package com.github.ttereshchenko.mailkit.conversion.msg;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
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
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull("Sibling .eml file should be created", eml);
        var content = new String(eml.contentsToByteArray(), StandardCharsets.US_ASCII);
        assertTrue("EML should contain Subject: " + content, content.contains("Subject: integration-marker"));
        assertTrue("EML should contain MIME-Version", content.contains("MIME-Version: 1.0"));
    }

    public void testMalformedMsgFailsGracefullyInsteadOfPropagating() throws Exception {
        // F4 regression: Apache POI and the converter raise UNCHECKED exceptions on malformed input. Here a
        // non-ASCII recipient email address leaks verbatim into the To: header (only display names are
        // RFC2047-encoded), tripping the converter's ASCII self-check -> IllegalStateException. The action
        // used to catch only IOException|ChunkNotFoundException, so the unchecked failure escaped the
        // background task and surfaced as an IDE "internal error". convertOrNotify now reports it and
        // returns null. (Email built at runtime so this source file stays pure ASCII.)
        var nonAsciiEmail = "rcpt" + (char) 0x00F8 + "@example.com";
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("graceful-failure")
                .sender("Sender", "sender@example.com")
                .recipientTo("Recipient", nonAsciiEmail)
                .textBody("body")
                .toBytes();
        var msgFile = createFileInProject("malformed.msg", bytes);

        var eml = ConvertMsgToEmlAction.convertOrNotify(getProject(), msgFile);

        assertNull("A malformed MSG must fail gracefully (notify + return null), not propagate", eml);
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
