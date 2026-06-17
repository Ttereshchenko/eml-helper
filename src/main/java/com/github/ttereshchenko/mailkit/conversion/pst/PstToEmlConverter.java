package com.github.ttereshchenko.mailkit.conversion.pst;

import com.github.ttereshchenko.mailkit.conversion.AppointmentRecurrence;
import com.github.ttereshchenko.mailkit.conversion.AttachmentBudget;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.conversion.HtmlMetaCharset;
import com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator;
import com.github.ttereshchenko.mailkit.conversion.ReportGenerator;
import com.github.ttereshchenko.mailkit.conversion.RtfStripper;
import com.github.ttereshchenko.mailkit.conversion.SmimeEntityHoist;
import com.github.ttereshchenko.mailkit.conversion.VCardGenerator;
import com.github.ttereshchenko.mailkit.conversion.WindowsTimeZone;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.Folder;
import com.github.ttereshchenko.mailkit.pst.MapiProperties;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.NodeEntry;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    // Package-private (not private) so the depth-guard regression test can drive the recursive
    // processFolder past the cap without fabricating a 256-deep folder hierarchy.
    static final int MAX_FOLDER_DEPTH = 256;
    private static final int MAX_SUBJECT_LENGTH = 100;
    // Keep the full output path within Windows' 260-char MAX_PATH (with headroom for the long-path API).
    private static final int MAX_PATH_LENGTH = 255;
    private static final int ATTACH_EMBEDDED_MSG = 5; // PR_ATTACH_METHOD == afEmbeddedMessage
    // PR_ATTACH_METHOD values whose content lives outside the store ([MS-OXCMSG] §2.2.2.9).
    private static final int ATTACH_BY_REFERENCE = 2; // afByReference
    private static final int ATTACH_BY_REFERENCE_ONLY = 4; // afByRefOnly (3 = afByReferenceResolve)

    private static final List<String> ALLOWED_MESSAGE_CLASSES = List.of(
            "IPM.Note",
            "IPM.Post",
            "REPORT.",
            "IPM.Schedule.Meeting.",
            "IPM.Appointment",
            // Message-like classes exported as a generic EML for parity with the MSG path (which has no
            // allow-list). Each is a transient/utility item that still lands in a mail folder, so it is
            // preserved rather than silently dropped; none carries a specialized payload, so each emits
            // the "no specialized handler" downgrade log. The literal "IPM" (no form found) is an exact
            // match, not a prefix, so it is accepted in isAllowedMessageClass instead.
            "IPM.Document",
            "IPM.OLE.Class",
            "IPM.Recall",
            "IPM.Outlook.Recall",
            "IPM.Remote",
            "IPM.Report",
            "IPM.Resend");

    // Non-mail item classes exported only when Options.exportNonMailItems is on: contacts become
    // EMLs with a vCard, tasks carry a VTODO, sticky notes and journal entries export their text,
    // and distribution lists list their members.
    private static final List<String> NON_MAIL_MESSAGE_CLASSES =
            List.of("IPM.Contact", "IPM.Task", "IPM.StickyNote", "IPM.DistList", "IPM.Activity");

    // Named-property sets ([MS-OXPROPS] §1.3.2) for appointment, task and contact properties.
    private static final UUID PSETID_APPOINTMENT = UUID.fromString("00062002-0000-0000-C000-000000000046");
    private static final UUID PSETID_TASK = UUID.fromString("00062003-0000-0000-C000-000000000046");
    private static final UUID PSETID_ADDRESS = UUID.fromString("00062004-0000-0000-C000-000000000046");
    private static final UUID PS_PUBLIC_STRINGS = UUID.fromString("00020329-0000-0000-C000-000000000046");

    private static final int ATTACH_OLE = 6; // afStorage: an embedded OLE object

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
            // Recovery scans the whole node database, so without per-iteration text the progress UI shows
            // only the stale folder-walk message and looks hung on a large store ("scan orphans" is on by
            // default). Mirror processFolderContents and report ongoing progress.
            indicator.setText("Recovering deleted/orphaned messages — " + stats.converted + " recovered so far");

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
                        emlFile = nextFreeEmlFile(recoveryDir, safeSubject, String.valueOf(nid), nameCounters);
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
            } catch (ProcessCanceledException canceled) {
                // As in the folder walk: cancellation must propagate, not be counted as a failed
                // recovery and logged as a spurious error.
                throw canceled;
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

    // Package-private (not private) so the cycle- and depth-guard regression tests can pre-seed the
    // visited set / depth without needing a crafted cyclic or 256-deep PST hierarchy.
    static void processFolder(
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
            // The Folder constructor swallows a read failure into loadError rather than throwing, so an
            // unreadable folder must be detected explicitly here — otherwise it would silently create an
            // empty directory, lose its messages, and be counted as a success. (Its messages still
            // classify as Recovered, not Orphaned, because folderNid is already in `visited`.)
            if (!folder.isLoaded()) {
                log.error("Failed to read folder " + folderNid + ": " + describeFailure(folder.getLoadError()));
                stats.failedFolders++;
                return;
            }
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
                            emlFile = nextFreeEmlFile(folderDir, safeSubject, String.valueOf(msgNid), nameCounters);
                        }
                    }

                    var serializer = createSerializer(message, options, pstFile, 0, log, stats);
                    writeSerializerAtomically(serializer, emlFile);
                    stats.converted++;
                } catch (ProcessCanceledException canceled) {
                    // The progress indicator's checkCanceled() throws ProcessCanceledException (a
                    // RuntimeException via CancellationException); the action layer expects it to
                    // propagate so the cancel is graceful. Rethrow before the generic catch so it is
                    // not swallowed, counted as a failed message, and logged as a spurious failure.
                    throw canceled;
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
        // Each top-level message gets a fresh attachment budget; the recursive overload threads the
        // same instance through embedded messages so the caps bound the whole tree (see AttachmentBudget).
        return createSerializer(message, options, pstFile, depth, log, stats, new AttachmentBudget());
    }

    private static EmlSerializer createSerializer(
            Message message,
            Options options,
            PstFile pstFile,
            int depth,
            ConversionLog log,
            Stats stats,
            AttachmentBudget budget) {
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
            // RFC 5322 §3.6.4: In-Reply-To/References are angle-bracketed msg-id lists, but MAPI stores
            // them unbracketed. Normalize through the shared helper the MSG path uses (bare tokens get
            // <...>, @-less tokens dropped) instead of emitting the raw value.
            var normalized = EmlSerializer.normalizeMessageIdList(inReplyTo);
            if (!normalized.isEmpty()) {
                serializer.addCustomHeader("In-Reply-To", normalized);
            }
        }
        String references = message.getStringProperty(MapiProperties.PR_INTERNET_REFERENCES_W);
        if (references != null && !references.isBlank()) {
            var normalized = EmlSerializer.normalizeMessageIdList(references);
            if (!normalized.isEmpty()) {
                serializer.addCustomHeader("References", normalized);
            }
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
        var authorName = message.getSentRepresentingName();
        var authorEmail = message.getSentRepresentingEmail();
        var fromName = senderName;
        var fromEmail = senderEmail;
        if (!authorEmail.isBlank()) {
            if (senderEmail.isBlank()) {
                // Only the represented author has a usable address (e.g. a delegated/draft item with no
                // PR_SENDER_EMAIL): promote it to From: rather than dropping it and emitting the
                // undisclosed placeholder (RFC 5322 §3.6.2). Matches the MSG path.
                fromName = authorName;
                fromEmail = authorEmail;
            } else if (!authorEmail.equalsIgnoreCase(senderEmail)) {
                // Sent on behalf of someone else: the author (sent-representing) goes in From: and the
                // actual transmitter in Sender:.
                fromName = authorName;
                fromEmail = authorEmail;
                serializer.setTransmitter(senderName, senderEmail);
            }
        } else if (senderName.isBlank() && senderEmail.isBlank() && !(authorName.isBlank() && authorEmail.isBlank())) {
            // The sender identity is entirely empty but a represented author exists (display-name only):
            // use it so From: is not the undisclosed placeholder. Matches the MSG path.
            fromName = authorName;
            fromEmail = authorEmail;
        }
        serializer.setSender(fromName, fromEmail);

        var recipients = message.getRecipients();
        var seenTypes = new HashSet<Integer>();
        for (Message.Recipient recipient : recipients) {
            // Mask PR_RECIPIENT_TYPE to its class bits ([MS-OXOMSG] §2.2.3.1) before the To/Cc/Bcc
            // compare, matching the MSG path: a recipient Exchange-flagged as already-processed
            // (e.g. 0x10000001 on a resent item) otherwise matches no class and is silently dropped.
            var maskedType = recipient.type & 0x0FFFFFFF;
            serializer.addRecipient(maskedType, recipient.name, recipient.email);
            if ((recipient.name != null && !recipient.name.isBlank())
                    || (recipient.email != null && !recipient.email.isBlank())) {
                seenTypes.add(maskedType);
            }
        }
        // Fall back to the PR_DISPLAY_* strings for each recipient type the structured table did not
        // supply, matching the MSG path (MsgToEmlConverter#populateRecipients): a table carrying a usable
        // To but only display-string Cc/Bcc would otherwise lose those headers. An empty/unreadable table
        // (no type seen) falls back for all three.
        if (!seenTypes.contains(EmlSerializer.RECIPIENT_TYPE_TO)) {
            addDisplayFallback(serializer, message.getTo(), EmlSerializer.RECIPIENT_TYPE_TO);
        }
        if (!seenTypes.contains(EmlSerializer.RECIPIENT_TYPE_CC)) {
            addDisplayFallback(serializer, message.getDisplayCc(), EmlSerializer.RECIPIENT_TYPE_CC);
        }
        if (!seenTypes.contains(EmlSerializer.RECIPIENT_TYPE_BCC)) {
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

        // Categories: PidNameKeywords is a string-named property in PS_PUBLIC_STRINGS (PT_MV_UNICODE).
        Integer keywordsId = pstFile.namedPropertyId(PS_PUBLIC_STRINGS, "Keywords");
        if (keywordsId != null) {
            var categories = collectStrings(message.getProperty(keywordsId));
            if (!categories.isEmpty()) {
                serializer.addCustomHeader("Keywords", String.join(", ", categories));
            }
        }
        // PR_READ_RECEIPT_REQUESTED -> Disposition-Notification-To (rfc8098), addressed to the From author.
        if (message.getProperty(MapiProperties.PR_READ_RECEIPT_REQUESTED) instanceof Boolean requested
                && requested
                && !fromEmail.isBlank()) {
            serializer.addCustomHeader("Disposition-Notification-To", EmlSerializer.formatAddress(fromName, fromEmail));
        }

        String msgClass = message.getMessageClass();

        // REPORT.* (NDR/DSN and read/non-read receipts) become an RFC 6522 multipart/report so the
        // structured delivery-status / disposition-notification survives instead of being flattened to
        // a plain body — reusing the POI-free generator the MSG path adopted.
        if (msgClass != null && msgClass.startsWith("REPORT.") && emitReport(message, msgClass, serializer)) {
            return serializer;
        }
        // IPM.Note.SMIME* / IPM.Note.Secure* keep their complete original MIME envelope in a single
        // attachment ([MS-OXOSMIME] §2.2.1); hoist it to the top level so the signature/encryption
        // stays verifiable instead of being demoted to an opaque attachment by the re-encode.
        if (msgClass != null && (msgClass.startsWith("IPM.Note.SMIME") || msgClass.startsWith("IPM.Note.Secure"))) {
            if (hoistSmimeEntity(message, serializer, log)) {
                return serializer;
            }
            log.info("Message " + message.getNid() + " is S/MIME (" + msgClass + ") but its envelope could"
                    + " not be hoisted (not a single stored entity); the converted EML re-encodes the MIME"
                    + " structure, so the original signature/encryption will not verify");
        }

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
        // The HTML was decoded from its original codepage and is re-emitted as UTF-8; rewrite any
        // surviving in-document <meta charset=...> so it cannot contradict the MIME charset.
        serializer.addBody(HtmlMetaCharset.rewriteToUtf8(htmlBody), "text/html; charset=UTF-8");
        String rtfBody = message.getRtfBody();
        if (rtfBody.isEmpty() && plainBody.isEmpty() && htmlBody.isEmpty()) {
            // Encapsulation RTF (\fromtext / \fromhtml) is normally redundant with the decoded
            // bodies and dropped; when the sibling bodies are missing it is the only content left,
            // so keep the raw RTF rather than exporting an empty message.
            rtfBody = message.getRawRtfBody();
        }
        if (!rtfBody.isEmpty()) {
            // A genuine RTF body (not encapsulated HTML) is not renderable by mail clients as a
            // multipart/alternative sibling; preserve it as an application/rtf attachment carrying the
            // original RTF bytes verbatim (getRawRtfBytes avoids the windows-1252 String round-trip,
            // which maps five undefined byte values to '?').
            serializer.addAttachment("body.rtf", "application/rtf", message.getRawRtfBytes(), null, false);
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
            // IPM.TaskRequest* is a task-assignment message, not a plain task: emit the matching iTIP
            // METHOD (REQUEST / REPLY) instead of letting startsWith("IPM.Task") mislabel it PUBLISH.
            String taskMethod = taskMethod(msgClass);
            // RFC 5546 §3.4: a task REQUEST/REPLY carries an ORGANIZER and ATTENDEE(s). For a REQUEST the
            // ORGANIZER is the assigner (From) and the ATTENDEE(s) the assignee recipients. For a REPLY the
            // roles swap (mirroring the meeting path): the ORGANIZER is the original assigner (the To
            // recipient) and the single ATTENDEE is the responding sender, with the accept/decline
            // PARTSTAT. A plain task keeps neither. effectiveTodoMethod downgrades to PUBLISH when the
            // parties a scheduling object needs are missing.
            String taskOrganizerName = null;
            String taskOrganizerEmail = null;
            List<ICalendarGenerator.Attendee> taskAttendees = List.of();
            if ("REQUEST".equals(taskMethod)) {
                taskOrganizerName = fromName;
                taskOrganizerEmail = fromEmail;
                taskAttendees = visibleAttendees(recipients);
            } else if ("REPLY".equals(taskMethod)) {
                var replyAttendees = visibleAttendees(recipients);
                if (!replyAttendees.isEmpty()) {
                    var assigner = recipients.stream()
                            .filter(recipient -> (recipient.type & 0x0FFFFFFF) == EmlSerializer.RECIPIENT_TYPE_TO
                                    && recipient.email != null
                                    && !recipient.email.isBlank())
                            .findFirst()
                            .orElseGet(() -> recipients.stream()
                                    .filter(recipient -> recipient.email != null && !recipient.email.isBlank())
                                    .findFirst()
                                    .orElse(null));
                    if (assigner != null) {
                        taskOrganizerName = assigner.name;
                        taskOrganizerEmail = assigner.email;
                        taskAttendees = List.of(new ICalendarGenerator.Attendee(
                                fromName, fromEmail, ICalendarGenerator.taskResponsePartStat(msgClass)));
                    }
                }
                // else: no recipient identifies the assigner — leave participants empty to downgrade.
            }
            serializer.addAttachment(
                    "task.ics",
                    "text/calendar; charset=UTF-8; method="
                            + ICalendarGenerator.effectiveTodoMethod(taskMethod, taskOrganizerEmail, taskAttendees),
                    buildTaskTodo(
                                    message,
                                    pstFile,
                                    subject,
                                    taskMethod,
                                    taskOrganizerName,
                                    taskOrganizerEmail,
                                    taskAttendees)
                            .getBytes(StandardCharsets.UTF_8),
                    null,
                    false);
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
                var attendees = visibleAttendees(recipients);
                String method = ICalendarGenerator.method(msgClass, !attendees.isEmpty());
                String organizerName = fromName;
                String organizerEmail = fromEmail;
                List<ICalendarGenerator.Attendee> eventAttendees = attendees;
                if ("REPLY".equals(method)) {
                    // RFC 5546 §3.2.3: a meeting-response REPLY flows from the responding ATTENDEE to the
                    // ORGANIZER and carries that attendee's PARTSTAT, so the two roles swap relative to a
                    // REQUEST. The responder is the sender (fromName/fromEmail). The ORGANIZER is the
                    // original meeting organizer — in the stored response that is the message's To
                    // recipient (PR_RECIPIENT_TYPE = TO), NOT simply the first recipient row: a response
                    // CC'd to delegates or other attendees would otherwise pick a non-organizer (and the
                    // real organizer would be lost). Fall back to the first usable recipient only when no
                    // explicit To recipient carries an address.
                    var meetingOrganizer = recipients.stream()
                            .filter(recipient -> (recipient.type & 0x0FFFFFFF) == EmlSerializer.RECIPIENT_TYPE_TO
                                    && recipient.email != null
                                    && !recipient.email.isBlank())
                            .findFirst()
                            .orElseGet(() -> recipients.stream()
                                    .filter(recipient -> recipient.email != null && !recipient.email.isBlank())
                                    .findFirst()
                                    .orElse(null));
                    if (meetingOrganizer != null) {
                        organizerName = meetingOrganizer.name;
                        organizerEmail = meetingOrganizer.email;
                    }
                    eventAttendees = List.of(new ICalendarGenerator.Attendee(
                            fromName, fromEmail, ICalendarGenerator.responsePartStat(msgClass)));
                }

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

                Integer sequenceId = pstFile.namedPropertyId(PSETID_APPOINTMENT, 0x8201); // PidLidAppointmentSequence
                int sequence = sequenceId != null && message.getProperty(sequenceId) instanceof Number sequenceValue
                        ? sequenceValue.intValue()
                        : 0;
                var eventDetails = new ICalendarGenerator.EventDetails(
                        method,
                        Date.from(start),
                        end != null ? Date.from(end) : null,
                        location,
                        subject,
                        organizerName,
                        organizerEmail,
                        message.getBody(),
                        eventAttendees,
                        allDay,
                        timeZone,
                        recurrence,
                        sequence);
                String ical = ICalendarGenerator.generate(eventDetails);
                serializer.addAttachment(
                        "invite.ics",
                        // Stamp method= with what generate() actually emitted (it downgrades to PUBLISH
                        // without a resolvable organizer/start), so it equals the body METHOD (rfc6047 §2.4).
                        "text/calendar; charset=UTF-8; method=" + ICalendarGenerator.effectiveMethod(eventDetails),
                        ical.getBytes(StandardCharsets.UTF_8),
                        null,
                        false);
            }
        }

        if (msgClass != null && !hasSpecializedHandler(msgClass)) {
            // Every other class still exported a generic EML above; surface that downgrade rather than
            // silently dropping the item's specialized semantics (documents, recall/remote/resend
            // utility items, journal entries and sticky notes).
            log.info("No specialized handler for message class " + msgClass + "; exported as a generic message");
        }

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
            if (budget.atCountLimit()) {
                log.error("Message has more than " + AttachmentBudget.MAX_ATTACHMENT_COUNT
                        + " attachments (including nested messages); remaining attachments were skipped");
                break;
            }
            budget.recordAttachment();

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
                    var embedSerializer =
                            createSerializer(embedMessage, options, pstFile, depth + 1, log, stats, budget);
                    // Serialize the child to BYTES (not a char sink) so a nested clear-signed S/MIME entity
                    // reaches the parent EML byte-for-byte and keeps its signature; EmlSerializer emits these
                    // bytes through its byte-exact raw-body path.
                    var embedStream = new ByteArrayOutputStream();
                    embedSerializer.writeTo(embedStream);
                    var nestedEml = embedStream.toByteArray();
                    // Count the serialized nested EML against the aggregate byte cap (parity with the
                    // regular-attachment path below); its own nested-attachment bytes were already recorded.
                    if (budget.recordBytes(nestedEml.length)) {
                        log.error("Message attachments exceed " + AttachmentBudget.maxTotalMegabytes()
                                + " MB in aggregate (including nested messages); remaining attachments were skipped");
                        break;
                    }
                    if (!attachName.toLowerCase(Locale.ROOT).endsWith(".eml")) attachName += ".eml";
                    serializer.addEmbeddedMessage(attachName, nestedEml);
                } catch (ProcessCanceledException canceled) {
                    // Never demote a cancellation to a failed-attachment count; let it unwind so the
                    // whole conversion stops (mirrors the per-message guard in processFolderContents).
                    throw canceled;
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

            if (budget.recordBytes(data.length)) {
                log.error("Message attachments exceed " + AttachmentBudget.maxTotalMegabytes()
                        + " MB in aggregate (including nested messages); remaining attachments were skipped");
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
            if (trimmed.isEmpty()) {
                continue;
            }
            if (EmlSerializer.looksLikeSmtpAddress(trimmed)) {
                // A bare SMTP address in the display string belongs in the address slot, not the name slot
                // (which would emit an unparseable quoted-name with no address), matching the MSG path.
                serializer.addRecipient(recipientType, null, trimmed);
            } else {
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

    /** A VTODO for an {@code IPM.Task}/{@code IPM.TaskRequest} item: dates, completion state and iTIP method. */
    private static String buildTaskTodo(
            Message message,
            PstFile pstFile,
            String subject,
            String method,
            String organizerName,
            String organizerEmail,
            List<ICalendarGenerator.Attendee> attendees) {
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
                complete,
                method,
                organizerName,
                organizerEmail,
                attendees);
    }

    /**
     * The visible ATTENDEE list (RFC 5546) for a meeting/assigned-task scheduling object: every
     * recipient with a resolvable address except BCC-class recipients (PR_RECIPIENT_TYPE masked to its
     * class bits, [MS-OXOMSG] §2.2.3.1), which are hidden from the other participants and must not leak
     * into a property they can all read.
     */
    private static List<ICalendarGenerator.Attendee> visibleAttendees(List<Message.Recipient> recipients) {
        var attendees = new ArrayList<ICalendarGenerator.Attendee>();
        for (Message.Recipient recipient : recipients) {
            if (recipient.email != null
                    && !recipient.email.isBlank()
                    && (recipient.type & 0x0FFFFFFF) != EmlSerializer.RECIPIENT_TYPE_BCC) {
                attendees.add(new ICalendarGenerator.Attendee(recipient.name, recipient.email));
            }
        }
        return attendees;
    }

    private static Instant namedInstant(Message message, PstFile pstFile, int namedId) {
        Integer propertyId = pstFile.namedPropertyId(PSETID_TASK, namedId);
        return propertyId != null && message.getProperty(propertyId) instanceof Instant value ? value : null;
    }

    /**
     * Replaces the message body with an RFC 6522 {@code multipart/report} reconstructed from the
     * report's MAPI properties via the shared {@link ReportGenerator}: a delivery report
     * ({@code REPORT.*.NDR}/{@code .DR}) yields a {@code message/delivery-status} part (RFC 3464), a
     * read receipt ({@code .IPNRN}/{@code .IPNNRN}) a {@code message/disposition-notification} part
     * (RFC 8098). The class suffix selects the branch and supplies the {@code Action} /
     * {@code disposition-type} that MAPI does not store verbatim.
     *
     * <p>The per-recipient fields mirror the MSG path: {@code Final-Recipient} (rfc3464 §2.3.2) is the
     * address that actually failed (the report's recipient-table entry, not its own PidTagDisplayTo),
     * the {@code Status} {@code d.d.d} code (§2.3.4) is recovered from the report's enhanced-status
     * text, and the free-form {@code PidTagSupplementaryInfo} becomes the {@code Diagnostic-Code}
     * (§2.3.6) — it is human-readable transport text and must not sit in the strict {@code Status}.
     */
    private static boolean emitReport(Message message, String messageClass, EmlSerializer serializer) {
        boolean deliveryReport = messageClass.endsWith(".NDR") || messageClass.endsWith(".DR");
        // Only NDR/DR delivery reports and IPNRN/IPNNRN read receipts carry a structured status. Any
        // other REPORT.* (delay/relay/etc.) is neither a DSN nor an MDN, so it must not be emitted as a
        // disposition-notification claiming the message was "displayed" (rfc8098 §3.2.6). Decline it and
        // let the caller fall back to the generic body.
        boolean readReceipt = messageClass.endsWith(".IPNRN") || messageClass.endsWith(".IPNNRN");
        if (!deliveryReport && !readReceipt) {
            return false;
        }
        String action = messageClass.endsWith(".NDR") ? "failed" : messageClass.endsWith(".DR") ? "delivered" : null;
        String dispositionType = messageClass.endsWith(".IPNNRN") ? "deleted" : "displayed";
        var supplementaryInfo = message.getStringProperty(0x0C1B); // PidTagSupplementaryInfo (transport text)
        String status = null;
        String diagnosticCode = null;
        String finalRecipient = null;
        if (deliveryReport) {
            finalRecipient = reportFailedRecipient(message);
            status = ReportGenerator.statusCode(
                    messageClass.endsWith(".NDR"), supplementaryInfo, message.getStringProperty(0x1001));
            diagnosticCode = supplementaryInfo;
        } else {
            // rfc8098 §3.2.4: an MDN's Final-Recipient is the reader issuing the receipt — the receipt's
            // own author/sender, not its To recipient (PR_DISPLAY_TO is the original sender who requested
            // the receipt).
            finalRecipient = message.getSentRepresentingEmail();
            if (finalRecipient == null || finalRecipient.isBlank()) {
                finalRecipient = message.getSenderEmail();
            }
        }
        if (finalRecipient == null || finalRecipient.isBlank()) {
            // Last resort only: no per-recipient (DSN) or reader (MDN) address was available.
            finalRecipient = message.getStringProperty(MapiProperties.PR_DISPLAY_TO_W);
        }
        var info = new ReportGenerator.ReportInfo(
                deliveryReport,
                message.getStringProperty(0x1001), // PidTagReportText
                message.getStringProperty(0x6820), // PidTagReportingMessageTransferAgent
                finalRecipient,
                action,
                status,
                diagnosticCode,
                message.getStringProperty(0x1046), // PidTagOriginalMessageId of the original
                dispositionType);
        var report = ReportGenerator.generate(info);
        serializer.setRawEntity(report.contentType(), null, null, report.body().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * Flattens a property value into its non-blank string elements: a multi-valued string property
     * (e.g. PidNameKeywords) arrives as a {@code List}, a single-valued one as a bare {@code String}.
     */
    private static List<String> collectStrings(Object value) {
        var strings = new ArrayList<String>();
        if (value instanceof List<?> values) {
            for (var element : values) {
                if (element instanceof String text && !text.isBlank()) {
                    strings.add(text);
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            strings.add(text);
        }
        return strings;
    }

    /**
     * The address that actually failed (rfc3464 §2.3.2): the recipient-table entry of the report
     * message holding the failed recipient — preferring a To-type row, then the first row with any
     * resolvable address — not the bounce's own PidTagDisplayTo. Returns {@code null} when the report
     * stores no recipient table or no resolvable address. Mirrors the MSG path's reportFailedRecipient.
     */
    private static String reportFailedRecipient(Message message) {
        String firstWithAddress = null;
        for (var recipient : message.getRecipients()) {
            if (recipient.email == null || recipient.email.isBlank()) {
                continue;
            }
            // Mask PR_RECIPIENT_TYPE to its class bits ([MS-OXOMSG] §2.2.3.1) before the To compare,
            // matching the To/Cc/Bcc split and the MSG path: a flag-stamped To row (e.g. 0x10000001 on a
            // resent/saved bounce) otherwise misses and the first non-To address is reported instead.
            if ((recipient.type & 0x0FFFFFFF) == EmlSerializer.RECIPIENT_TYPE_TO) {
                return recipient.email;
            }
            if (firstWithAddress == null) {
                firstWithAddress = recipient.email;
            }
        }
        return firstWithAddress;
    }

    /**
     * Hoists the stored S/MIME envelope of an {@code IPM.Note.SMIME*}/{@code IPM.Note.Secure*} message
     * to the top level via the shared {@link SmimeEntityHoist} (POI-free, also used by the MSG path).
     * Returns {@code false} — and the caller falls back to the regular re-encode — when the message
     * does not consist of exactly one data-bearing attachment holding the envelope.
     */
    private static boolean hoistSmimeEntity(Message message, EmlSerializer serializer, ConversionLog log) {
        var attachments = message.getAttachments();
        if (attachments.size() != 1) {
            return false;
        }
        var attachment = attachments.get(0);
        if (attachment.getAttachMethod() == ATTACH_EMBEDDED_MSG) {
            return false;
        }
        byte[] data = attachment.getData();
        if (data == null || data.length == 0) {
            return false;
        }
        String filename = attachment.getLongFilename();
        if (filename.isEmpty()) {
            filename = attachment.getFilename();
        }
        var entity = SmimeEntityHoist.hoist(data, filename, attachment.getMimeTag());
        serializer.setRawEntity(entity.contentType(), entity.transferEncoding(), entity.disposition(), entity.body());
        if (entity.fromMimeHeaders()) {
            log.info("S/MIME message " + message.getNid() + ": hoisted the stored MIME entity (" + entity.contentType()
                    + ")");
        } else {
            log.info("S/MIME message " + message.getNid() + ": exported the stored PKCS#7 envelope as the"
                    + " message body (" + entity.contentType() + ")");
        }
        return true;
    }

    /**
     * The iTIP method (RFC 5546 §3.4) for a task message class: {@code IPM.TaskRequest} is a
     * {@code REQUEST}, its {@code .Accept}/{@code .Decline}/{@code .Update} responses are
     * {@code REPLY}s, and a plain {@code IPM.Task} is {@code PUBLISH}ed. Distinguishing
     * {@code IPM.TaskRequest*} from {@code IPM.Task} is what stops a task request — which has no dot
     * after {@code Task} — from being swallowed by a naive {@code startsWith("IPM.Task")}.
     */
    private static String taskMethod(String messageClass) {
        if (messageClass.startsWith("IPM.TaskRequest.Accept")
                || messageClass.startsWith("IPM.TaskRequest.Decline")
                || messageClass.startsWith("IPM.TaskRequest.Update")) {
            return "REPLY";
        }
        if (messageClass.startsWith("IPM.TaskRequest")) {
            return "REQUEST";
        }
        return "PUBLISH";
    }

    /**
     * Whether a message class produced a specialized artifact above (calendar invite, vCard, VTODO,
     * distribution-list body, multipart/report or hoisted S/MIME) or is a plain note/post. Anything
     * else still exported a generic EML, which the caller logs as a downgrade.
     */
    private static boolean hasSpecializedHandler(String messageClass) {
        return messageClass.equals("IPM")
                || messageClass.startsWith("IPM.Note")
                || messageClass.startsWith("IPM.Post")
                || messageClass.startsWith("IPM.Appointment")
                || messageClass.startsWith("IPM.Schedule.Meeting")
                || messageClass.startsWith("IPM.Contact")
                || messageClass.startsWith("IPM.Task")
                || messageClass.startsWith("IPM.DistList")
                || messageClass.startsWith("REPORT.");
    }

    private static void writeSerializerAtomically(EmlSerializer serializer, Path emlFile) throws IOException {
        var tempFile = emlFile.resolveSibling(emlFile.getFileName() + ".part");
        var written = false;
        try {
            // Write through an OutputStream (not a Writer) so a hoisted clear-signed S/MIME entity's
            // 8-bit body is emitted byte-for-byte rather than re-encoded by the UTF-8 char writer.
            try (var outputStream = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                serializer.writeTo(outputStream);
            }
            written = true;
            try {
                Files.move(tempFile, emlFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Some filesystems (e.g. certain network mounts) cannot rename atomically. A plain
                // REPLACE_EXISTING move is the best available there, but it is not crash-safe: a power loss
                // mid-replace can lose both the prior export and the new bytes. The fully written .part is
                // kept on failure (see the catch below) so the converted message stays recoverable.
                Files.move(tempFile, emlFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            // Clean up only a partially written .part. A .part that was written in full but whose final
            // rename failed (e.g. the non-atomic fallback above) is deliberately left on disk so the
            // converted message can be recovered by renaming it, rather than silently lost.
            if (!written) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
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
     * Whether a message with the given {@code PidTagMessageClass} should be exported. Intentionally
     * permits a {@code null}, empty or literal {@code "IPM"} class: [MS-OXCMSG] §2.2.1.3 defines a
     * missing message class — and the "no form found" {@code IPM} class — as the generic note, so such
     * an item is treated as a plain email (best-effort fidelity) rather than silently dropped.
     * Everything else must match an allowed prefix.
     */
    static boolean isAllowedMessageClass(String messageClass, boolean exportNonMailItems) {
        if (messageClass == null || messageClass.isEmpty() || messageClass.equals("IPM")) {
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

    /**
     * The next free {@code <subject>_<nid>[_N].eml} path under {@code folderDir} for a SUFFIX_COUNTER
     * duplicate. Shares the per-base counter map with {@link #uniqueDirectory} (keys are disjoint — those
     * are directories, these are {@code .eml} files) so repeated collisions on one base resume the
     * {@code _N} probe from the last value used instead of re-probing from {@code _1}, turning O(K^2)
     * filesystem stats into O(K). The produced {@code _1, _2, …} naming is unchanged.
     */
    private static Path nextFreeEmlFile(
            Path folderDir, String safeSubject, String nidPart, Map<Path, Integer> counters) {
        var base = folderDir.resolve(boundedEmlFileName(folderDir, safeSubject, nidPart));
        if (!counters.containsKey(base) && !Files.exists(base)) {
            counters.put(base, 0);
            return base;
        }
        var next = counters.getOrDefault(base, 0);
        Path candidate;
        do {
            next++;
            candidate = folderDir.resolve(boundedEmlFileName(folderDir, safeSubject, nidPart + "_" + next));
        } while (counters.containsKey(candidate) || Files.exists(candidate));
        counters.put(base, next);
        counters.put(candidate, 0);
        return candidate;
    }

    static String describeFailure(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
