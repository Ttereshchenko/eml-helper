package com.github.ttereshchenko.mailkit.conversion.pst;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.Folder;
import com.github.ttereshchenko.mailkit.pst.MapiProperties;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.NodeEntry;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure (UI-free) orchestration that walks the IPM subtree of an open {@link PstFile}
 * and writes one {@code .eml} per eligible message under a target directory.
 *
 * <p>Extracted from {@code ConvertPstToEmlAction} so the conversion logic is testable
 * without reflection and decoupled from the IntelliJ action/UI. All progress and error
 * reporting goes through {@link ConversionLog} (never {@code null}); tests pass
 * {@link ConversionLog#NOOP}.
 */
public final class PstToEmlConverter {

    private static final Logger LOG = Logger.getInstance(PstToEmlConverter.class);

    private static final int NID_ROOT_FOLDER = 0x122;
    private static final int MAX_EMBEDDED_DEPTH = 10;
    // A PST folder hierarchy is a tree on disk with no depth bound ([MS-PST] §2.4.4); a hostile archive
    // can declare a deep linear acyclic chain whose distinct NIDs never trip the cycle guard, so cap the
    // recursion depth to keep the folder walk from overflowing the stack (an Error escapes catch).
    private static final int MAX_FOLDER_DEPTH = 256;
    private static final int MAX_SUBJECT_LENGTH = 100;
    // Keep the full output path within Windows' 260-char MAX_PATH (with headroom for the long-path API).
    private static final int MAX_PATH_LENGTH = 255;
    private static final int ATTACH_EMBEDDED_MSG = 5; // PR_ATTACH_METHOD == afEmbeddedMessage
    // maxNodeSize bounds a single attachment, but a crafted message can declare many of them and the
    // serializer holds every part in memory before writing — the aggregate can OutOfMemoryError, which
    // (like the folder-depth Error) escapes catch(Exception). Cap the per-message total bytes and count.
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ATTACHMENT_COUNT = 1000;

    private static final List<String> ALLOWED_MESSAGE_CLASSES =
            List.of("IPM.Note", "IPM.Post", "REPORT.", "IPM.Schedule.Meeting.", "IPM.Appointment");

    // Low 5 bits of a NID encode its type; a normal message node is type 0x04.
    private static final int NID_TYPE_MASK = 0x1F;
    private static final int NID_TYPE_NORMAL_MESSAGE = 0x04;
    // Synthetic folders that hold messages recovered outside the normal folder walk.
    private static final String RECOVERED_FOLDER_NAME = "Recovered Items";
    private static final String ORPHANED_FOLDER_NAME = "Orphaned Items";
    // Reserved DOS device names that cannot be used as a Windows path segment even with an extension.
    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
            "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** How to handle a target {@code .eml} path that already exists. */
    public enum DuplicateHandling {
        OVERWRITE("Overwrite"),
        SKIP("Skip"),
        SUFFIX_COUNTER("Suffix-counter");

        private final String label;

        DuplicateHandling(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Immutable conversion options gathered from the dialog. */
    public record Options(
            DuplicateHandling duplicateHandling,
            Integer limit,
            boolean useOriginalHeaders,
            boolean skipEmptyFolders,
            Message.AddressPreference addressPreference,
            boolean recoverDeletedItems,
            boolean scanOrphans,
            long maxNodeSize) {}

    /** Mutable running totals for a single conversion. */
    public static final class Stats {
        private int converted;
        private int failedMessages;
        private int failedFolders;
        private int recoveredDeleted;
        private int recoveredOrphans;

        public int converted() {
            return converted;
        }

        public int failedMessages() {
            return failedMessages;
        }

        public int failedFolders() {
            return failedFolders;
        }

        public int recoveredDeleted() {
            return recoveredDeleted;
        }

        public int recoveredOrphans() {
            return recoveredOrphans;
        }
    }

    private PstToEmlConverter() {}

    public static Stats convert(
            PstFile pstFile, Path targetDir, Options options, ProgressIndicator indicator, ConversionLog log)
            throws IOException {
        var stats = new Stats();
        var rootFolderNode = pstFile.getNode(NID_ROOT_FOLDER);
        if (rootFolderNode == null) {
            return stats;
        }
        Files.createDirectories(targetDir);

        // Shared across the walk so the recovery pass below can tell which folders were visited and
        // which messages were already accounted for (referenced by some folder's contents table).
        var visited = new HashSet<Integer>();
        var knownMessages = new HashSet<Integer>();
        var nameCounters = new HashMap<Path, Integer>();
        processFolder(
                pstFile,
                rootFolderNode.nodeId(),
                targetDir,
                options,
                stats,
                indicator,
                "Root",
                log,
                visited,
                knownMessages,
                nameCounters,
                0);

        if (options.recoverDeletedItems() || options.scanOrphans()) {
            recoverUnreferencedMessages(
                    pstFile, targetDir, options, stats, indicator, log, visited, knownMessages, nameCounters);
        }
        return stats;
    }

    /** A message node found in the NBT that the folder walk did not export. */
    record RecoveryCandidate(int nid, boolean fromVisitedFolder) {}

    /**
     * Scans every node in the NBT for message nodes (NID type {@code 0x04}) that no visited folder's
     * contents table referenced. {@code fromVisitedFolder} is true when the node's parent is a folder
     * the walk visited — i.e. it was soft-deleted from a real folder (Dumpster, #7) — and false when
     * the parent is outside the folder tree entirely — i.e. a detached orphan (#8).
     */
    static List<RecoveryCandidate> findUnreferencedMessages(
            Map<Integer, NodeEntry> allNodes, Set<Integer> knownMessages, Set<Integer> visitedFolders) {
        var candidates = new ArrayList<RecoveryCandidate>();
        for (var entry : allNodes.values()) {
            var nid = entry.nodeId();
            if ((nid & NID_TYPE_MASK) != NID_TYPE_NORMAL_MESSAGE) {
                continue;
            }
            if (knownMessages.contains(nid)) {
                continue;
            }
            candidates.add(new RecoveryCandidate(nid, visitedFolders.contains(entry.parentNodeId())));
        }
        return candidates;
    }

    /**
     * Recovers message nodes the folder walk missed: soft-deleted items still parented to a visited
     * folder go into {@value #RECOVERED_FOLDER_NAME} (#7); fully detached nodes go into
     * {@value #ORPHANED_FOLDER_NAME} (#8). Each is filtered by {@link #isAllowedMessageClass} just like
     * the walk, so folder-associated config items and appointments are not resurrected.
     */
    static void recoverUnreferencedMessages(
            PstFile pstFile,
            Path targetDir,
            Options options,
            Stats stats,
            ProgressIndicator indicator,
            ConversionLog log,
            Set<Integer> visitedFolders,
            Set<Integer> knownMessages,
            Map<Path, Integer> nameCounters)
            throws IOException {
        var candidates = findUnreferencedMessages(pstFile.allNodes(), knownMessages, visitedFolders);
        Path dumpsterDir = null;
        Path orphanDir = null;
        for (var candidate : candidates) {
            if (options.limit() != null && stats.converted >= options.limit()) {
                break;
            }
            indicator.checkCanceled();

            var dumpster = candidate.fromVisitedFolder();
            if (dumpster && !options.recoverDeletedItems()) {
                continue;
            }
            if (!dumpster && !options.scanOrphans()) {
                continue;
            }

            var nid = candidate.nid();
            try {
                var message = new Message(pstFile, nid);
                message.setAddressPreference(options.addressPreference());
                if (!isAllowedMessageClass(message.getMessageClass())) {
                    continue;
                }

                Path recoveryDir;
                if (dumpster) {
                    if (dumpsterDir == null) {
                        dumpsterDir = uniqueDirectory(targetDir, RECOVERED_FOLDER_NAME, nameCounters);
                        Files.createDirectories(dumpsterDir);
                    }
                    recoveryDir = dumpsterDir;
                } else {
                    if (orphanDir == null) {
                        orphanDir = uniqueDirectory(targetDir, ORPHANED_FOLDER_NAME, nameCounters);
                        Files.createDirectories(orphanDir);
                    }
                    recoveryDir = orphanDir;
                }

                var subject = message.getSubject();
                if (subject == null || subject.isBlank()) {
                    subject = "No Subject";
                }
                var safeSubject = truncateSubject(subject).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
                var fileName = boundedEmlFileName(recoveryDir, safeSubject, nid);
                var emlFile = recoveryDir.resolve(fileName);
                if (!emlFile.normalize().startsWith(recoveryDir.normalize())) {
                    emlFile = recoveryDir.resolve("message_" + nid + ".eml");
                }
                if (Files.exists(emlFile)) {
                    // Mirror processFolder: SKIP leaves the existing file alone, SUFFIX_COUNTER finds the
                    // next free "_N" name so a second recovered message never clobbers the first, and
                    // OVERWRITE falls through to the atomic REPLACE_EXISTING write below.
                    if (options.duplicateHandling() == DuplicateHandling.SKIP) {
                        continue;
                    } else if (options.duplicateHandling() == DuplicateHandling.SUFFIX_COUNTER) {
                        int suffixCount = 1;
                        while (Files.exists(recoveryDir.resolve(
                                boundedEmlFileName(recoveryDir, safeSubject, nid + "_" + suffixCount)))) {
                            suffixCount++;
                        }
                        emlFile = recoveryDir.resolve(
                                boundedEmlFileName(recoveryDir, safeSubject, nid + "_" + suffixCount));
                    }
                }

                var serializer = createSerializer(message, options, pstFile, log);
                writeSerializerAtomically(serializer, emlFile);
                stats.converted++;
                if (dumpster) {
                    stats.recoveredDeleted++;
                } else {
                    stats.recoveredOrphans++;
                }
                knownMessages.add(nid);
            } catch (Exception exception) {
                stats.failedMessages++;
                log.error("Failed to recover message " + nid + ": " + describeFailure(exception));
            }
        }
        if (stats.recoveredDeleted > 0 || stats.recoveredOrphans > 0) {
            log.info("Recovered " + stats.recoveredDeleted + " deleted and " + stats.recoveredOrphans
                    + " orphaned message(s)");
        }
    }

    static void processFolder(
            PstFile pstFile,
            int folderNid,
            Path targetDir,
            Options options,
            Stats stats,
            ProgressIndicator indicator,
            String currentPath,
            ConversionLog log) {
        processFolder(
                pstFile,
                folderNid,
                targetDir,
                options,
                stats,
                indicator,
                currentPath,
                log,
                new HashSet<>(),
                new HashSet<>(),
                new HashMap<>(),
                0);
    }

    private static void processFolder(
            PstFile pstFile,
            int folderNid,
            Path targetDir,
            Options options,
            Stats stats,
            ProgressIndicator indicator,
            String currentPath,
            ConversionLog log,
            Set<Integer> visited,
            Set<Integer> knownMessages,
            Map<Path, Integer> nameCounters,
            int depth) {
        if (options.limit() != null && stats.converted >= options.limit()) {
            return;
        }
        if (depth > MAX_FOLDER_DEPTH) {
            // Linear acyclic folder chains slip past the cycle guard below; without this cap a deep
            // enough chain overflows the stack with a StackOverflowError that no catch(Exception) sees.
            log.error("Folder nesting exceeded depth " + MAX_FOLDER_DEPTH + " at " + currentPath
                    + "; skipping deeper folders");
            stats.failedFolders++;
            return;
        }
        if (!visited.add(folderNid)) {
            // A well-formed PST folder hierarchy is a tree, but a corrupt or hostile archive can
            // reference a folder as its own ancestor; without this guard that recurses forever into a
            // StackOverflowError.
            log.info("Skipping already-visited folder " + folderNid + " (cycle guard)");
            return;
        }

        Folder folder;
        String folderName;
        try {
            folder = new Folder(pstFile, folderNid);
            folderName = folder.getDisplayName();
            if (folderName == null || folderName.isBlank() || folderName.startsWith("Unknown")) {
                folderName = "Folder_" + folderNid;
            }
            log.info("Processing folder: " + currentPath + "/" + folderName);
        } catch (Exception exception) {
            log.error("Failed to read folder " + folderNid + ": " + describeFailure(exception));
            stats.failedFolders++;
            // folderNid was already added to `visited` by the cycle guard above, so any of this folder's
            // messages the recovery pass later finds are classified as deleted-from-a-known-folder
            // (Recovered Items), not detached orphans — we know the folder existed, only that it was unreadable.
            return;
        }

        // Sanitize folder name, cap its length, and guard against path traversal (a PST is untrusted input).
        folderName = boundedSegment(safeSegment(folderName, folderNid));
        Path folderDir = targetDir.resolve(folderName);
        if (!folderDir.normalize().startsWith(targetDir.normalize())) {
            folderName = "Folder_" + folderNid;
            folderDir = targetDir.resolve(folderName);
        }
        // Resolve name collisions without re-probing from "_2" each time: a cached per-base counter
        // resumes where the previous duplicate left off, so K identically named siblings cost O(K).
        folderDir = uniqueDirectory(targetDir, folderName, nameCounters);
        folderName = folderDir.getFileName().toString();
        try {
            Files.createDirectories(folderDir);
        } catch (Exception exception) {
            log.error("Failed to create folder directory for " + folderName + ": " + describeFailure(exception));
            stats.failedFolders++;
            return;
        }

        try {
            List<Integer> messages = List.of();
            try {
                messages = folder.getMessages();
                // Every message referenced by this folder's contents table is "known" — even if it is
                // later skipped (disallowed class, limit, duplicate) — so the recovery pass does not
                // mistake it for a deleted/orphaned node.
                knownMessages.addAll(messages);
            } catch (Exception exception) {
                log.error("Failed to list messages in " + currentPath + "/" + folderName + ": "
                        + describeFailure(exception));
                stats.failedFolders++;
            }
            for (int msgNid : messages) {
                if (options.limit() != null && stats.converted >= options.limit()) {
                    break;
                }

                indicator.checkCanceled();
                indicator.setText(
                        "Converted " + stats.converted + (options.limit() != null ? " / " + options.limit() : "")
                                + " messages — current folder: " + currentPath + "/" + folderName);

                try {
                    var message = new Message(pstFile, msgNid);
                    message.setAddressPreference(options.addressPreference());
                    String messageClass = message.getMessageClass();
                    if (!isAllowedMessageClass(messageClass)) {
                        log.info("Skipping message " + msgNid + " (Class: " + messageClass + " is not allowed)");
                        continue;
                    }

                    String subject = message.getSubject();
                    if (subject == null || subject.isBlank()) {
                        subject = "No Subject";
                    }
                    subject = truncateSubject(subject);
                    String safeSubject = subject.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
                    String fileName = boundedEmlFileName(folderDir, safeSubject, msgNid);
                    Path emlFile = folderDir.resolve(fileName);
                    if (!emlFile.normalize().startsWith(folderDir.normalize())) {
                        emlFile = folderDir.resolve("message_" + msgNid + ".eml");
                    }

                    if (Files.exists(emlFile)) {
                        if (options.duplicateHandling() == DuplicateHandling.SKIP) {
                            log.info("Skipping duplicate message: " + fileName);
                            continue;
                        } else if (options.duplicateHandling() == DuplicateHandling.SUFFIX_COUNTER) {
                            int suffixCount = 1;
                            while (Files.exists(folderDir.resolve(
                                    boundedEmlFileName(folderDir, safeSubject, msgNid + "_" + suffixCount)))) {
                                suffixCount++;
                            }
                            emlFile = folderDir.resolve(
                                    boundedEmlFileName(folderDir, safeSubject, msgNid + "_" + suffixCount));
                        }
                    }

                    var serializer = createSerializer(message, options, pstFile, log);
                    writeSerializerAtomically(serializer, emlFile);
                    stats.converted++;
                } catch (Exception exception) {
                    stats.failedMessages++;
                    // Track failures separately; do not pop notifications per failure to avoid spam.
                    log.error("Failed to convert message " + msgNid + " in " + currentPath + "/" + folderName + ": "
                            + describeFailure(exception));
                }
            }

            List<Folder> subFolders = List.of();
            try {
                subFolders = folder.getSubFolders();
            } catch (Exception exception) {
                log.error("Failed to list subfolders of " + currentPath + "/" + folderName + ": "
                        + describeFailure(exception));
                stats.failedFolders++;
            }

            for (Folder subFolder : subFolders) {
                indicator.checkCanceled();
                if (options.limit() != null && stats.converted >= options.limit()) {
                    break;
                }
                processFolder(
                        pstFile,
                        subFolder.getNid(),
                        folderDir,
                        options,
                        stats,
                        indicator,
                        currentPath + "/" + folderName,
                        log,
                        visited,
                        knownMessages,
                        nameCounters,
                        depth + 1);
            }
        } finally {
            if (options.skipEmptyFolders()) {
                try (var stream = Files.list(folderDir)) {
                    if (stream.findAny().isEmpty()) {
                        Files.delete(folderDir);
                    }
                } catch (IOException exception) {
                    LOG.debug("Could not remove empty folder " + folderDir, exception);
                }
            }
        }
    }

    static EmlSerializer createSerializer(Message message, Options options, PstFile pstFile, ConversionLog log) {
        return createSerializer(message, options, pstFile, 0, log);
    }

    static EmlSerializer createSerializer(
            Message message, Options options, PstFile pstFile, int depth, ConversionLog log) {
        if (depth > MAX_EMBEDDED_DEPTH) {
            log.info("Maximum nested message depth reached. Truncating message.");
            var stub = new EmlSerializer();
            stub.setSubject("Nested Message Limit Exceeded");
            stub.addBody("The maximum nested message depth was reached.", "text/plain; charset=UTF-8");
            return stub;
        }
        var serializer = new EmlSerializer();

        if (options.useOriginalHeaders()) {
            String headers = message.getTransportHeaders();
            if (headers != null && !headers.isBlank()) {
                serializer.setTransportHeaders(headers);
            }
        }

        String subject = message.getSubject();
        if (subject == null || subject.isBlank()) {
            subject = "No Subject";
        }
        serializer.setSubject(subject);

        var date = message.getMessageDate();
        if (date != null) {
            serializer.setDate(Date.from(date));
        }

        String messageId = message.getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            serializer.setMessageId(messageId);
        }

        Object sclObj = message.getProperty(MapiProperties.PR_CONTENT_FILTER_SPAM_CONFIDENCE_LEVEL);
        if (sclObj instanceof Number n) {
            serializer.setScl(n.intValue());
        }

        serializer.setSender(message.getSenderName(), message.getSenderEmail());

        var recipients = message.getRecipients();
        for (Message.Recipient recipient : recipients) {
            serializer.addRecipient(recipient.type, recipient.name, recipient.email);
        }
        if (recipients.isEmpty()) {
            // The recipient table was empty or unreadable; fall back to the display strings
            // so the To:/Cc:/Bcc: headers are not silently lost.
            addDisplayFallback(serializer, message.getTo(), EmlSerializer.RECIPIENT_TYPE_TO);
            addDisplayFallback(serializer, message.getDisplayCc(), EmlSerializer.RECIPIENT_TYPE_CC);
            addDisplayFallback(serializer, message.getDisplayBcc(), EmlSerializer.RECIPIENT_TYPE_BCC);
        }

        serializer.addBody(message.getBody(), "text/plain; charset=UTF-8");
        serializer.addBody(message.getHtmlBody(), "text/html; charset=UTF-8");
        serializer.addBody(message.getRtfBody(), "text/rtf; charset=UTF-8");

        String msgClass = message.getMessageClass();
        // Emit a calendar invite for both calendar items (IPM.Appointment) and meeting requests
        // (IPM.Schedule.Meeting.*); both store the start/end/location named properties below.
        if (msgClass != null
                && (msgClass.startsWith("IPM.Appointment") || msgClass.startsWith("IPM.Schedule.Meeting"))) {
            java.util.UUID psetidAppointment = java.util.UUID.fromString("00062002-0000-0000-C000-000000000046");
            Integer startId = pstFile.namedPropertyId(psetidAppointment, 0x820D);
            Integer endId = pstFile.namedPropertyId(psetidAppointment, 0x820E);
            Integer locId = pstFile.namedPropertyId(psetidAppointment, 0x8208);

            Instant start = startId != null && message.getProperty(startId) instanceof Instant instant ? instant : null;
            Instant end = endId != null && message.getProperty(endId) instanceof Instant instant ? instant : null;
            String location = locId != null ? message.getStringProperty(locId) : null;

            String ical = com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator.generate(
                    start != null ? Date.from(start) : null,
                    end != null ? Date.from(end) : null,
                    location,
                    subject,
                    message.getSenderName(),
                    message.getSenderEmail(),
                    message.getBody());
            serializer.addAttachment(
                    "invite.ics",
                    "text/calendar; method=REQUEST",
                    ical.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    null,
                    false);
        }

        long totalAttachmentBytes = 0;
        int attachmentCount = 0;
        for (Attachment attachment : message.getAttachments()) {
            if (attachmentCount >= MAX_ATTACHMENT_COUNT) {
                log.error("Message has more than " + MAX_ATTACHMENT_COUNT
                        + " attachments; remaining attachments were skipped");
                break;
            }
            attachmentCount++;

            String attachName = attachment.getLongFilename();
            if (attachName.isEmpty()) attachName = attachment.getFilename();
            if (attachName.isEmpty()) attachName = "attachment.dat";

            if (attachment.getAttachMethod() == ATTACH_EMBEDDED_MSG) {
                Integer embedNid = attachment.getEmbeddedMessageNodeId();
                if (embedNid != null) {
                    log.info("Found embedded message attachment: " + attachName);
                    try {
                        var attachNode = attachment.getNode();
                        if (attachNode != null) {
                            NodeEntry embedEntry = pstFile.readSubnodeEntry(attachNode.subBid(), embedNid);
                            if (embedEntry != null) {
                                var embedMessage = new Message(pstFile, embedEntry);
                                embedMessage.setAddressPreference(options.addressPreference());
                                var embedSerializer = createSerializer(embedMessage, options, pstFile, depth + 1, log);
                                var stringWriter = new StringWriter();
                                embedSerializer.writeTo(stringWriter);
                                if (!attachName.toLowerCase().endsWith(".eml")) attachName += ".eml";
                                serializer.addEmbeddedMessage(attachName, stringWriter.toString());
                            }
                        }
                    } catch (Exception exception) {
                        log.error("Failed to extract embedded message '" + attachName + "': "
                                + describeFailure(exception));
                    }
                }
                continue;
            }

            byte[] data = attachment.getData();
            if (data == null) continue;

            totalAttachmentBytes += data.length;
            if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                log.error("Message attachments exceed " + (MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024))
                        + " MB in aggregate; remaining attachments were skipped");
                break;
            }

            String mime = attachment.getMimeTag();
            if (mime.isEmpty()) mime = "application/octet-stream";

            log.info("Found attachment: " + attachName + " (" + mime + ")");
            serializer.addAttachment(attachName, mime, data, attachment.getContentId(), attachment.isInline());
        }

        return serializer;
    }

    private static void addDisplayFallback(EmlSerializer serializer, String displayList, int recipientType) {
        if (displayList == null || displayList.isBlank()) {
            return;
        }
        // PR_DISPLAY_TO/CC/BCC is MAPI's own semicolon-delimited display string (not an RFC 5322
        // address list), so split on ";" with surrounding whitespace absorbed. A literal ";" inside a
        // display name is inherently ambiguous here and rare; the structured recipient table above is
        // the accurate source when available.
        for (String name : displayList.split("\\s*;\\s*")) {
            var trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                serializer.addRecipient(recipientType, trimmed, "");
            }
        }
    }

    /**
     * Writes the EML to a sibling {@code .part} file and atomically renames it into place, so a
     * mid-conversion failure never leaves a truncated {@code .eml} behind on the user's filesystem.
     */
    private static void writeSerializerAtomically(EmlSerializer serializer, Path emlFile) throws IOException {
        var tempFile = emlFile.resolveSibling(emlFile.getFileName() + ".part");
        try {
            try (var writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                serializer.writeTo(writer);
            }
            try {
                Files.move(tempFile, emlFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Some filesystems (e.g. certain network mounts) cannot rename atomically; fall back to a
                // best-effort replace so conversion still completes.
                Files.move(tempFile, emlFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static String safeSegment(String name, int nid) {
        // Map forbidden filename characters and control characters / newlines to '_', matching the
        // subject sanitizer (a newline left in a folder name would otherwise persist into the directory).
        var sanitized = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
        // Windows silently trims trailing dots and spaces from a segment; do it ourselves so the on-disk
        // name matches what we computed (and so a name of only dots/spaces collapses to the fallback).
        sanitized = sanitized.replaceAll("[ .]+$", "");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            return "Folder_" + nid;
        }
        // Reserved DOS device names (with or without an extension) cannot be created on Windows; prefix
        // the NID to defang while keeping the original name visible.
        var baseName = sanitized;
        var dotIndex = baseName.indexOf('.');
        if (dotIndex >= 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        if (RESERVED_WINDOWS_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            return "Folder_" + nid + "_" + sanitized;
        }
        return sanitized;
    }

    /**
     * Whether a message with the given {@code PidTagMessageClass} should be exported. Intentionally
     * permits a {@code null} or empty class: [MS-OXCMSG] §2.2.1.3 defines a missing message class as
     * the generic {@code IPM} note, so a malformed item with no class is treated as a plain email
     * (best-effort fidelity) rather than silently dropped. Everything else must match an allowed prefix.
     */
    private static boolean isAllowedMessageClass(String messageClass) {
        if (messageClass == null || messageClass.isEmpty()) {
            return true;
        }
        for (String allowed : ALLOWED_MESSAGE_CLASSES) {
            if (messageClass.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String truncateSubject(String subject) {
        if (subject.length() <= MAX_SUBJECT_LENGTH) {
            return subject;
        }
        int end = MAX_SUBJECT_LENGTH;
        if (Character.isHighSurrogate(subject.charAt(end - 1))) {
            end--; // do not split a surrogate pair
        }
        return subject.substring(0, end);
    }

    /** Caps a single path segment length so one very long folder name cannot blow the path budget. */
    private static String boundedSegment(String segment) {
        if (segment.length() <= MAX_SUBJECT_LENGTH) {
            return segment;
        }
        return segment.substring(0, surrogateSafeEnd(segment, MAX_SUBJECT_LENGTH));
    }

    static String boundedEmlFileName(Path folderDir, String safeSubject, int messageNid) {
        return boundedEmlFileName(folderDir, safeSubject, String.valueOf(messageNid));
    }

    static String boundedEmlFileName(Path folderDir, String safeSubject, String suffixPart) {
        var suffix = "_" + suffixPart + ".eml";
        var budget = MAX_PATH_LENGTH - (folderDir.toString().length() + 1) - suffix.length() - 5;
        if (budget <= 0) {
            // Even "_<suffix>.eml" overflows the budget; keep just the unique suffix-based name.
            return suffixPart + ".eml";
        }
        var subject = safeSubject.length() > budget
                ? safeSubject.substring(0, surrogateSafeEnd(safeSubject, budget))
                : safeSubject;
        return subject + suffix;
    }

    /** Trims {@code text} to at most {@code end} chars without splitting a surrogate pair. */
    private static int surrogateSafeEnd(String text, int end) {
        var bounded = Math.min(end, text.length());
        if (bounded > 0 && Character.isHighSurrogate(text.charAt(bounded - 1))) {
            return bounded - 1;
        }
        return bounded;
    }

    /**
     * Returns a non-colliding directory under {@code parent} for {@code baseName}, appending
     * {@code _2}, {@code _3}, … only as needed. A per-base counter in {@code counters} caches the
     * last suffix used so repeated collisions resume from there instead of re-probing from {@code _2},
     * turning K identically named siblings from O(K^2) filesystem stats into O(K).
     */
    static Path uniqueDirectory(Path parent, String baseName, Map<Path, Integer> counters) {
        var base = parent.resolve(baseName);
        if (!counters.containsKey(base) && !Files.exists(base)) {
            counters.put(base, 1);
            return base;
        }
        var next = counters.getOrDefault(base, 1);
        Path candidate;
        do {
            next++;
            candidate = parent.resolve(baseName + "_" + next);
        } while (counters.containsKey(candidate) || Files.exists(candidate));
        counters.put(base, next);
        counters.put(candidate, 1);
        return candidate;
    }

    static String describeFailure(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
