package org.example.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class EmlHeaderSettingsConfigurable implements Configurable {
    private static final Pattern VALID_HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");

    private static final String[] SUGGESTIONS = {
            "From", "To", "Cc", "Bcc", "Subject", "Date",
            "Reply-To", "Sender", "Message-ID", "In-Reply-To", "References",
            "MIME-Version", "Content-Type", "Content-Transfer-Encoding", "Content-Disposition"
    };

    private JCheckBox highlightingEnabledCheckbox;
    private JBTable table;
    private HeaderTableModel tableModel;
    private JPanel tablePanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "EML";
    }

    @Override
    public @Nullable JComponent createComponent() {
        EmlHeaderSettings settings = EmlHeaderSettings.getInstance();
        List<HeaderEntry> entries = new ArrayList<>();
        for (String header : settings.getHighlightedHeaders()) {
            entries.add(new HeaderEntry(header, settings.isNameOnly(header)));
        }

        tableModel = new HeaderTableModel(entries);
        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(1).setMaxWidth(80);
        table.getColumnModel().getColumn(1).setMinWidth(80);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> addHeader())
                .setRemoveAction(button -> removeHeader());

        tablePanel = new JPanel(new java.awt.BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Highlighted Headers"));
        tablePanel.add(decorator.createPanel(), java.awt.BorderLayout.CENTER);

        highlightingEnabledCheckbox = new JCheckBox("Enable highlighting", settings.isHighlightingEnabled());
        highlightingEnabledCheckbox.addActionListener(e -> updateTableEnabled());
        updateTableEnabled();

        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(highlightingEnabledCheckbox, java.awt.BorderLayout.NORTH);
        root.add(tablePanel, java.awt.BorderLayout.CENTER);
        return root;
    }

    private void updateTableEnabled() {
        boolean enabled = highlightingEnabledCheckbox.isSelected();
        table.setEnabled(enabled);
        tablePanel.setEnabled(enabled);
    }

    private void addHeader() {
        String result = (String) JOptionPane.showInputDialog(
                table,
                "Enter header name (e.g., Content-Type):",
                "Add Header",
                JOptionPane.PLAIN_MESSAGE,
                null,
                SUGGESTIONS,
                SUGGESTIONS[0]);

        if (result == null || result.isBlank()) {
            return;
        }

        String name = result.trim();
        if (!VALID_HEADER_NAME.matcher(name).matches()) {
            JOptionPane.showMessageDialog(table,
                    "Invalid header name. Only letters, digits, and hyphens are allowed.",
                    "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        for (HeaderEntry entry : tableModel.entries) {
            if (entry.name.equalsIgnoreCase(name)) {
                JOptionPane.showMessageDialog(table,
                        "Header \"" + entry.name + "\" already exists.",
                        "Duplicate Header", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        tableModel.entries.add(new HeaderEntry(name, true));
        tableModel.fireTableRowsInserted(tableModel.entries.size() - 1, tableModel.entries.size() - 1);
    }

    private void removeHeader() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            tableModel.entries.remove(row);
            tableModel.fireTableRowsDeleted(row, row);
        }
    }

    @Override
    public boolean isModified() {
        EmlHeaderSettings settings = EmlHeaderSettings.getInstance();
        List<String> currentHeaders = new ArrayList<>();
        List<String> currentNameOnly = new ArrayList<>();
        for (HeaderEntry entry : tableModel.entries) {
            currentHeaders.add(entry.name);
            if (entry.nameOnly) {
                currentNameOnly.add(entry.name);
            }
        }
        return highlightingEnabledCheckbox.isSelected() != settings.isHighlightingEnabled()
                || !currentHeaders.equals(settings.getHighlightedHeaders())
                || !currentNameOnly.equals(settings.getNameOnlyHeaders());
    }

    @Override
    public void apply() {
        EmlHeaderSettings settings = EmlHeaderSettings.getInstance();
        settings.setHighlightingEnabled(highlightingEnabledCheckbox.isSelected());
        List<String> headers = new ArrayList<>();
        List<String> nameOnly = new ArrayList<>();
        for (HeaderEntry entry : tableModel.entries) {
            headers.add(entry.name);
            if (entry.nameOnly) {
                nameOnly.add(entry.name);
            }
        }
        settings.setHighlightedHeaders(headers);
        settings.setNameOnlyHeaders(nameOnly);

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            DaemonCodeAnalyzer.getInstance(project).restart();
        }
    }

    @Override
    public void reset() {
        EmlHeaderSettings settings = EmlHeaderSettings.getInstance();
        highlightingEnabledCheckbox.setSelected(settings.isHighlightingEnabled());
        updateTableEnabled();
        tableModel.entries.clear();
        for (String header : settings.getHighlightedHeaders()) {
            tableModel.entries.add(new HeaderEntry(header, settings.isNameOnly(header)));
        }
        tableModel.fireTableDataChanged();
    }

    private static final class HeaderEntry {
        String name;
        boolean nameOnly;

        HeaderEntry(String name, boolean nameOnly) {
            this.name = name;
            this.nameOnly = nameOnly;
        }
    }

    private static final class HeaderTableModel extends AbstractTableModel {
        private static final String[] COLUMN_NAMES = {"Header Name", "Name Only"};
        final List<HeaderEntry> entries;

        HeaderTableModel(List<HeaderEntry> entries) {
            this.entries = entries;
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }

        @Override
        public Object getValueAt(int row, int column) {
            HeaderEntry entry = entries.get(row);
            return column == 0 ? entry.name : entry.nameOnly;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 1) {
                entries.get(row).nameOnly = (Boolean) value;
                fireTableCellUpdated(row, column);
            }
        }
    }
}
