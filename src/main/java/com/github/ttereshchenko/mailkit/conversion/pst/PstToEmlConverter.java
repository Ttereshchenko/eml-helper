package com.github.ttereshchenko.mailkit.conversion.pst;

import com.github.ttereshchenko.mailkit.conversion.AppointmentRecurrence;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator;
import com.github.ttereshchenko.mailkit.conversion.RtfStripper;
import com.github.ttereshchenko.mailkit.conversion.VCardGenerator;
import com.github.ttereshchenko.mailkit.conversion.WindowsTimeZone;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

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
    // PR_ATTACH_METHOD values whose content lives outside the store ([MS-OXCMSG] §2.2.2.9).
    private static final int ATTACH_BY_REFERENCE = 2; // afByReference
    private static final int ATTACH_BY_REFERENCE_ONLY = 4; // afByRefOnly (3 = afByReferenceResolve)
    // maxNodeSize bounds a single attachment, but a crafted message can declare many of them and the
    // serializer holds every part in memory before writing — the aggregate can OutOfMemoryError, which
    // (like the folder-depth Error) escapes catch(Exception). Cap the per-message total bytes and count.
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ATTACHMENT_COUNT = 1000;

    private static final List<String> ALLOWED_MESSAGE_CLASSES =
            List.of("IPM.Note", "IPM.Post", "REPORT.", "IPM.Schedule.Meeting.", "IPM.Appointment");

    // Non-mail item classes exported only when Options.exportNonMailItems is on: contacts become
    // EMLs with a vCard, tasks carry a VTODO, sticky notes and journal entries export their text,
    // and distribution lists list their members.
    private static final List<String> NON_MAIL_MESSAGE_CLASSES =
            List.of("IPM.Contact", "IPM.Task", "IPM.StickyNote", "IPM.DistList", "IPM.Activity");

    // Named-property sets ([MS-OXPROPS] §1.3.2) for appointment, task and contact properties.
    private static final UUID PSETID_APPOINTMENT = UUID.fromString("00062002-0000-0000-C000-000000000046");
    private static final UUID PSETID_TASK = UUID.fromString("00062003-0000-0000-C000-000000000046");
    private static final UUID PSETID_ADDRESS = UUID.fromString("00062004-0000-0000-C000-000000000046");

    private static final int ATTACH_OLE = 6; // afStorage: an embedded OLE object

    // The LzFu decoder reads RTF as windows-1252, which round-trips all 256 byte values in Java;
    // encoding the decoded string back with it recovers the original RTF bytes.
    private static final Charset RTF_CHARSET = Charset.forName("windows-1252");

    // Low 5 bits of a NID encode its type; a normal message node is type 0x04.
    private static final int NID_TYPE_MASK = 0x1F;
    private static final int NID_TYPE_NORMAL_MESSAGE = 0x04;
    // Matches the Exchange journal-report marker header at the start of a transport-header line;
    // its value is almost always empty, so only the same-line remainder is captured (the marker is
    // not folded in practice).
    private static final Pattern JOURNAL_REPORT_HEADER =
            Pattern.compile("(?im)^X-MS-Journal-Report[ \\t]*:[ \\t]*(.*)$");

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
            long maxNodeSize,
            boolean exportNonMailItems,
            boolean verifyCrc) {

        /** Mail-only options with CRC verification off — the defaults before those switches existed. */
        public Options(
                DuplicateHandling duplicateHandling,
                Integer limit,
                boolean useOriginalHeaders,
                boolean skipEmptyFolders,
                Message.AddressPreference addressPreference,
                boolean recoverDeletedItems,
                boolean scanOrphans,
                long maxNodeSize) {
            this(
                    duplicateHandling,
                    limit,
                    useOriginalHeaders,
                    skipEmptyFolders,
                    addressPreference,
                    recoverDeletedItems,
                    scanOrphans,
                    maxNodeSize,
                    false,
                    false);
        }
    }

    /** Mutable running totals for a single conversion. */
    public static final class Stats {
        private int converted;
        private int failedMessages;
        private int failedFolders;
        private int failedAttachments;
        private int recoveredDeleted;
        private int recoveredOrphans;
        private final Map<String, Integer> skippedByClass = new TreeMap<>();

        public int converted() {
            return converted;
        }

        public int failedMessages() {
            return failedMessages;
        }

        public int failedAttachments() {
            return failedAttachments;
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

        /** How many items each disallowed message class kept out of the export, keyed by class. */
        public Map<String, Integer> skippedByClass() {
            return Collections.unmodifiableMap(skippedByClass);
        }

        void recordSkipped(String messageClass) {
            skippedByClass.merge(messageClass, 1, Integer::sum);
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
        // The root folder (NID 0x122) is an unnamed container: its contents go straight into
        // targetDir instead of a synthetic "Folder_<nid>" wrapper directory. Marking it visited
        // keeps the cycle guard intact and the recovery pass classifying its unreferenced children
        // as soft-deleted rather than orphaned.
        visited.add(rootFolderNode.nodeId());
        var rootFolder = new Folder(pstFile, rootFolderNode.nodeId());
        log.info("Processing folder: Root");
        processFolderContents(
                pstFile,
                rootFolder,
                targetDir,
                "Root",
                options,
                stats,
                indicator,
                log,
                visited,
                knownMessages,
                nameCounters,
                0,
                false);

        if (options.recoverDeletedItems() || options.scanOrphans()) {
            recoverUnreferencedMessages(
                    pstFile, targetDir, options, stats, indicator, log, visited, knownMessages, nameCounters);
        }
        if (!stats.skippedByClass().isEmpty()) {
            // Make it visible what an archive contained beyond mail, so skipped item types are a
            // conscious choice (the "convert non-mail items" option) rather than silent loss.
            var classSummaries = new ArrayList<String>();
            for (var entry : stats.skippedByClass().entrySet()) {
                classSummaries.add(entry.getKey() + " x" + entry.getValue());
            }
            log.info("Skipped by message class: " + String.join(", ", classSummaries)
                    + (options.exportNonMailItems()
                            ? ""
                            : " — enable \"Convert contacts, tasks, notes and distribution lists\" to export"
                                    + " the supported non-mail types"));
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
                if (failedToLoad(message, dumpster ? RECOVERED_FOLDER_NAME : ORPHANED_FOLDER_NAME, stats, log)) {
                    continue;
                }
                message.setAddressPreference(options.addressPreference());
                if (!isAllowedMessageClass(message.getMessageClass(), options.exportNonMailItems())) {
                    stats.recordSkipped(message.getMessageClass());
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

                var serializer = createSerializer(message, options, pstFile, 0, log, stats);
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

        processFolderContents(
                pstFile,
                folder,
                folderDir,
                currentPath + "/" + folderName,
                options,
                stats,
                indicator,
                log,
                visited,
                knownMessages,
                nameCounters,
                depth,
                true);
    }

    /**
     * Converts the messages of {@code folder} into {@code folderDir} and recurses into its
     * sub-folders. Factored out of {@link #processFolder} so the root folder's contents can be
     * written into the user-chosen target directory itself ({@code removeWhenEmpty} false — the
     * target directory is never deleted) rather than a synthetic wrapper directory.
     */
    private static void processFolderContents(
            PstFile pstFile,
            Folder folder,
            Path folderDir,
            String displayPath,
            Options options,
            Stats stats,
            ProgressIndicator indicator,
            ConversionLog log,
            Set<Integer> visited,
            Set<Integer> knownMessages,
            Map<Path, Integer> nameCounters,
            int depth,
            boolean removeWhenEmpty) {
        try {
            List<Integer> messages = List.of();
            try {
                messages = folder.getMessages();
                // Every message referenced by this folder's contents table is "known" — even if it is
                // later skipped (disallowed class, limit, duplicate) — so the recovery pass does not
                // mistake it for a deleted/orphaned node.
                knownMessages.addAll(messages);
            } catch (Exception exception) {
                log.error("Failed to list messages in " + displayPath + ": " + describeFailure(exception));
                stats.failedFolders++;
            }
            for (int msgNid : messages) {
                if (options.limit() != null && stats.converted >= options.limit()) {
                    break;
                }

                indicator.checkCanceled();
                indicator.setText(
                        "Converted " + stats.converted + (options.limit() != null ? " / " + options.limit() : "")
                                + " messages — current folder: " + displayPath);

                try {
                    var message = new Message(pstFile, msgNid);
                    if (failedToLoad(message, displayPath, stats, log)) {
                        continue;
                    }
                    message.setAddressPreference(options.addressPreference());
                    String messageClass = message.getMessageClass();
                    if (!isAllowedMessageClass(messageClass, options.exportNonMailItems())) {
                        stats.recordSkipped(messageClass);
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

                    var serializer = createSerializer(message, options, pstFile, 0, log, stats);
                    writeSerializerAtomically(serializer, emlFile);
                    stats.converted++;
                } catch (Exception exception) {
                    stats.failedMessages++;
                    // Track failures separately; do not pop notifications per failure to avoid spam.
                    log.error("Failed to convert message " + msgNid + " in " + displayPath + ": "
                            + describeFailure(exception));
                }
            }

            List<Folder> subFolders = List.of();
            try {
                subFolders = folder.getSubFolders();
            } catch (Exception exception) {
                log.error("Failed to list subfolders of " + displayPath + ": " + describeFailure(exception));
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
                        displayPath,
                        log,
                        visited,
                        knownMessages,
                        nameCounters,
                        depth + 1);
            }
        } finally {
            if (removeWhenEmpty && options.skipEmptyFolders()) {
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
        return createSerializer(message, options, pstFile, 0, log, new Stats());
    }

    static EmlSerializer createSerializer(
            Message message, Options options, PstFile pstFile, int depth, ConversionLog log, Stats stats) {
        if (depth > MAX_EMBEDDED_DEPTH) {
            // The replaced content is lost, so this is a failure worth counting, not just a note.
            stats.failedAttachments++;
            log.error("Maximum nested message depth (" + MAX_EMBEDDED_DEPTH
                    + ") reached; the deeper embedded message was replaced with a placeholder");
            var stub = new EmlSerializer();
            stub.setSubject("Nested Message Limit Exceeded");
            stub.addBody("The maximum nested message depth was reached.", "text/plain; charset=UTF-8");
            return stub;
        }
        var serializer = new EmlSerializer();

        String transportHeaders = message.getTransportHeaders();
        if (options.useOriginalHeaders()) {
            if (transportHeaders != null && !transportHeaders.isBlank()) {
                serializer.setTransportHeaders(transportHeaders);
            }
        } else {
            // The journal-report marker has no MAPI-derived substitute: without it an exported
            // Exchange journal report is no longer identifiable as one, so it survives even when
            // the user opted out of the original transport headers.
            String journalMarker = journalReportMarkerValue(transportHeaders);
            if (journalMarker != null) {
                serializer.addCustomHeader("X-MS-Journal-Report", journalMarker);
            }
        }

        String subject = message.getSubject();
        // A subject-less message stays subject-less: fabricating a "No Subject" header would alter
        // message content (the filename fallback in processFolder is a separate concern).
        if (subject != null && !subject.isBlank()) {
            serializer.setSubject(subject);
        }

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

        String inReplyTo = message.getStringProperty(MapiProperties.PR_IN_REPLY_TO_ID_W);
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            serializer.addCustomHeader("In-Reply-To", inReplyTo.trim());
        }
        String references = message.getStringProperty(MapiProperties.PR_INTERNET_REFERENCES_W);
        if (references != null && !references.isBlank()) {
            serializer.addCustomHeader("References", references.trim());
        }
        if (message.getProperty(MapiProperties.PR_IMPORTANCE) instanceof Number importance) {
            // MAPI importance: 0 = low, 1 = normal, 2 = high; normal is the default and stays implicit.
            if (importance.intValue() == 2) {
                serializer.addCustomHeader("Importance", "High");
                serializer.addCustomHeader("X-Priority", "1");
            } else if (importance.intValue() == 0) {
                serializer.addCustomHeader("Importance", "Low");
                serializer.addCustomHeader("X-Priority", "5");
            }
        }
        if (message.getProperty(MapiProperties.PR_SENSITIVITY) instanceof Number sensitivity) {
            // MAPI sensitivity: 0 = none (stays implicit); 1-3 map to the RFC 2156 Sensitivity values.
            String sensitivityLabel =
                    switch (sensitivity.intValue()) {
                        case 1 -> "Personal";
                        case 2 -> "Private";
                        case 3 -> "Company-Confidential";
                        default -> null;
                    };
            if (sensitivityLabel != null) {
                serializer.addCustomHeader("Sensitivity", sensitivityLabel);
            }
        }
        String threadTopic = message.getStringProperty(MapiProperties.PR_CONVERSATION_TOPIC_W);
        if (threadTopic != null && !threadTopic.isBlank()) {
            serializer.addCustomHeader("Thread-Topic", threadTopic);
        }
        if (message.getProperty(MapiProperties.PR_CONVERSATION_INDEX) instanceof byte[] conversationIndex
                && conversationIndex.length > 0) {
            serializer.addCustomHeader("Thread-Index", Base64.getEncoder().encodeToString(conversationIndex));
        }

        var senderName = message.getSenderName();
        var senderEmail = message.getSenderEmail();
        var fromName = senderName;
        var fromEmail = senderEmail;
        var authorEmail = message.getSentRepresentingEmail();
        if (!authorEmail.isBlank() && !senderEmail.isBlank() && !authorEmail.equalsIgnoreCase(senderEmail)) {
            // Sent on behalf of someone else: RFC 5322 §3.6.2 puts the author (sent-representing)
            // in From: and the actual transmitter in Sender:.
            fromName = message.getSentRepresentingName();
            fromEmail = authorEmail;
            serializer.setTransmitter(senderName, senderEmail);
        }
        serializer.setSender(fromName, fromEmail);

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

        var replyTo = new ArrayList<String>();
        for (Message.Recipient recipient : message.getReplyTo()) {
            String formatted = EmlSerializer.formatAddress(recipient.name, recipient.email);
            if (!formatted.isBlank()) {
                replyTo.add(formatted);
            }
        }
        if (!replyTo.isEmpty()) {
            serializer.addCustomHeader("Reply-To", String.join(", ", replyTo));
        }

        String msgClass = message.getMessageClass();

        String plainBody = message.getBody();
        String htmlBody = message.getHtmlBody();
        if (plainBody.isEmpty() && htmlBody.isEmpty()) {
            String rawRtf = message.getRawRtfBody();
            if (rawRtf.contains("\\fromtext")) {
                // \fromtext RTF encapsulates the plain-text body; when PR_BODY itself is missing
                // the encapsulated text is the only renderable content, so extract it instead of
                // exporting an empty message with only a body.rtf attachment.
                plainBody = RtfStripper.strip(rawRtf);
            }
        }
        if (msgClass != null && msgClass.startsWith("IPM.DistList") && plainBody.isEmpty()) {
            // A distribution list has no body of its own; its content is the member list.
            plainBody = formatDistributionListMembers(message);
        }
        serializer.addBody(plainBody, "text/plain; charset=UTF-8");
        serializer.addBody(htmlBody, "text/html; charset=UTF-8");
        String rtfBody = message.getRtfBody();
        if (rtfBody.isEmpty() && plainBody.isEmpty() && htmlBody.isEmpty()) {
            // Encapsulation RTF (\fromtext / \fromhtml) is normally redundant with the decoded
            // bodies and dropped; when the sibling bodies are missing it is the only content left,
            // so keep the raw RTF rather than exporting an empty message.
            rtfBody = message.getRawRtfBody();
        }
        if (!rtfBody.isEmpty()) {
            // A genuine RTF body (not encapsulated HTML) is not renderable by mail clients as a
            // multipart/alternative sibling; preserve it as an application/rtf attachment carrying
            // its original windows-1252 bytes (the LzFu decode is a lossless 1252 round-trip).
            serializer.addAttachment("body.rtf", "application/rtf", rtfBody.getBytes(RTF_CHARSET), null, false);
        }

        if (msgClass != null && msgClass.startsWith("IPM.Contact")) {
            serializer.addAttachment(
                    "contact.vcf",
                    "text/vcard; charset=UTF-8",
                    buildContactCard(message, pstFile).getBytes(StandardCharsets.UTF_8),
                    null,
                    false);
        }
        if (msgClass != null && msgClass.startsWith("IPM.Task")) {
            serializer.addAttachment(
                    "task.ics",
                    "text/calendar; charset=UTF-8; method=PUBLISH",
                    buildTaskTodo(message, pstFile, subject).getBytes(StandardCharsets.UTF_8),
                    null,
                    false);
        }
        if (msgClass != null && msgClass.startsWith("IPM.Note.SMIME")) {
            // The serializer re-encodes the MIME structure, which necessarily invalidates a
            // signed/encrypted envelope; surface that instead of letting the user discover it
            // when signature verification fails.
            log.info("Message " + message.getNid() + " is S/MIME (" + msgClass
                    + "); the converted EML re-encodes the MIME structure, so the original"
                    + " signature/encryption envelope will not verify");
        }
        // Emit a calendar invite for both calendar items (IPM.Appointment) and meeting messages
        // (IPM.Schedule.Meeting.*); both store the start/end/location named properties below.
        if (msgClass != null
                && (msgClass.startsWith("IPM.Appointment") || msgClass.startsWith("IPM.Schedule.Meeting"))) {
            Integer startId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x820D);
            Integer endId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x820E);
            Integer locId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x8208);

            Instant start = startId != null && message.getProperty(startId) instanceof Instant instant ? instant : null;
            Instant end = endId != null && message.getProperty(endId) instanceof Instant instant ? instant : null;
            String location = locId != null ? message.getStringProperty(locId) : null;

            if (start == null) {
                // Without a real start time the invite would have to fabricate one; matching the
                // MSG converter's decision, no invite is emitted at all.
                log.info("Skipping calendar invite for message " + message.getNid() + ": no start time stored");
            } else {
                var attendees = new ArrayList<ICalendarGenerator.Attendee>();
                for (Message.Recipient recipient : recipients) {
                    if (recipient.email != null && !recipient.email.isBlank()) {
                        attendees.add(new ICalendarGenerator.Attendee(recipient.name, recipient.email));
                    }
                }
                String method = icalMethod(msgClass, !attendees.isEmpty());

                // All-day flag, event time zone and recurrence (PidLidAppointmentSubType /
                // PidLidTimeZoneStruct / PidLidAppointmentRecur): a recurring meeting exports its
                // full series (RRULE + EXDATEs) anchored to the event's own zone, so the local hour
                // survives DST changes instead of drifting with a fixed UTC time.
                Integer allDayId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x8215);
                boolean allDay =
                        allDayId != null && message.getProperty(allDayId) instanceof Boolean allDayFlag && allDayFlag;
                Integer timeZoneId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x8233);
                WindowsTimeZone timeZone =
                        timeZoneId != null && message.getProperty(timeZoneId) instanceof byte[] timeZoneStruct
                                ? WindowsTimeZone.parse(timeZoneStruct)
                                : null;
                AppointmentRecurrence.Pattern recurrence = null;
                Integer recurrenceId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x8216);
                if (recurrenceId != null && message.getProperty(recurrenceId) instanceof byte[] recurrenceBlob) {
                    recurrence = AppointmentRecurrence.parse(recurrenceBlob);
                    if (recurrence == null) {
                        log.info("Message " + message.getNid() + " has a recurrence pattern this converter"
                                + " cannot map (malformed or non-Gregorian); the invite carries the first"
                                + " occurrence only");
                    }
                }

                String ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                        method,
                        Date.from(start),
                        end != null ? Date.from(end) : null,
                        location,
                        subject,
                        fromName,
                        fromEmail,
                        message.getBody(),
                        attendees,
                        allDay,
                        timeZone,
                        recurrence));
                serializer.addAttachment(
                        "invite.ics",
                        "text/calendar; charset=UTF-8; method=" + method,
                        ical.getBytes(StandardCharsets.UTF_8),
                        null,
                        false);
            }
        }

        long totalAttachmentBytes = 0;
        int attachmentCount = 0;
        List<Attachment> messageAttachments = message.getAttachments();
        if (messageAttachments.isEmpty() && message.hasAttachments()) {
            // PR_HASATTACH says there are attachments but the attachment table yielded none —
            // corruption or an unreadable table. Without this tripwire the message exports
            // "successfully" with its attachments silently missing.
            stats.failedAttachments++;
            log.error("Message " + message.getNid() + " claims attachments (PR_HASATTACH) but none could be"
                    + " read from its attachment table; they were not exported");
        }
        for (Attachment attachment : messageAttachments) {
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
                if (attachName.equals("attachment.dat")) {
                    // Embedded messages usually carry no filename properties, only PR_DISPLAY_NAME
                    // (typically the embedded subject); prefer it over the generic default.
                    var displayName = attachment.getDisplayName();
                    attachName = !displayName.isBlank() ? displayName : "message";
                }
                try {
                    Message embedMessage = message.readEmbeddedMessage(attachment);
                    if (embedMessage != null && !embedMessage.isLoaded()) {
                        // The embedded node resolved but its properties would not parse; serializing
                        // it would silently replace the original content with an empty .eml part.
                        stats.failedAttachments++;
                        log.error("Failed to load embedded message '" + attachName + "' in message "
                                + message.getNid() + ": " + describeFailure(embedMessage.getLoadError())
                                + "; the attachment was skipped");
                        continue;
                    }
                    if (embedMessage == null) {
                        // A method-5 attachment that does not resolve to a message node means the
                        // store is damaged; dropping it silently would lose the message a journal
                        // report (or forward) exists to carry.
                        stats.failedAttachments++;
                        log.error("Failed to resolve embedded message '" + attachName + "' in message "
                                + message.getNid() + "; the attachment was skipped");
                        continue;
                    }
                    log.info("Found embedded message attachment: " + attachName);
                    var embedSerializer = createSerializer(embedMessage, options, pstFile, depth + 1, log, stats);
                    var stringWriter = new StringWriter();
                    embedSerializer.writeTo(stringWriter);
                    if (!attachName.toLowerCase(Locale.ROOT).endsWith(".eml")) attachName += ".eml";
                    serializer.addEmbeddedMessage(attachName, stringWriter.toString());
                } catch (Exception exception) {
                    stats.failedAttachments++;
                    log.error("Failed to extract embedded message '" + attachName + "': " + describeFailure(exception));
                }
                continue;
            }

            byte[] data = attachment.getData();
            String mimeOverride = null;
            if (data == null && attachment.getAttachMethod() == ATTACH_OLE) {
                // An OLE-embedded object (afStorage) has no PT_BINARY content; its storage lives in
                // a subnode. Mail clients cannot open it directly, but exporting the raw bytes
                // beats dropping user content on the floor.
                try {
                    data = attachment.getObjectData();
                } catch (IOException exception) {
                    log.error("Failed to read the OLE object of attachment '" + attachName + "' in message "
                            + message.getNid() + ": " + describeFailure(exception));
                }
                if (data != null) {
                    if (!attachName.contains(".")) {
                        attachName = attachName + ".ole";
                    }
                    mimeOverride = "application/octet-stream";
                    log.info("Attachment '" + attachName + "' in message " + message.getNid()
                            + " is an embedded OLE object; its raw storage bytes were exported");
                }
            }
            if (data == null) {
                int attachMethod = attachment.getAttachMethod();
                if (attachMethod >= ATTACH_BY_REFERENCE && attachMethod <= ATTACH_BY_REFERENCE_ONLY) {
                    // By-reference attachments are links to files outside the store; there are no
                    // bytes to export, so this is expected rather than a failure.
                    log.info("Attachment '" + attachName + "' in message " + message.getNid()
                            + " is a reference to a file outside the store; nothing to export");
                } else {
                    stats.failedAttachments++;
                    Long attachSize = attachment.getSize();
                    if (attachSize != null && attachSize > options.maxNodeSize()) {
                        // The content exists but exceeds the configured single-node cap; without
                        // this distinction the log misdiagnoses it as missing content.
                        log.error("Attachment '" + attachName + "' in message " + message.getNid() + " (~"
                                + (attachSize / (1024 * 1024)) + " MB) exceeds the configured limit of "
                                + (options.maxNodeSize() / (1024 * 1024)) + " MB; raise \"Max single attachment"
                                + " size (MB)\" in the conversion dialog to export it");
                    } else {
                        log.error("Attachment '" + attachName + "' in message " + message.getNid()
                                + " has no stored content (attach method " + attachMethod + "); it was skipped");
                    }
                }
                continue;
            }

            totalAttachmentBytes += data.length;
            if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                log.error("Message attachments exceed " + (MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024))
                        + " MB in aggregate; remaining attachments were skipped");
                break;
            }

            String mime = mimeOverride != null ? mimeOverride : attachment.getMimeTag();
            if (mime.isEmpty()) mime = "application/octet-stream";

            log.info("Found attachment: " + attachName + " (" + mime + ")");
            serializer.addAttachment(
                    attachName,
                    mime,
                    data,
                    attachment.getContentId(),
                    attachment.getContentLocation(),
                    attachment.isInline());
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

    /** The plain-text body of an exported distribution list: one member per line. */
    private static String formatDistributionListMembers(Message message) {
        var members = message.getDistributionListMembers();
        if (members.isEmpty()) {
            return "";
        }
        var listing = new StringBuilder("Distribution list members:\r\n");
        for (Message.Recipient member : members) {
            var formatted = EmlSerializer.formatAddress(member.name, member.email);
            if (!formatted.isBlank()) {
                listing.append("- ").append(formatted).append("\r\n");
            }
        }
        return listing.toString();
    }

    /** A vCard for an {@code IPM.Contact} item: names, organization, phones and the Email1-3 named properties. */
    private static String buildContactCard(Message message, PstFile pstFile) {
        var contact = new VCardGenerator.Contact()
                .displayName(message.getStringProperty(MapiProperties.PR_DISPLAY_NAME_W))
                .givenName(message.getStringProperty(MapiProperties.PR_GIVEN_NAME_W))
                .surname(message.getStringProperty(MapiProperties.PR_SURNAME_W))
                .company(message.getStringProperty(MapiProperties.PR_COMPANY_NAME_W))
                .jobTitle(message.getStringProperty(MapiProperties.PR_TITLE_W))
                .phone("work", message.getStringProperty(MapiProperties.PR_BUSINESS_TELEPHONE_NUMBER_W))
                .phone("home", message.getStringProperty(MapiProperties.PR_HOME_TELEPHONE_NUMBER_W))
                .phone("cell", message.getStringProperty(MapiProperties.PR_MOBILE_TELEPHONE_NUMBER_W));
        // PidLidEmail1EmailAddress / Email2 / Email3 ([MS-OXOCNTC] §2.2.1.2.3).
        for (int namedId : new int[] {0x8083, 0x8093, 0x80A3}) {
            Integer propertyId = pstFile.namedPropertyId(PSETID_ADDRESS, namedId);
            if (propertyId != null) {
                contact.email(message.getStringProperty(propertyId));
            }
        }
        return VCardGenerator.generate(contact);
    }

    /** A VTODO for an {@code IPM.Task} item: start/due dates, completion state and percent complete. */
    private static String buildTaskTodo(Message message, PstFile pstFile, String subject) {
        Instant start = namedInstant(message, pstFile, 0x8104); // PidLidTaskStartDate
        Instant due = namedInstant(message, pstFile, 0x8105); // PidLidTaskDueDate
        Integer percentId = pstFile.namedPropertyId(PSETID_TASK, 0x8102); // PidLidPercentComplete
        Double percent = percentId != null && message.getProperty(percentId) instanceof Double value ? value : null;
        Integer completeId = pstFile.namedPropertyId(PSETID_TASK, 0x811C); // PidLidTaskComplete
        Boolean complete =
                completeId != null && message.getProperty(completeId) instanceof Boolean value ? value : null;
        return ICalendarGenerator.generateTodo(
                subject,
                message.getBody(),
                start != null ? Date.from(start) : null,
                due != null ? Date.from(due) : null,
                percent,
                complete);
    }

    private static Instant namedInstant(Message message, PstFile pstFile, int namedId) {
        Integer propertyId = pstFile.namedPropertyId(PSETID_TASK, namedId);
        return propertyId != null && message.getProperty(propertyId) instanceof Instant value ? value : null;
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
     * The value of the {@code X-MS-Journal-Report} header in an original transport-header block, or
     * {@code null} when the block has none. The marker is normally empty-valued ([MS-OXTNEF]
     * journaling), so an empty string is a meaningful "present" result distinct from {@code null}.
     * Package-private for testing.
     */
    static String journalReportMarkerValue(String transportHeaders) {
        if (transportHeaders == null || transportHeaders.isBlank()) {
            return null;
        }
        var matcher = JOURNAL_REPORT_HEADER.matcher(transportHeaders);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * Reports a message whose properties failed to load (corrupt or missing node) and tells the
     * caller to skip it. Without this check every getter returns its empty default and the message
     * is exported as a blank "No Subject" EML silently counted as a success. Package-private for
     * testing.
     */
    static boolean failedToLoad(Message message, String displayPath, Stats stats, ConversionLog log) {
        if (message.isLoaded()) {
            return false;
        }
        stats.failedMessages++;
        log.error("Failed to convert message " + message.getNid() + " in " + displayPath + ": "
                + describeFailure(message.getLoadError()));
        return true;
    }

    /**
     * The iTIP method for a calendar item (RFC 5546): meeting cancellations are {@code CANCEL}s,
     * responses {@code REPLY}s and requests {@code REQUEST}s — each only when at least one attendee
     * is available, because those methods are invalid without one and a plain {@code PUBLISH}
     * renders everywhere. Plain appointments are always {@code PUBLISH}ed.
     */
    static String icalMethod(String messageClass, boolean hasAttendees) {
        if (!hasAttendees || !messageClass.startsWith("IPM.Schedule.Meeting")) {
            return "PUBLISH";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Canceled")) {
            return "CANCEL";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Resp")) {
            return "REPLY";
        }
        return "REQUEST";
    }

    /**
     * Whether a message with the given {@code PidTagMessageClass} should be exported. Intentionally
     * permits a {@code null} or empty class: [MS-OXCMSG] §2.2.1.3 defines a missing message class as
     * the generic {@code IPM} note, so a malformed item with no class is treated as a plain email
     * (best-effort fidelity) rather than silently dropped. Everything else must match an allowed prefix.
     */
    private static boolean isAllowedMessageClass(String messageClass, boolean exportNonMailItems) {
        if (messageClass == null || messageClass.isEmpty()) {
            return true;
        }
        for (String allowed : ALLOWED_MESSAGE_CLASSES) {
            if (messageClass.startsWith(allowed)) {
                return true;
            }
        }
        if (exportNonMailItems) {
            for (String allowed : NON_MAIL_MESSAGE_CLASSES) {
                if (messageClass.startsWith(allowed)) {
                    return true;
                }
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
