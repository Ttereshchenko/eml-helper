package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.CancellationToken;
import com.github.ttereshchenko.mailkit.smtp.MessageSource;
import com.github.ttereshchenko.mailkit.smtp.SmtpClient;
import com.github.ttereshchenko.mailkit.smtp.SmtpException;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Editor / project popup entry point for "Send via SMTP…". Hidden when the global egress toggle
 * is off. Opens {@link SendDialog}; if the user confirms, dispatches the send on a
 * {@link Task.Backgroundable} so the UI thread stays free.
 */
public final class SendViaSmtpAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var enabled = SmtpProfileService.getInstance().isEgressEnabled();
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        var isEml = file != null && "eml".equalsIgnoreCase(file.getExtension());
        event.getPresentation().setEnabledAndVisible(enabled && isEml);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        ApplicationManager.getApplication().invokeLater(() -> openDialog(project, file));
    }

    private static void openDialog(Project project, @Nullable VirtualFile sourceFile) {
        var dialog = new SendDialog(project, sourceFile);
        if (!dialog.showAndGet()) {
            return;
        }
        var request = dialog.getCommittedRequest();
        if (request == null) {
            return;
        }
        dispatchSend(project, request);
    }

    static void dispatchSend(Project project, SendDialog.SendRequest request) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Sending via SMTP", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                runSend(project, request, indicator);
            }
        });
    }

    private static void runSend(Project project, SendDialog.SendRequest request, ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        indicator.setText("Opening connection to " + request.config().host() + ":"
                + request.config().port());
        var listener = SmtpConsoleService.getInstance(project)
                .liveTranscriptListener("Sending to " + request.config().host() + ":"
                        + request.config().port());
        CancellationToken cancel = indicator::isCanceled;
        MessageSource source = buildSource(request);
        var sourceBytes = source.size().orElse(0);
        var profileName = resolveProfileName(request);
        var started = java.time.Instant.now();
        var startNanos = System.nanoTime();
        try {
            var result = new SmtpClient().send(request.config(), request.envelope(), source, cancel, listener);
            var elapsed = (System.nanoTime() - startNanos) / 1_000_000;
            recordAuditEntry(project, request, profileName, sourceBytes, started, elapsed, result, null);
            notifySuccess(project, result.recipientDispositions().size(), result.cleanlyClosed());
        } catch (SmtpException failure) {
            var elapsed = (System.nanoTime() - startNanos) / 1_000_000;
            recordAuditEntry(project, request, profileName, sourceBytes, started, elapsed, null, failure);
            notifyFailure(project, failure);
        }
    }

    private static String resolveProfileName(SendDialog.SendRequest request) {
        var defaultProfile =
                com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService.getInstance().getProfiles().stream()
                        .filter(profile -> profile.host.equals(request.config().host())
                                && profile.port == request.config().port())
                        .findFirst();
        return defaultProfile.map(profile -> profile.name).orElse("ad-hoc");
    }

    private static void recordAuditEntry(
            Project project,
            SendDialog.SendRequest request,
            String profileName,
            long sourceBytes,
            java.time.Instant started,
            long elapsedMillis,
            com.github.ttereshchenko.mailkit.smtp.SendResult result,
            SmtpException failure) {
        var dispositions =
                new java.util.ArrayList<com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditEntry.Recipient>();
        if (result != null) {
            for (var disposition : result.recipientDispositions()) {
                dispositions.add(new com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditEntry.Recipient(
                        disposition.address(), disposition.code(), disposition.text(), disposition.accepted()));
            }
        }
        var auth = request.config().auth();
        var mechanism = auth == null || auth.isDisabled() || auth.mechanism() == null
                ? "(none)"
                : auth.mechanism().wireName();
        var tlsActive = result != null && result.tls().active();
        var entry = new com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditEntry(
                started,
                profileName,
                request.config().host(),
                request.config().port(),
                tlsActive ? result.tls().protocol() : "",
                tlsActive ? result.tls().cipherSuite() : "",
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
        com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditLog.getInstance(project)
                .append(entry);
    }

    private static MessageSource buildSource(SendDialog.SendRequest request) {
        if (request.sourceFile() == null) {
            return MessageSource.ofString("");
        }
        var path = request.sourcePath();
        return MessageSource.ofPath(path);
    }

    private static void notifySuccess(Project project, int recipientCount, boolean cleanlyClosed) {
        var text = "Delivered to " + recipientCount + " recipient" + (recipientCount == 1 ? "" : "s") + " — "
                + (cleanlyClosed ? "QUIT 221 OK" : "socket closed without 221");
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MailKit")
                .createNotification("SMTP send succeeded", text, NotificationType.INFORMATION)
                .notify(project);
    }

    private static void notifyFailure(Project project, SmtpException failure) {
        var headline = "SMTP send failed: " + failure.kind() + " @ " + failure.phase();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MailKit")
                .createNotification(headline, failure.getMessage(), NotificationType.ERROR)
                .notify(project);
    }
}
