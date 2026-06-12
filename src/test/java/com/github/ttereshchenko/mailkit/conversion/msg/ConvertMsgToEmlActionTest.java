package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class ConvertMsgToEmlActionTest extends BasePlatformTestCase {

    public void testActionVisibleForMsgFile() throws Exception {
        var virtualFile = createFileInProject("sample.msg", new byte[] {0x00});
        var event = makeEvent(virtualFile);
        new ConvertMsgToEmlAction().update(event);
        assertTrue("Action should be visible for .msg", event.getPresentation().isVisible());
        assertTrue("Action should be enabled for .msg", event.getPresentation().isEnabled());
    }

    public void testActionHiddenForEmlFile() throws Exception {
        var virtualFile = createFileInProject("sample.eml", "From: a@b\n".getBytes(StandardCharsets.US_ASCII));
        var event = makeEvent(virtualFile);
        new ConvertMsgToEmlAction().update(event);
        assertFalse("Action should be hidden for .eml", event.getPresentation().isVisible());
    }

    public void testActionHiddenForUnrelatedFile() throws Exception {
        var virtualFile = createFileInProject("notes.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        var event = makeEvent(virtualFile);
        new ConvertMsgToEmlAction().update(event);
        assertFalse("Action should be hidden for .txt", event.getPresentation().isVisible());
    }

    public void testActionHiddenWhenNoFileSelected() {
        var event = makeEvent(null);
        new ConvertMsgToEmlAction().update(event);
        assertFalse(
                "Action should be hidden when no file selected",
                event.getPresentation().isVisible());
    }

    public void testConversionWritesSiblingEml() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("integration-marker")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .messageDate(new Date(1715817600000L))
                .textBody("integration body")
                .toBytes();
        var msgFile = createFileInProject("sample.msg", bytes);

        ConvertMsgToEmlAction.runConversion(getProject(), msgFile);

        var deadline = System.currentTimeMillis() + 5000;
        VirtualFile eml = null;
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            ApplicationManager.getApplication().invokeAndWait(() -> {});
            var parent = msgFile.getParent();
            parent.refresh(false, false);
            eml = parent.findChild("sample.eml");
            if (eml != null && eml.getLength() > 0) {
                var editorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject());
                if (editorManager.isFileOpen(eml)) {
                    break;
                }
            }
            Thread.sleep(50);
        }
        assertNotNull("Sibling .eml file should be created", eml);
        var content = new String(eml.contentsToByteArray(), StandardCharsets.US_ASCII);
        assertTrue("EML should contain Subject: " + content, content.contains("Subject: integration-marker"));
        assertTrue("EML should contain MIME-Version", content.contains("MIME-Version: 1.0"));

        VirtualFile finalEml = eml;
        ApplicationManager.getApplication().invokeAndWait(() -> {
            com.intellij
                    .openapi
                    .fileEditor
                    .FileEditorManager
                    .getInstance(getProject())
                    .closeFile(finalEml);
        });
    }

    public void testNonAsciiRecipientEmailIsPreservedNotCrashing() throws Exception {
        // Previously the US-ASCII output writer aborted (UnmappableCharacterException) when a non-ASCII
        // address leaked verbatim into a header — only display names are RFC 2047-encoded. The converter
        // now writes UTF-8 (matching the PST path), preserving the address instead of failing the whole
        // conversion. Genuinely malformed (non-OLE) input still throws — see
        // MsgToEmlConverterTest.emptyStreamFailsLoudly. (Email built at runtime so this file stays ASCII.)
        var nonAsciiEmail = "rcpt" + (char) 0x00F8 + "@example.com";
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("graceful")
                .sender("Sender", "sender@example.com")
                .recipientTo("Recipient", nonAsciiEmail)
                .textBody("body")
                .toBytes();

        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new java.io.ByteArrayInputStream(bytes), out, ConversionLog.NOOP);
        var eml = out.toString(StandardCharsets.UTF_8);

        assertTrue("Non-ASCII recipient email should be preserved, not crash: " + eml, eml.contains(nonAsciiEmail));
    }

    public void testExistingTargetPreservedWhenOverwriteDeclined() throws Exception {
        // R6: converting a.msg used to clobber a pre-existing (possibly hand-edited) a.eml without
        // asking; the conversion must now stop when the user declines the overwrite prompt.
        var msgBytes =
                MsgFixtureBuilder.topLevel().subject("decline").textBody("body").toBytes();
        var msgFile = createFileInProject("decline.msg", msgBytes);
        var existing = createFileInProject("decline.eml", "hand-edited content".getBytes(StandardCharsets.US_ASCII));

        var previousDialog = TestDialogManager.setTestDialog(TestDialog.NO);
        try {
            ConvertMsgToEmlAction.runConversion(getProject(), msgFile);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            ApplicationManager.getApplication().invokeAndWait(() -> {});
        } finally {
            TestDialogManager.setTestDialog(previousDialog);
        }

        var content = new String(existing.contentsToByteArray(), StandardCharsets.US_ASCII);
        assertEquals("Declining the overwrite must leave the existing file untouched", "hand-edited content", content);
    }

    public void testExistingTargetReplacedWhenOverwriteConfirmed() throws Exception {
        var msgBytes = MsgFixtureBuilder.topLevel()
                .subject("confirm-marker")
                .textBody("body")
                .toBytes();
        var msgFile = createFileInProject("confirm.msg", msgBytes);
        var existing = createFileInProject("confirm.eml", "stale".getBytes(StandardCharsets.US_ASCII));

        var previousDialog = TestDialogManager.setTestDialog(TestDialog.OK);
        try {
            ConvertMsgToEmlAction.runConversion(getProject(), msgFile);
            var deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                ApplicationManager.getApplication().invokeAndWait(() -> {});
                if (new String(existing.contentsToByteArray(), StandardCharsets.US_ASCII).contains("confirm-marker")) {
                    break;
                }
                Thread.sleep(50);
            }
        } finally {
            TestDialogManager.setTestDialog(previousDialog);
        }

        var content = new String(existing.contentsToByteArray(), StandardCharsets.US_ASCII);
        assertTrue(
                "Confirming the overwrite must replace the stale file: " + content,
                content.contains("Subject: confirm-marker"));

        ApplicationManager.getApplication().invokeAndWait(() -> {
            com.intellij
                    .openapi
                    .fileEditor
                    .FileEditorManager
                    .getInstance(getProject())
                    .closeFile(existing);
        });
    }

    public void testFindCancellationUnwrapsWrappedCancellation() {
        // R12: the converter wraps every RuntimeException — including the progress indicator's
        // ProcessCanceledException thrown from a log checkpoint — in ConversionException; the action
        // must recognize buried cancellations instead of reporting them as conversion failures.
        var canceled = new ProcessCanceledException();
        var wrapped = new ConversionException("Failed to convert MSG to EML: canceled", new RuntimeException(canceled));
        assertSame(canceled, ConvertMsgToEmlAction.findCancellation(wrapped));
        assertNull(ConvertMsgToEmlAction.findCancellation(new RuntimeException("plain failure")));
    }

    private AnActionEvent makeEvent(VirtualFile file) {
        var builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject());
        if (file != null) {
            builder.add(CommonDataKeys.VIRTUAL_FILE, file);
        }
        return TestActionEvent.createTestEvent(builder.build());
    }

    private VirtualFile createFileInProject(String name, byte[] bytes) throws IOException {
        var virtualFile = myFixture.getTempDirFixture().createFile(name);
        assertNotNull("Could not create temp file " + name, virtualFile);
        WriteAction.runAndWait(() -> virtualFile.setBinaryContent(bytes));
        return virtualFile;
    }
}
