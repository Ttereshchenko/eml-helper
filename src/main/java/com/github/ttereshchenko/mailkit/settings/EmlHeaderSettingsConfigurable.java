package com.github.ttereshchenko.mailkit.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public final class EmlHeaderSettingsConfigurable implements Configurable {
    private static final Pattern VALID_HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");

    private static final String[] SUGGESTIONS = {
        "From",
        "To",
        "Cc",
        "Bcc",
        "Subject",
        "Date",
        "Reply-To",
        "Sender",
        "Message-ID",
        "In-Reply-To",
        "References",
        "MIME-Version",
        "Content-Type",
        "Content-Transfer-Encoding",
        "Content-Disposition"
    };

    private JCheckBox highlightingEnabledCheckbox;
    private JBTable table;
    private HeaderTableModel tableModel;
    private JPanel tablePanel;
    private final DaemonRestarter daemonRestarter;

    @SuppressWarnings("unused")
    public EmlHeaderSettingsConfigurable() {
        this(DaemonRestarter.DEFAULT);
    }

    EmlHeaderSettingsConfigurable(DaemonRestarter daemonRestarter) {
        this.daemonRestarter = daemonRestarter;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MailKit";
    }

    @Override
    public @Nullable JComponent createComponent() {
        var settings = EmlHeaderSettings.getInstance();
        var entries = new ArrayList<HeaderEntry>();
        for (String header : settings.getHighlightedHeaders()) {
            entries.add(new HeaderEntry(header, settings.isNameOnly(header)));
        }

        tableModel = new HeaderTableModel(entries);
        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(1).setMaxWidth(80);
        table.getColumnModel().getColumn(1).setMinWidth(80);

        var decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(action -> addHeader())
                .setRemoveAction(action -> removeHeader());

        tablePanel = new JPanel(new java.awt.BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Highlighted Headers"));
        tablePanel.add(decorator.createPanel(), java.awt.BorderLayout.CENTER);

        highlightingEnabledCheckbox = new JCheckBox("Enable highlighting", settings.isHighlightingEnabled());
        highlightingEnabledCheckbox.addActionListener(event -> updateTableEnabled());
        updateTableEnabled();

        var root = new JPanel(new java.awt.BorderLayout());
        root.add(highlightingEnabledCheckbox, java.awt.BorderLayout.NORTH);
        root.add(tablePanel, java.awt.BorderLayout.CENTER);
        return root;
    }

    private void updateTableEnabled() {
        var enabled = highlightingEnabledCheckbox.isSelected();
        table.setEnabled(enabled);
        tablePanel.setEnabled(enabled);
    }

    private void addHeader() {
        var result = (String) JOptionPane.showInputDialog(
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

        var name = result.trim();
        if (!VALID_HEADER_NAME.matcher(name).matches()) {
            JOptionPane.showMessageDialog(
                    table,
                    "Invalid header name. Only letters, digits, and hyphens are allowed.",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        for (HeaderEntry entry : tableModel.entries) {
            if (entry.name.equalsIgnoreCase(name)) {
                JOptionPane.showMessageDialog(
                        table,
                        "Header \"" + entry.name + "\" already exists.",
                        "Duplicate Header",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        tableModel.entries.add(new HeaderEntry(name, true));
        tableModel.fireTableRowsInserted(tableModel.entries.size() - 1, tableModel.entries.size() - 1);
    }

    private void removeHeader() {
        var row = table.getSelectedRow();
        if (row >= 0) {
            tableModel.entries.remove(row);
            tableModel.fireTableRowsDeleted(row, row);
        }
    }

    @Override
    public boolean isModified() {
        var settings = EmlHeaderSettings.getInstance();
        var currentHeaders = new ArrayList<String>();
        var currentNameOnly = new ArrayList<String>();
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
        var settings = EmlHeaderSettings.getInstance();
        settings.setHighlightingEnabled(highlightingEnabledCheckbox.isSelected());
        var headers = new ArrayList<String>();
        var nameOnly = new ArrayList<String>();
        for (HeaderEntry entry : tableModel.entries) {
            headers.add(entry.name);
            if (entry.nameOnly) {
                nameOnly.add(entry.name);
            }
        }
        settings.setHighlightedHeaders(headers);
        settings.setNameOnlyHeaders(nameOnly);

        daemonRestarter.restart("EML settings changed");
    }

    @Override
    public void reset() {
        var settings = EmlHeaderSettings.getInstance();
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
            var entry = entries.get(row);
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
