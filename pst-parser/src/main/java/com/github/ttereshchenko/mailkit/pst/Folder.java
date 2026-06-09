package com.github.ttereshchenko.mailkit.pst;

// TODO: re-visit log
// import com.intellij.openapi.diagnostic.Logger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a folder within a PST file.
 */
public class Folder {

    // TODO: re-visit log
    // private static final Logger LOG = Logger.getInstance(Folder.class);

    private final PstFile pstFile;
    private final int nid;
    private final NodeDatabase nodeDatabase;
    private PropertyContext propertyContext;
    private String displayName = "";

    public Folder(PstFile pstFile, int nid) {
        this.pstFile = pstFile;
        this.nid = nid;
        this.nodeDatabase = pstFile.nodeDatabase();
        loadProperties();
    }

    private void loadProperties() {
        try {
            var node = nodeDatabase.getNode(nid);
            if (node == null) return;

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            this.propertyContext = new PropertyContext(data, nodeDatabase, node);
            this.propertyContext.decodeString8(Charset.forName("windows-1252"));

            Object nameObj = propertyContext.getProperty(MapiProperties.PR_DISPLAY_NAME_W);
            if (nameObj instanceof String str) {
                this.displayName = str;
            }
        } catch (Exception exception) {
            // Degrades gracefully (display name falls back to Folder_<nid>), but log so genuine
            // corruption is not hidden.
            // TODO: re-visit log
            // LOG.debug("Failed to load properties for folder node " + nid, exception);
        }
    }

    public int getNid() {
        return nid;
    }

    public List<Integer> getMessages() throws PstException {
        List<Integer> messages = new ArrayList<>();
        int contentsNid = (nid & 0xFFFFFFE0) | 0x0E; // NID_TYPE_CONTENTS_TABLE

        try {
            var node = nodeDatabase.getNode(contentsNid);
            if (node == null) {
                return messages;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            TableContext tableContext = new TableContext(data, nodeDatabase, node);

            for (Map<Integer, Object> row : tableContext.getRows()) {
                // The RowID in the Contents table is the NID of the message
                Integer nidProp = (Integer) row.get(MapiProperties.PidTagLtpRowId);
                int messageNid = nidProp != null ? nidProp : 0;
                if (messageNid != 0) {
                    messages.add(messageNid);
                }
            }
        } catch (PstException exception) {
            throw exception;
        } catch (Exception exception) {
            // TODO: re-visit log
            // LOG.warn("Error reading folder messages", exception);
            throw new PstException("Error reading folder messages", exception);
        }

        return messages;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Folder> getSubFolders() throws PstException {
        List<Folder> subFolders = new ArrayList<>();
        int hierarchyNid = (nid & ~0x1F) | 0x0D;

        try {
            var node = nodeDatabase.getNode(hierarchyNid);
            if (node == null) {
                return subFolders;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            TableContext tableContext = new TableContext(data, nodeDatabase, node);

            for (Map<Integer, Object> row : tableContext.getRows()) {
                // The RowID in the Hierarchy table is the NID of the subfolder
                Object nidObj = row.get(MapiProperties.PidTagLtpRowId);
                if (nidObj instanceof Integer rowNid) {
                    subFolders.add(new Folder(pstFile, rowNid));
                }
            }
        } catch (PstException exception) {
            throw exception;
        } catch (Exception exception) {
            // TODO: re-visit log
            // LOG.warn("Error reading subfolders", exception);
            throw new PstException("Error reading subfolders", exception);
        }

        return subFolders;
    }

    // For later: getMessages()
}
