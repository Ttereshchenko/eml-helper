package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditEntry;
import com.github.ttereshchenko.mailkit.smtp.audit.SmtpAuditLog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.table.JBTable;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.Nullable;

/**
 * Viewer over the persisted SMTP audit log. Read-only — gives the user a way to inspect what
 * has been sent without scraping the JSON file. "Clear" wipes the file; "Close" dismisses.
 */
public final class RecentSendsDialog extends DialogWrapper {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(java.time.ZoneId.systemDefault());

    private final Project project;
    private final SmtpAuditLog auditLog;
    private final RecentSendsTableModel tableModel;
    private final JBTable table;

    public RecentSendsDialog(@Nullable Project project) {
        super(project);
        this.project = project;
        this.auditLog = SmtpAuditLog.getInstance(project);
        // Start empty and load the log off the EDT — reading the audit JSON is blocking I/O that must
        // not stall the UI thread while the dialog opens.
        this.tableModel = new RecentSendsTableModel(List.of());
        this.table = new JBTable(tableModel);
        setTitle("Recent SMTP Sends");
        setOKButtonText("Close");
        setCancelButtonText("Clear log");
        init();
        getOKAction().putValue(DialogWrapper.DEFAULT_ACTION, Boolean.FALSE);
        reload();
    }

    @Override
    protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(720, 360));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        var refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> reload());
        var bottom = new JPanel();
        bottom.add(refreshButton);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /** Reads the audit log on a pooled thread, then replaces the table model back on the EDT. */
    private void reload() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var entries = auditLog.readAll();
            ApplicationManager.getApplication().invokeLater(() -> tableModel.replaceAll(entries), ModalityState.any());
        });
    }

    @Override
    public void doCancelAction() {
        var confirm = Messages.showYesNoDialog(
                getContentPanel(),
                "Delete all entries from the SMTP send log?",
                "Clear SMTP Log",
                Messages.getQuestionIcon());
        if (confirm == Messages.YES) {
            // Clear the view immediately; wipe the file off the EDT (clear() is blocking I/O).
            tableModel.replaceAll(List.of());
            ApplicationManager.getApplication().executeOnPooledThread(auditLog::clear);
        }
    }

    RecentSendsTableModel tableModelForTests() {
        return tableModel;
    }

    Project projectForTests() {
        return project;
    }

    static final class RecentSendsTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Timestamp", "Profile", "Host:Port", "Recipients", "Result", "Duration"
        };

        private List<SmtpAuditEntry> entries;

        RecentSendsTableModel(List<SmtpAuditEntry> entries) {
            this.entries = entries;
        }

        void replaceAll(List<SmtpAuditEntry> next) {
            this.entries = next;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            // Reverse order: newest first.
            var entry = entries.get(entries.size() - 1 - rowIndex);
            return switch (columnIndex) {
                case 0 -> TIMESTAMP_FORMAT.format(entry.timestamp());
                case 1 -> entry.profileName();
                case 2 -> entry.host() + ":" + entry.port();
                case 3 -> entry.recipients().size() + " (" + acceptedCount(entry) + " accepted)";
                case 4 -> entry.success() ? "OK" : (entry.errorKind() + " @ " + entry.errorPhase());
                case 5 -> entry.durationMillis() + " ms";
                default -> "";
            };
        }

        private static int acceptedCount(SmtpAuditEntry entry) {
            var count = 0;
            for (var recipient : entry.recipients()) {
                if (recipient.accepted()) {
                    count++;
                }
            }
            return count;
        }
    }
}
