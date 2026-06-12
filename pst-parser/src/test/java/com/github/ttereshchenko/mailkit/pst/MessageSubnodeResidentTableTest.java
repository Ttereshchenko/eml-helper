package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * F1 regression, end to end through the public {@link Message} API: a message-internal table
 * (recipients / attachments) whose row matrix is subnode-resident stores it in the table's own
 * nested subnode tree ([MS-PST] §2.3.3.2, §2.2.2.8.3.3) — not in the message's tree. The old code
 * passed the message node to the {@code TableContext}, looked the row matrix up in the wrong tree
 * and silently lost every recipient and attachment of such messages (mass-recipient mail,
 * many-attachment messages), or fabricated rows from an unrelated heap item.
 *
 * <p>Runs across all three block-encoding schemes; the HIGH ("strong") variant doubles as the
 * end-to-end fixture for cyclic encryption, for which no public archive exists — the store is
 * generated rather than vendored.
 */
class MessageSubnodeResidentTableTest {

    @Test
    void recipientsAndAttachmentsResolveInTheTablesOwnSubnodeTree() throws Exception {
        for (var encryptionType : PstFile.EncryptionType.values()) {
            var store = Files.createTempFile("synthetic_nested_" + encryptionType, ".pst");
            try {
                SyntheticUnicodeStore.write(store, encryptionType, 60);
                assertStoreReadsFully(store, encryptionType);
            } finally {
                Files.deleteIfExists(store);
            }
        }
    }

    private static void assertStoreReadsFully(Path store, PstFile.EncryptionType encryptionType) throws Exception {
        try (var pst = new PstFile(store)) {
            assertEquals(PstFile.Format.UNICODE, pst.format());
            assertEquals(encryptionType, pst.encryptionType(), "The store must carry the requested crypt method");

            var message = new Message(pst, SyntheticUnicodeStore.MESSAGE_NID);
            assertTrue(
                    message.isLoaded(),
                    () -> "Message must load under " + encryptionType + ": " + message.getLoadError());
            assertEquals(SyntheticUnicodeStore.MESSAGE_CLASS, message.getMessageClass());
            assertEquals(SyntheticUnicodeStore.SUBJECT, message.getSubject());

            var recipients = message.getRecipients();
            assertEquals(
                    60,
                    recipients.size(),
                    "All recipients must survive a subnode-resident row matrix under " + encryptionType);
            assertEquals(SyntheticUnicodeStore.FIRST_RECIPIENT_NAME, recipients.get(0).name);
            for (var index = 0; index < recipients.size(); index++) {
                assertEquals(
                        1 + (index % 3),
                        recipients.get(index).type,
                        "Recipient " + index + " must keep its To/Cc/Bcc type");
            }

            var attachments = message.getAttachments();
            assertEquals(
                    1,
                    attachments.size(),
                    "The attachment must survive a subnode-resident attachment table under " + encryptionType);
            assertEquals(
                    SyntheticUnicodeStore.ATTACHMENT_FILENAME,
                    attachments.get(0).getLongFilename());
            assertEquals(1, attachments.get(0).getAttachMethod());
            assertArrayEquals(
                    SyntheticUnicodeStore.ATTACHMENT_CONTENT, attachments.get(0).getData());
        }
    }
}
