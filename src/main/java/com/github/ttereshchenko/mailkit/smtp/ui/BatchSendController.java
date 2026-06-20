package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.CancellationToken;
import com.github.ttereshchenko.mailkit.smtp.MessageSource;
import com.github.ttereshchenko.mailkit.smtp.SendResult;
import com.github.ttereshchenko.mailkit.smtp.SmtpClient;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.SmtpException;
import com.github.ttereshchenko.mailkit.smtp.SmtpTranscript;
import com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditEntry;
import com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditLog;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a batch of SMTP sends sequentially on a pooled thread — one {@link SmtpClient#send} (fresh
 * connection) per file — and reports per-file progress back through {@link BatchListener}. The
 * listener is invoked on the background thread; UI consumers must marshal to the EDT themselves.
 * Each attempted message gets its own audit-log entry and console transcript section, exactly as a
 * single send did before batching existed.
 */
final class BatchSendController {

    /** Terminal and transient states a file in the batch moves through. */
    enum FileStatus {
        PENDING,
        SENDING,
        SENT,
        FAILED,
        SKIPPED
    }

    /** Seam over {@link SmtpClient#send} so tests can run a batch without sockets. */
    @FunctionalInterface
    interface MessageSender {
        SendResult send(
                SmtpConfig config,
                SmtpEnvelope envelope,
                MessageSource source,
                CancellationToken cancel,
                SmtpTranscript.Listener listener)
                throws SmtpException;
    }

    /** Seam over {@link SmtpConsoleService#liveTranscriptListener} for headless tests. */
    @FunctionalInterface
    interface TranscriptListenerFactory {
        SmtpTranscript.Listener create(String header, boolean clearFirst);
    }

    /** Progress callbacks; all invoked on the controller's background thread. */
    interface BatchListener {
        void fileStarted(int index);

        void fileFinished(int index, FileStatus status, String detail);

        void batchFinished(int sent, int failed, int skipped, boolean cancelled);
    }

    private final Project project;
    private final MessageSender sender;
    private final TranscriptListenerFactory transcriptListeners;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    BatchSendController(Project project) {
        this(
                project,
                (config, envelope, source, cancel, listener) ->
                        new SmtpClient().send(config, envelope, source, cancel, listener),
                (header, clearFirst) ->
                        SmtpConsoleService.getInstance(project).liveTranscriptListener(header, clearFirst));
    }

    BatchSendController(Project project, MessageSender sender, TranscriptListenerFactory transcriptListeners) {
        this.project = Objects.requireNonNull(project, "project");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.transcriptListeners = Objects.requireNonNull(transcriptListeners, "transcriptListeners");
    }

    /** Asks the batch to stop: skips files not yet started and aborts the in-flight transaction. */
    void requestCancel() {
        cancelRequested.set(true);
    }

    /** Starts the batch on a pooled thread. The returned future completes after {@code batchFinished}. */
    Future<?> start(SendDialog.SendRequest request, BatchListener listener) {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> runBatch(request, listener));
    }

    private void runBatch(SendDialog.SendRequest request, BatchListener listener) {
        // An empty selection is the legacy "envelope only" mode: one message with an empty body.
        var files =
                request.sourceFiles().isEmpty() ? Collections.<VirtualFile>singletonList(null) : request.sourceFiles();
        var total = files.size();
        var profileName = resolveProfileName(request);
        CancellationToken cancel = cancelRequested::get;
        var sent = 0;
        var failed = 0;
        var skipped = 0;
        SendResult lastResult = null;
        SmtpException lastFailure = null;

        for (var index = 0; index < total; index++) {
            if (cancelRequested.get()) {
                skipped += markRemainingSkipped(listener, index, total, "Cancelled");
                break;
            }
            var file = files.get(index);
            listener.fileStarted(index);
            var transcript = transcriptListeners.create(transcriptHeader(request, file, index, total), index == 0);
            var source = buildSource(file);
            var sourceBytes = source.size().orElse(0);
            var started = Instant.now();
            var startNanos = System.nanoTime();
            try {
                var result = sender.send(request.config(), request.envelope(), source, cancel, transcript);
                var elapsed = (System.nanoTime() - startNanos) / 1_000_000;
                recordAuditEntry(request, profileName, sourceBytes, started, elapsed, result, null);
                lastResult = result;
                sent++;
                listener.fileFinished(index, FileStatus.SENT, sentDetail(request, result));
            } catch (SmtpException failure) {
                var elapsed = (System.nanoTime() - startNanos) / 1_000_000;
                recordAuditEntry(request, profileName, sourceBytes, started, elapsed, null, failure);
                lastFailure = failure;
                failed++;
                listener.fileFinished(index, FileStatus.FAILED, failureDetail(failure));
                if (request.failurePolicy() == SendDialog.FailurePolicy.STOP_ON_FIRST_FAILURE) {
                    skipped += markRemainingSkipped(listener, index + 1, total, "Stopped after earlier failure");
                    break;
                }
            }
        }

        var cancelled = cancelRequested.get();
        listener.batchFinished(sent, failed, skipped, cancelled);
        notifyBatchOutcome(request, total, sent, failed, skipped, cancelled, lastResult, lastFailure);
    }

    /**
     * Builds the DATA source for one message, transmitting the selected {@code .eml} unmodified —
     * the client sends the original bytes verbatim and never removes or rewrites any header field
     * (including {@code Bcc:}). A local file streams straight from disk via {@link Path} so a large
     * message is not buffered for the whole send; any other {@link VirtualFile} is read through the
     * VFS and sent from memory. An empty selection is the legacy "envelope only" mode (a {@code null}
     * file → empty body).
     */
    private static MessageSource buildSource(@Nullable VirtualFile file) {
        if (file == null) {
            return MessageSource.ofString("");
        }
        if (file.isInLocalFileSystem()) {
            return MessageSource.ofPath(Path.of(file.getPath()));
        }
        byte[] raw;
        try (var input = file.getInputStream()) {
            raw = input.readAllBytes();
        } catch (IOException ignored) {
            // Could not read the non-local file into memory; stream from its path and let the SMTP
            // client surface any read error.
            return MessageSource.ofPath(Path.of(file.getPath()));
        }
        return MessageSource.ofBytes(raw);
    }

    private static int markRemainingSkipped(BatchListener listener, int firstIndex, int total, String reason) {
        for (var index = firstIndex; index < total; index++) {
            listener.fileFinished(index, FileStatus.SKIPPED, reason);
        }
        return total - firstIndex;
    }

    private static String transcriptHeader(
            SendDialog.SendRequest request, @Nullable VirtualFile file, int index, int total) {
        var destination =
                "Sending to " + request.config().host() + ":" + request.config().port();
        if (total == 1) {
            return destination;
        }
        var name = file == null ? "(envelope only)" : file.getName();
        return "[" + (index + 1) + "/" + total + "] " + name + " — " + destination;
    }

    private static String sentDetail(SendDialog.SendRequest request, SendResult result) {
        // Count only RCPT TO that the server accepted — a rejected recipient was never delivered to,
        // so it must not inflate the "Delivered to N" figure.
        var recipientCount = acceptedCount(result);
        var detail = "Delivered to " + recipientCount + " recipient" + (recipientCount == 1 ? "" : "s");
        if (request.config().stopAfter() != null) {
            detail = "Stopped after " + request.config().stopAfter().name() + " (debug)";
        }
        return detail;
    }

    /** Number of recipients the server actually accepted (RCPT TO answered with a 2xx). */
    private static long acceptedCount(SendResult result) {
        return result.recipientDispositions().stream()
                .filter(SendResult.RecipientDisposition::accepted)
                .count();
    }

    private static String failureDetail(SmtpException failure) {
        var message = failure.getMessage() == null ? "" : ": " + failure.getMessage();
        return failure.kind() + " @ " + failure.phase() + message;
    }

    private static String resolveProfileName(SendDialog.SendRequest request) {
        // Use the profile the user actually selected (threaded through the request), not a host:port
        // reverse-match: two profiles can share a host:port, and a per-send host override would
        // otherwise mis-attribute (or "ad-hoc"-attribute) a send a real profile drove.
        var profileName = request.profileName();
        return profileName != null && !profileName.isBlank() ? profileName : "ad-hoc";
    }

    private void recordAuditEntry(
            SendDialog.SendRequest request,
            String profileName,
            long sourceBytes,
            Instant started,
            long elapsedMillis,
            @Nullable SendResult result,
            @Nullable SmtpException failure) {
        var dispositions = new ArrayList<SmtpAuditEntry.Recipient>();
        if (result != null) {
            for (var disposition : result.recipientDispositions()) {
                dispositions.add(new SmtpAuditEntry.Recipient(
                        disposition.address(), disposition.code(), disposition.text(), disposition.accepted()));
            }
        }
        var auth = request.config().auth();
        var mechanism = auth == null || auth.isDisabled() || auth.mechanism() == null
                ? "(none)"
                : auth.mechanism().wireName();
        // Prefer the successful send's TLS outcome; on failure fall back to the partial TLS state the
        // exception carries, so a send that failed AFTER TLS came up (e.g. an AUTH/RCPT rejection) is
        // still recorded as encrypted instead of showing empty TLS fields.
        var tls = result != null ? result.tls() : (failure != null ? failure.tls() : null);
        var tlsActive = tls != null && tls.active();
        var entry = new SmtpAuditEntry(
                started,
                profileName,
                request.config().host(),
                request.config().port(),
                tlsActive ? tls.protocol() : "",
                tlsActive ? tls.cipherSuite() : "",
                mechanism,
                request.envelope().mailFrom(),
                dispositions,
                sourceBytes,
                elapsedMillis,
                request.config().stopAfter() == null
                        ? ""
                        : request.config().stopAfter().name(),
                request.config().dropAfter(),
                failure == null,
                failure == null ? "" : failure.kind().name(),
                failure == null ? "" : failure.phase().name(),
                failure == null ? "" : (failure.getMessage() == null ? "" : failure.getMessage()));
        SmtpAuditLog.getInstance(project).append(entry);
    }

    private void notifyBatchOutcome(
            SendDialog.SendRequest request,
            int total,
            int sent,
            int failed,
            int skipped,
            boolean cancelled,
            @Nullable SendResult lastResult,
            @Nullable SmtpException lastFailure) {
        // Single-message batches keep the pre-batching notification wording.
        if (total == 1 && lastFailure != null) {
            notify(
                    "SMTP send failed: " + lastFailure.kind() + " @ " + lastFailure.phase(),
                    lastFailure.getMessage(),
                    NotificationType.ERROR);
            return;
        }
        if (total == 1 && lastResult != null) {
            // Only accepted RCPT TO count as delivered — a 5xx-rejected recipient was not delivered to.
            var recipientCount = acceptedCount(lastResult);
            var text = "Delivered to " + recipientCount + " recipient" + (recipientCount == 1 ? "" : "s") + " — "
                    + (lastResult.cleanlyClosed() ? "QUIT 221 OK" : "socket closed without 221");
            notify("SMTP send succeeded", text, NotificationType.INFORMATION);
            return;
        }
        if (total == 1) {
            // Cancelled before the only message started.
            notify("SMTP send cancelled", "The message was not sent.", NotificationType.INFORMATION);
            return;
        }
        var parts = new ArrayList<String>();
        parts.add(sent + " sent");
        if (failed > 0) {
            parts.add(failed + " failed");
        }
        if (skipped > 0) {
            parts.add(skipped + " skipped");
        }
        var text = String.join(", ", parts);
        if (request.config().stopAfter() != null) {
            text += " — stopped after " + request.config().stopAfter().name() + " (debug), nothing was delivered";
        }
        var type = failed > 0 ? NotificationType.ERROR : NotificationType.INFORMATION;
        var title = cancelled ? "SMTP batch send cancelled" : "SMTP batch send finished";
        notify(title, text, type);
    }

    private void notify(String title, @Nullable String text, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MailKit")
                .createNotification(title, text == null ? "" : text, type)
                .notify(project);
    }

    /** Visible for the dialog's tests: whether a cancel has been requested. */
    boolean isCancelRequested() {
        return cancelRequested.get();
    }
}
