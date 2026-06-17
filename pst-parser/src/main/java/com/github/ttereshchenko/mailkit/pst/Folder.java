package com.github.ttereshchenko.mailkit.pst;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A folder within a PST/OST store: its display name, its message node ids (contents table) and its
 * sub-folders (hierarchy table).
 *
 * <p>Construction reads the folder's Property Context; a corrupt or missing folder node degrades to
 * an empty folder rather than failing, so callers can keep walking damaged stores —
 * {@link #isLoaded()} / {@link #getLoadError()} report whether that happened.
 *
 * <p>Instances are not thread-safe; confine each to a single thread.
 */
public class Folder {

    private static final System.Logger LOG = System.getLogger(Folder.class.getName());

    private final PstFile pstFile;
    private final int nid;
    private final NodeDatabase nodeDatabase;
    private PropertyContext propertyContext;
    private String displayName = "";
    private Exception loadError;

    /**
     * Wraps the folder with the given node id. The root folder of a store is node {@code 0x122}.
     *
     * @param pstFile the open store; must not be {@code null}
     * @param nid the folder's node id
     */
    public Folder(PstFile pstFile, int nid) {
        this.pstFile = Objects.requireNonNull(pstFile, "pstFile");
        this.nid = nid;
        this.nodeDatabase = pstFile.nodeDatabase();
        loadProperties();
    }

    private void loadProperties() {
        try {
            var node = nodeDatabase.getNode(nid);
            if (node == null) {
                loadError = new PstException("Folder node not found in NBT: " + nid);
                return;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            this.propertyContext = new PropertyContext(data, nodeDatabase, node);
            this.propertyContext.decodeString8(folderCharset());

            if (propertyContext.getProperty(MapiProperties.PR_DISPLAY_NAME_W) instanceof String name) {
                this.displayName = name;
            }
        } catch (Exception exception) {
            // Degrades gracefully (display name stays empty), but record and log so genuine
            // corruption is not hidden.
            loadError = exception;
            LOG.log(System.Logger.Level.DEBUG, () -> "Failed to load properties for folder node " + nid, exception);
        }
    }

    /**
     * The charset for this folder's PT_STRING8 properties (display name): the folder's own code-page
     * properties when present, defaulting to windows-1252 (folders rarely carry one).
     */
    private Charset folderCharset() {
        Object codePage = propertyContext.getProperty(MapiProperties.PR_MESSAGE_CODEPAGE);
        if (codePage == null) {
            codePage = propertyContext.getProperty(MapiProperties.PR_INTERNET_CPID);
        }
        return codePage instanceof Number number ? CodePages.charsetFor(number.intValue()) : CodePages.defaultCharset();
    }

    /** This folder's node id. */
    public int getNid() {
        return nid;
    }

    /** Whether the folder's properties were read successfully; see {@link #getLoadError()}. */
    public boolean isLoaded() {
        return loadError == null;
    }

    /**
     * The failure that prevented the folder's properties from loading, or {@code null} if loading
     * succeeded. A non-null value usually means the node id does not exist or the store is damaged.
     */
    public Exception getLoadError() {
        return loadError;
    }

    /**
     * The node ids of the messages in this folder's contents table; empty if the folder has none.
     * Construct a {@link Message} from each id to read it.
     *
     * @throws PstException if the contents table exists but cannot be parsed
     */
    public List<Integer> getMessages() throws PstException {
        List<Integer> messages = new ArrayList<>();
        int contentsNid = (nid & ~0x1F) | 0x0E; // NID_TYPE_CONTENTS_TABLE

        try {
            var node = nodeDatabase.getNode(contentsNid);
            if (node == null) {
                return messages;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            var tableContext = new TableContext(data, nodeDatabase, node);

            for (Map<Integer, Object> row : tableContext.getRows()) {
                // The RowID in the Contents table is the NID of the message
                if (row.get(MapiProperties.PidTagLtpRowId) instanceof Integer messageNid && messageNid != 0) {
                    messages.add(messageNid);
                }
            }
        } catch (PstException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PstException("Error reading folder messages", exception);
        }

        return messages;
    }

    /** The folder's display name, or an empty string if it has none (or failed to load). */
    public String getDisplayName() {
        return displayName;
    }

    public List<Folder> getSubFolders() throws PstException {
        List<Folder> subFolders = new ArrayList<>();
        int hierarchyNid = (nid & ~0x1F) | 0x0D; // NID_TYPE_HIERARCHY_TABLE

        try {
            var node = nodeDatabase.getNode(hierarchyNid);
            if (node == null) {
                return subFolders;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            var tableContext = new TableContext(data, nodeDatabase, node);

            var seen = new HashSet<Integer>();
            for (Map<Integer, Object> row : tableContext.getRows()) {
                // The RowID in the Hierarchy table is the NID of the subfolder.
                if (row.get(MapiProperties.PidTagLtpRowId) instanceof Integer rowNid) {
                    // A hierarchy table that lists this folder as its own child, or the same child twice,
                    // is corrupt: passing it on would make a depth-first walk recurse forever or duplicate
                    // work. The parser owns the folder graph, so drop the trivial self-loop and any
                    // duplicate child here; deeper A->B->A cycles are caught by the recursive caller, which
                    // tracks the folder NIDs already on its path.
                    if (rowNid == nid) {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                () -> "Folder node " + nid
                                        + " lists itself as a sub-folder; skipping the self-reference");
                        continue;
                    }
                    if (seen.add(rowNid)) {
                        subFolders.add(new Folder(pstFile, rowNid));
                    }
                }
            }
        } catch (PstException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PstException("Error reading subfolders", exception);
        }

        return subFolders;
    }
}
