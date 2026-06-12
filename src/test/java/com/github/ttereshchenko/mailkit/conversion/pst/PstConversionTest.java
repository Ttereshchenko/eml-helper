package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.Folder;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class PstConversionTest {

    // The pure open/walk/read assertions for passworded.pst, dummy_ansi.pst and example-2013.ost
    // moved to the pst-parser subproject's own suite (PstFileTest) so the standalone library carries
    // its format coverage; this class keeps only conversion-specific tests.

    // ansi-test.pst is the genuine ANSI-format sample (test_ansi.pst from the pstsdk/Fairport test
    // corpus); the previous file at this path was a saved GitHub 404 HTML page, so conversion of a
    // real ANSI PST was never exercised end-to-end.
    @Test
    void testAnsiPstConversion() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/ansi-test.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_ansi_convert");
        try (var pstFile = new PstFile(path)) {
            assertEquals(PstFile.Format.ANSI, pstFile.format());
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    50,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var stats = PstToEmlConverter.convert(pstFile, tempDir, options, indicator, ConversionLog.NOOP);
            org.junit.jupiter.api.Assertions.assertTrue(
                    stats.converted() > 0, "ANSI PST conversion should export messages");

            java.util.List<Path> emls;
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                emls = stream.filter(entry -> entry.toString().endsWith(".eml")).toList();
            }
            org.junit.jupiter.api.Assertions.assertFalse(emls.isEmpty(), "Expected EML files from the ANSI PST");
            for (Path eml : emls) {
                String content = java.nio.file.Files.readString(eml);
                org.junit.jupiter.api.Assertions.assertTrue(
                        content.startsWith("From:") || content.contains("\nFrom:") || content.startsWith("Date:"),
                        "Exported EML should carry headers: " + eml);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void testDistListPst() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_test_dist");
        try (var pstFile = new PstFile(path)) {
            var rootFolder = new Folder(pstFile, 0x122);
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    50,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var stats = new PstToEmlConverter.Stats();
            PstToEmlConverter.processFolder(
                    pstFile, rootFolder.getNid(), tempDir, options, stats, indicator, "", ConversionLog.NOOP);

            java.util.List<Path> emls = java.nio.file.Files.walk(tempDir)
                    .filter(pathEntry -> pathEntry.toString().endsWith(".eml"))
                    .toList();
            // IPM.Appointment is now on the allow-list, so dist-list.pst's appointments are exported (each
            // carrying a calendar invite); IPM.DistList items are still filtered out.
            org.junit.jupiter.api.Assertions.assertFalse(
                    emls.isEmpty(), "Expected appointment EML files now that IPM.Appointment is allowed");
            boolean foundInvite = false;
            for (Path eml : emls) {
                String content = java.nio.file.Files.readString(eml);
                // The ICS payload is base64-encoded inside the attachment, so assert on the (unencoded)
                // calendar content-type and the invite.ics filename rather than the VCALENDAR body.
                if (content.contains("text/calendar") && content.contains("invite.ics")) {
                    foundInvite = true;
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                    foundInvite, "Expected at least one exported appointment to carry an invite.ics");
        } finally {
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    @Test
    void testTikaTestPst() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        try (var pstFile = new PstFile(path)) {
            var rootFolder = new Folder(pstFile, 0x122);
            assertNotNull(rootFolder);
            assertDoesNotThrow(rootFolder::getSubFolders);
            assertDoesNotThrow(rootFolder::getMessages);
        }
    }

    @Test
    void testVariousBodyTypesPst() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/testPST_variousBodyTypes.pst");
        try (var pstFile = new PstFile(path)) {
            var rootFolder = new Folder(pstFile, 0x122);
            assertNotNull(rootFolder);
            assertDoesNotThrow(rootFolder::getSubFolders);
            assertDoesNotThrow(rootFolder::getMessages);
        }
    }

    @Test
    void testConversionOutput() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_test");
        try (var pstFile = new PstFile(path)) {
            var rootFolder = new Folder(pstFile, 0x122);
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    50,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var stats = new PstToEmlConverter.Stats();
            PstToEmlConverter.processFolder(
                    pstFile, rootFolder.getNid(), tempDir, options, stats, indicator, "", ConversionLog.NOOP);

            java.util.List<Path> emls = java.nio.file.Files.walk(tempDir)
                    .filter(pathEntry -> pathEntry.toString().endsWith(".eml"))
                    .toList();
            org.junit.jupiter.api.Assertions.assertFalse(emls.isEmpty(), "Expected to extract some EML files");

            boolean foundRoutableFrom = false;
            boolean foundBodyText = false;
            boolean foundAttachment = false;

            for (Path eml : emls) {
                String content = java.nio.file.Files.readString(eml);

                if (java.util.regex.Pattern.compile("(?m)^From:.*<[^/]*@[^>]+>")
                        .matcher(content)
                        .find()) {
                    foundRoutableFrom = true;
                }

                if (content.contains("Nick Burch resolved TIKA-1249")) {
                    foundBodyText = true;
                }

                if (content.contains("Content-Disposition: attachment")) {
                    foundAttachment = true;
                    var matcher = java.util.regex.Pattern.compile(
                                    "(?i)(?s)Content-Transfer-Encoding: base64.*?\\r\\n\\r\\n(.*?)(?:\\r\\n--)")
                            .matcher(content);
                    org.junit.jupiter.api.Assertions.assertTrue(
                            matcher.find(), "Attachment should have base64 encoding");
                    String b64 = matcher.group(1).replaceAll("\\s+", "");
                    byte[] decoded = java.util.Base64.getDecoder().decode(b64);
                    org.junit.jupiter.api.Assertions.assertTrue(
                            decoded.length > 0, "Attachment should have decoded bytes");
                }

                // Appointments are no longer skipped (IPM.Appointment is on the allow-list); when one is
                // exported it carries a calendar invite, so the invite.ics attachment must declare a
                // text/calendar content-type.
                if (content.contains("invite.ics")) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            content.contains("text/calendar"), "Appointment EML should declare a calendar part");
                }

                // Verify Date is in GMT (eliminates timezone flakiness)
                if (content.contains("Date:")) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            java.util.regex.Pattern.compile("(?m)^Date: .*[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} \\+0000$")
                                    .matcher(content)
                                    .find(),
                            "Date should be formatted in GMT (+0000)");
                }
            }

            org.junit.jupiter.api.Assertions.assertTrue(foundRoutableFrom, "Should have a routable SMTP From");
            org.junit.jupiter.api.Assertions.assertTrue(foundBodyText, "Should have decoded body text");
        } finally {
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    @Test
    void testMessageWithAttachmentExtraction() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        try (var pstFile = new PstFile(path)) {
            // Mock a message with an attachment using an anonymous subclass
            var msg = new Message(pstFile, 0x122) {
                @Override
                public String getSubject() {
                    return "Test Subject";
                }

                @Override
                public boolean hasAttachments() {
                    return true;
                }

                @Override
                public java.util.List<Attachment> getAttachments() {
                    // Test double: subclass the public Attachment and override only the accessors the
                    // converter reads. The library's PropertyContext/NodeDatabase internals are no longer
                    // part of the public API, so the attachment is faked at the public surface instead.
                    return java.util.List.of(new Attachment() {
                        @Override
                        public String getLongFilename() {
                            return "";
                        }

                        @Override
                        public String getFilename() {
                            return "test.txt";
                        }

                        @Override
                        public String getMimeTag() {
                            return "text/plain";
                        }

                        @Override
                        public byte[] getData() {
                            return new byte[] {1, 2, 3, 4, 5};
                        }

                        @Override
                        public int getAttachMethod() {
                            return 0;
                        }

                        @Override
                        public String getContentId() {
                            return null;
                        }

                        @Override
                        public boolean isInline() {
                            return false;
                        }
                    });
                }

                @Override
                public java.util.List<Message.Recipient> getRecipients() {
                    return java.util.List.of();
                }

                @Override
                public String getBody() {
                    return "Body text";
                }

                @Override
                public String getSenderName() {
                    return "Sender";
                }

                @Override
                public String getSenderEmail() {
                    return "sender@example.com";
                }

                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }
            };

            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var serializer = PstToEmlConverter.createSerializer(msg, options, pstFile, ConversionLog.NOOP);

            java.io.StringWriter writer = new java.io.StringWriter();
            serializer.writeTo(writer);
            String eml = writer.toString();

            org.junit.jupiter.api.Assertions.assertTrue(
                    eml.contains("Content-Disposition: attachment; filename=\"test.txt\""));

            var matcher = java.util.regex.Pattern.compile(
                            "(?i)(?s)Content-Transfer-Encoding: base64.*?\\r\\n\\r\\n(.*?)(?:\\r\\n--)")
                    .matcher(eml);
            org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), "Attachment should have base64 encoding");
            String b64 = matcher.group(1).replaceAll("\\s+", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(b64);
            org.junit.jupiter.api.Assertions.assertEquals(5, decoded.length, "Attachment decoded length should be 5");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new byte[] {1, 2, 3, 4, 5}, decoded, "Attachment decoded content should match");
        }
    }

    @Test
    void emptyRecipientTableFallsBackToDisplayBcc() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        try (var pstFile = new PstFile(path)) {
            var msg = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Bcc fallback";
                }

                @Override
                public String getBody() {
                    return "body";
                }

                @Override
                public java.util.List<Message.Recipient> getRecipients() {
                    return java.util.List.of();
                }

                @Override
                public String getTo() {
                    return "";
                }

                @Override
                public String getDisplayCc() {
                    return "";
                }

                @Override
                public String getDisplayBcc() {
                    return "Hidden Person";
                }

                @Override
                public String getSenderName() {
                    return "Sender";
                }

                @Override
                public String getSenderEmail() {
                    return "sender@example.com";
                }
            };

            // useOriginalHeaders=false forces the synthesized path so Bcc is actually emitted.
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    false,
                    false,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var serializer = PstToEmlConverter.createSerializer(msg, options, pstFile, ConversionLog.NOOP);

            var writer = new java.io.StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            org.junit.jupiter.api.Assertions.assertTrue(
                    eml.contains("Bcc:") && eml.contains("Hidden Person"),
                    "Bcc should fall back to the stored display string: " + eml);
        }
    }

    @Test
    void atomicWriteLeavesNoPartialFiles() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_atomic");
        try (var pstFile = new PstFile(path)) {
            var rootFolder = new Folder(pstFile, 0x122);
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    20,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var stats = new PstToEmlConverter.Stats();
            PstToEmlConverter.processFolder(
                    pstFile, rootFolder.getNid(), tempDir, options, stats, indicator, "", ConversionLog.NOOP);

            try (var stream = java.nio.file.Files.walk(tempDir)) {
                var leftover = stream.filter(entry -> entry.toString().endsWith(".part"))
                        .toList();
                org.junit.jupiter.api.Assertions.assertTrue(
                        leftover.isEmpty(), "Temp .part files must be moved into place or cleaned up: " + leftover);
            }
        } finally {
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    @Test
    void testCreateSerializerDepthLimit() throws Exception {
        var options = new PstToEmlConverter.Options(
                PstToEmlConverter.DuplicateHandling.OVERWRITE,
                null,
                false,
                true,
                Message.AddressPreference.PREFER_SMTP,
                false,
                false,
                64L * 1024 * 1024);
        var stub = PstToEmlConverter.createSerializer(null, options, null, 11, ConversionLog.NOOP);

        java.io.StringWriter writer = new java.io.StringWriter();
        stub.writeTo(writer);
        String eml = writer.toString();

        org.junit.jupiter.api.Assertions.assertTrue(
                eml.contains("Subject: Nested Message Limit Exceeded"), "Should emit stub for depth limit");
        org.junit.jupiter.api.Assertions.assertTrue(
                eml.contains("The maximum nested message depth was reached"), "Should emit stub body");
    }

    // #8 deep recovery: with nothing "known" or "visited", every message node is an orphan and is
    // written into the synthetic "Orphaned Items" folder.
    @Test
    void recoversOrphanMessagesIntoOrphanedItemsFolder() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_orphan");
        try (var pstFile = new PstFile(path)) {
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    true,
                    64L * 1024 * 1024);
            var stats = new PstToEmlConverter.Stats();
            PstToEmlConverter.recoverUnreferencedMessages(
                    pstFile,
                    tempDir,
                    options,
                    stats,
                    indicator,
                    ConversionLog.NOOP,
                    new java.util.HashSet<>(),
                    new java.util.HashSet<>(),
                    new java.util.HashMap<>());

            org.junit.jupiter.api.Assertions.assertTrue(
                    stats.recoveredOrphans() > 0, "Expected orphan recovery to write some messages");
            Path orphanDir = tempDir.resolve("Orphaned Items");
            assertNotNull(orphanDir);
            org.junit.jupiter.api.Assertions.assertTrue(
                    java.nio.file.Files.isDirectory(orphanDir), "Orphaned Items folder should exist");
            try (var stream = java.nio.file.Files.list(orphanDir)) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        stream.anyMatch(entry -> entry.toString().endsWith(".eml")),
                        "Orphaned Items should contain recovered EMLs");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // #7 dumpster: when every folder counts as "visited", unreferenced messages parented to a real
    // folder are soft-deleted recoveries and land in the synthetic "Recovered Items" folder.
    @Test
    void recoversSoftDeletedMessagesIntoRecoveredItemsFolder() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_dumpster");
        try (var pstFile = new PstFile(path)) {
            var visitedFolders = new java.util.HashSet<Integer>();
            for (var entry : pstFile.allNodes().values()) {
                if ((entry.nodeId() & 0x1F) == 0x02) { // NID_TYPE_NORMAL_FOLDER
                    visitedFolders.add(entry.nodeId());
                }
            }
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    true,
                    false,
                    64L * 1024 * 1024);
            var stats = new PstToEmlConverter.Stats();
            PstToEmlConverter.recoverUnreferencedMessages(
                    pstFile,
                    tempDir,
                    options,
                    stats,
                    indicator,
                    ConversionLog.NOOP,
                    visitedFolders,
                    new java.util.HashSet<>(),
                    new java.util.HashMap<>());

            org.junit.jupiter.api.Assertions.assertTrue(
                    stats.recoveredDeleted() > 0, "Expected soft-deleted recovery to write some messages");
            org.junit.jupiter.api.Assertions.assertTrue(
                    java.nio.file.Files.isDirectory(tempDir.resolve("Recovered Items")),
                    "Recovered Items folder should exist");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // A full convert() with recovery enabled must never export the same message twice (the walk and
    // the recovery pass agree via the shared knownMessages set).
    @Test
    void convertWithRecoveryDoesNotDuplicateWalkedMessages() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_recovery_convert");
        try (var pstFile = new PstFile(path)) {
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    true,
                    true,
                    64L * 1024 * 1024);
            var stats = PstToEmlConverter.convert(pstFile, tempDir, options, indicator, ConversionLog.NOOP);
            org.junit.jupiter.api.Assertions.assertTrue(stats.converted() > 0, "convert should export messages");

            var nids = new java.util.ArrayList<String>();
            var pattern = java.util.regex.Pattern.compile("_(\\d+)\\.eml$");
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                stream.filter(entry -> entry.toString().endsWith(".eml")).forEach(entry -> {
                    var matcher = pattern.matcher(entry.getFileName().toString());
                    if (matcher.find()) {
                        nids.add(matcher.group(1));
                    }
                });
            }
            org.junit.jupiter.api.Assertions.assertEquals(
                    new java.util.HashSet<>(nids).size(), nids.size(), "no message may be exported twice: " + nids);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // F17 regression: convert() used to wrap everything in a synthetic "Folder_290" directory
    // (the unnamed root folder, NID 0x122); the root's children now land in the target directory.
    @Test
    void convertDoesNotCreateSyntheticRootWrapper() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_no_wrapper");
        try (var pstFile = new PstFile(path)) {
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    20,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var stats = PstToEmlConverter.convert(pstFile, tempDir, options, indicator, ConversionLog.NOOP);
            org.junit.jupiter.api.Assertions.assertTrue(stats.converted() > 0, "convert should export messages");

            java.util.List<String> topLevel;
            try (var stream = java.nio.file.Files.list(tempDir)) {
                topLevel = stream.map(entry -> entry.getFileName().toString()).toList();
            }
            org.junit.jupiter.api.Assertions.assertFalse(
                    topLevel.stream().anyMatch(name -> name.matches("Folder_\\d+")),
                    "synthetic root wrapper must be gone: " + topLevel);
            // The root's real children (tika-testPST's is the French "Début du fichier de données
            // Outlook") sit directly at the top level.
            org.junit.jupiter.api.Assertions.assertFalse(
                    topLevel.isEmpty(), "the root's real children belong at the top level");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // F2 regression: exported appointments used to carry METHOD:REQUEST with no ATTENDEE — invalid
    // iTIP that strict clients refuse to render. A plain appointment must be PUBLISHed, with the
    // Content-Type method parameter matching the document.
    @Test
    void appointmentInviteIsPublishedNotRequested() throws Exception {
        Path path = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        Path tempDir = java.nio.file.Files.createTempDirectory("pst_publish");
        try (var pstFile = new PstFile(path)) {
            var indicator = new ProgressIndicatorBase();
            var options = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    50,
                    false,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            PstToEmlConverter.convert(pstFile, tempDir, options, indicator, ConversionLog.NOOP);

            String inviteEml = null;
            try (var stream = java.nio.file.Files.walk(tempDir)) {
                for (Path eml : stream.filter(entry -> entry.toString().endsWith(".eml"))
                        .toList()) {
                    String content = java.nio.file.Files.readString(eml);
                    if (content.contains("invite.ics")) {
                        inviteEml = content;
                        break;
                    }
                }
            }
            assertNotNull(inviteEml, "expected an exported appointment with an invite");
            org.junit.jupiter.api.Assertions.assertTrue(
                    inviteEml.contains("text/calendar; charset=UTF-8; method=PUBLISH"), inviteEml);

            var matcher = java.util.regex.Pattern.compile("(?is)text/calendar.*?\\r\\n\\r\\n(.*?)\\r\\n--")
                    .matcher(inviteEml);
            org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), inviteEml);
            String ics = new String(
                    java.util.Base64.getMimeDecoder().decode(matcher.group(1)),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(ics.contains("METHOD:PUBLISH"), ics);
            org.junit.jupiter.api.Assertions.assertTrue(ics.contains("DTSTART:"), ics);
            for (var line : ics.split("\r\n")) {
                org.junit.jupiter.api.Assertions.assertFalse(
                        line.startsWith("ATTENDEE"), "PUBLISH must not carry attendees: " + ics);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
