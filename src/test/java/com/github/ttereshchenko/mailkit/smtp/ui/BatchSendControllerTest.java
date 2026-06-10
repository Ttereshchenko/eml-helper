package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.Phase;
import com.github.ttereshchenko.mailkit.smtp.SendResult;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.SmtpException;
import com.github.ttereshchenko.mailkit.smtp.SmtpTranscript;
import com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditLog;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives {@link BatchSendController} through a fake {@link BatchSendController.MessageSender}
 * (no sockets) and asserts sequential order, the two failure policies, cancellation, and the
 * one-audit-entry-per-attempt rule. The companion manual-verification samples are
 * {@code src/test/resources/samples/eml/smtp/batch_send_message_one.eml} and
 * {@code batch_send_message_two.eml}.
 */
public class BatchSendControllerTest extends BasePlatformTestCase {

    private static final BatchSendController.TranscriptListenerFactory NO_CONSOLE = (header, clearFirst) -> entry -> {};

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        SmtpAuditLog.getInstance(getProject()).clear();
    }

    public void testSendsSequentiallyOneSendPerFileAndReportsInOrder() throws Exception {
        var files = createEmlFiles("one.eml", "two.eml", "three.eml");
        var sendCount = new AtomicInteger();
        var recorder = new RecordingListener();
        var controller = new BatchSendController(
                getProject(),
                (config, envelope, source, cancel, listener) -> {
                    sendCount.incrementAndGet();
                    return successResult();
                },
                NO_CONSOLE);

        controller.start(buildRequest(files, SendDialog.FailurePolicy.CONTINUE_ON_FAILURE), recorder);
        recorder.awaitBatchFinished();

        assertEquals(3, sendCount.get());
        assertEquals(
                List.of(
                        "started 0",
                        "finished 0 SENT",
                        "started 1",
                        "finished 1 SENT",
                        "started 2",
                        "finished 2 SENT",
                        "batch 3 sent, 0 failed, 0 skipped, cancelled=false"),
                recorder.events);
        assertEquals(3, SmtpAuditLog.getInstance(getProject()).readAll().size());
    }

    public void testContinueOnFailureStillSendsTheRemainingFiles() throws Exception {
        var files = createEmlFiles("one.eml", "two.eml", "three.eml");
        var sendCount = new AtomicInteger();
        var recorder = new RecordingListener();
        var controller = new BatchSendController(
                getProject(),
                (config, envelope, source, cancel, listener) -> {
                    if (sendCount.incrementAndGet() == 2) {
                        throw new SmtpException(SmtpException.Kind.MAIL_REJECTED, Phase.MAIL, "550 nope");
                    }
                    return successResult();
                },
                NO_CONSOLE);

        controller.start(buildRequest(files, SendDialog.FailurePolicy.CONTINUE_ON_FAILURE), recorder);
        recorder.awaitBatchFinished();

        assertEquals(3, sendCount.get());
        assertEquals("finished 1 FAILED", recorder.events.get(3));
        assertEquals("batch 2 sent, 1 failed, 0 skipped, cancelled=false", recorder.events.get(6));
        var entries = SmtpAuditLog.getInstance(getProject()).readAll();
        assertEquals("one audit entry per attempted message", 3, entries.size());
        assertTrue(entries.get(0).success());
        assertFalse(entries.get(1).success());
        assertTrue(entries.get(2).success());
    }

    public void testStopOnFirstFailureSkipsTheRemainingFiles() throws Exception {
        var files = createEmlFiles("one.eml", "two.eml", "three.eml");
        var sendCount = new AtomicInteger();
        var recorder = new RecordingListener();
        var controller = new BatchSendController(
                getProject(),
                (config, envelope, source, cancel, listener) -> {
                    if (sendCount.incrementAndGet() == 2) {
                        throw new SmtpException(SmtpException.Kind.RCPT_REJECTED, Phase.RCPT, "550 unknown user");
                    }
                    return successResult();
                },
                NO_CONSOLE);

        controller.start(buildRequest(files, SendDialog.FailurePolicy.STOP_ON_FIRST_FAILURE), recorder);
        recorder.awaitBatchFinished();

        assertEquals("the third file must never be attempted", 2, sendCount.get());
        assertEquals(
                List.of(
                        "started 0",
                        "finished 0 SENT",
                        "started 1",
                        "finished 1 FAILED",
                        "finished 2 SKIPPED",
                        "batch 1 sent, 1 failed, 1 skipped, cancelled=false"),
                recorder.events);
        assertEquals(
                "skipped files get no audit entry",
                2,
                SmtpAuditLog.getInstance(getProject()).readAll().size());
    }

    public void testRequestCancelSkipsTheFilesNotYetStarted() throws Exception {
        var files = createEmlFiles("one.eml", "two.eml", "three.eml");
        var sendCount = new AtomicInteger();
        var recorder = new RecordingListener();
        var controllerReference = new BatchSendController[1];
        var controller = new BatchSendController(
                getProject(),
                (config, envelope, source, cancel, listener) -> {
                    if (sendCount.incrementAndGet() == 1) {
                        controllerReference[0].requestCancel();
                    }
                    return successResult();
                },
                NO_CONSOLE);
        controllerReference[0] = controller;

        controller.start(buildRequest(files, SendDialog.FailurePolicy.CONTINUE_ON_FAILURE), recorder);
        recorder.awaitBatchFinished();

        assertEquals("no further send after the cancel request", 1, sendCount.get());
        assertEquals(
                List.of(
                        "started 0",
                        "finished 0 SENT",
                        "finished 1 SKIPPED",
                        "finished 2 SKIPPED",
                        "batch 1 sent, 0 failed, 2 skipped, cancelled=true"),
                recorder.events);
    }

    public void testEmptySelectionSendsASingleEnvelopeOnlyMessage() throws Exception {
        var sendCount = new AtomicInteger();
        var recorder = new RecordingListener();
        var controller = new BatchSendController(
                getProject(),
                (config, envelope, source, cancel, listener) -> {
                    sendCount.incrementAndGet();
                    return successResult();
                },
                NO_CONSOLE);

        controller.start(buildRequest(List.of(), SendDialog.FailurePolicy.CONTINUE_ON_FAILURE), recorder);
        recorder.awaitBatchFinished();

        assertEquals(1, sendCount.get());
        assertEquals("batch 1 sent, 0 failed, 0 skipped, cancelled=false", recorder.events.get(2));
    }

    private List<VirtualFile> createEmlFiles(String... names) throws Exception {
        var sampleBody = Files.readString(Path.of("src/test/resources/samples/eml/smtp/batch_send_message_one.eml"));
        var files = new ArrayList<VirtualFile>(names.length);
        for (var name : names) {
            files.add(myFixture.getTempDirFixture().createFile(name, sampleBody));
        }
        return files;
    }

    private static SendDialog.SendRequest buildRequest(
            List<VirtualFile> files, SendDialog.FailurePolicy failurePolicy) {
        return new SendDialog.SendRequest(
                SmtpConfig.defaults("localhost"),
                SmtpEnvelope.of("from@example.com", "to@example.com"),
                files,
                failurePolicy);
    }

    private static SendResult successResult() {
        return new SendResult(
                new SmtpTranscript(),
                List.of(new SendResult.RecipientDisposition("to@example.com", 250, "OK", true)),
                Duration.ofMillis(5),
                Phase.DOT,
                true,
                Map.of(),
                SendResult.TlsOutcome.none());
    }

    private static final class RecordingListener implements BatchSendController.BatchListener {

        // Appended only from the controller's single background thread; read after the latch.
        final List<String> events = new ArrayList<>();
        private final CountDownLatch batchFinished = new CountDownLatch(1);

        @Override
        public void fileStarted(int index) {
            events.add("started " + index);
        }

        @Override
        public void fileFinished(int index, BatchSendController.FileStatus status, String detail) {
            events.add("finished " + index + " " + status);
        }

        @Override
        public void batchFinished(int sent, int failed, int skipped, boolean cancelled) {
            events.add(
                    "batch " + sent + " sent, " + failed + " failed, " + skipped + " skipped, cancelled=" + cancelled);
            batchFinished.countDown();
        }

        void awaitBatchFinished() throws InterruptedException {
            assertTrue("batchFinished was not reported in time", batchFinished.await(30, TimeUnit.SECONDS));
        }
    }
}
