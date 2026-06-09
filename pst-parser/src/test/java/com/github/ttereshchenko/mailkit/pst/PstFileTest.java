package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the library's primary use case: open a real PST file and walk it from
 * the root folder down to an individual {@link Message}. Complements the unit tests that exercise
 * the NDB/HN/PC/TC internals in isolation. Fixture: {@code samples/pst/dist-list.pst} (UNICODE).
 */
class PstFileTest {

    private static final int NID_ROOT_FOLDER = 0x122;

    private static Path fixture(String name) throws Exception {
        var url = PstFileTest.class.getResource("/samples/pst/" + name);
        assertNotNull(url, "Missing test fixture: " + name);
        return Path.of(url.toURI());
    }

    @Test
    void opensHeaderAndReportsFormat() throws Exception {
        try (var pst = new PstFile(fixture("dist-list.pst"))) {
            assertEquals(PstFile.Format.UNICODE, pst.format());
            assertNotNull(pst.encryptionType());
            assertNotNull(pst.nodeDatabase());
        }
    }

    @Test
    void walksFoldersAndReadsAMessage() throws Exception {
        try (var pst = new PstFile(fixture("dist-list.pst"))) {
            var root = new Folder(pst, NID_ROOT_FOLDER);
            assertNotNull(root.getDisplayName());

            var messageNids = collectMessageNids(root);
            assertFalse(messageNids.isEmpty(), "Expected to discover at least one message while walking the store");

            var message = new Message(pst, messageNids.get(0));
            // The message must be readable end-to-end without throwing; exact content varies by item.
            assertDoesNotThrow(message::getSubject);
            assertNotNull(message.getMessageClass());
        }
    }

    private static List<Integer> collectMessageNids(Folder folder) throws PstException {
        var nids = new ArrayList<>(folder.getMessages());
        for (var child : folder.getSubFolders()) {
            nids.addAll(collectMessageNids(child));
        }
        return nids;
    }
}
