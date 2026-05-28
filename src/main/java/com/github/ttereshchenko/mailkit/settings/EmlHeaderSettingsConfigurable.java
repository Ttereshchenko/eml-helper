package com.github.ttereshchenko.mailkit.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public final class EmlHeaderSettingsConfigurable implements Configurable {
    private static final List<String> SUGGESTIONS = List.of(
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
            "Content-Disposition");

    private JCheckBox highlightingEnabledCheckbox;
    private JCheckBox showAttachmentActionsCheckbox;
    private JBTable table;
    private HeaderTableModel tableModel;
    private JPanel tablePanel;
    private JComponent rootPanel;
    private final DaemonRestarter daemonRestarter;
    private final ColorSchemePageRefresher colorSchemeRefresher;
    private final HeaderNamePrompter prompter;

    @SuppressWarnings("unused")
    public EmlHeaderSettingsConfigurable() {
        this(DaemonRestarter.DEFAULT, ColorSchemePageRefresher.DEFAULT, HeaderNamePrompter.DEFAULT);
    }

    EmlHeaderSettingsConfigurable(DaemonRestarter daemonRestarter, ColorSchemePageRefresher colorSchemeRefresher) {
        this(daemonRestarter, colorSchemeRefresher, HeaderNamePrompter.DEFAULT);
    }

    EmlHeaderSettingsConfigurable(DaemonRestarter daemonRestarter, HeaderNamePrompter prompter) {
        this(daemonRestarter, ColorSchemePageRefresher.DEFAULT, prompter);
    }

    EmlHeaderSettingsConfigurable(
            DaemonRestarter daemonRestarter,
            ColorSchemePageRefresher colorSchemeRefresher,
            HeaderNamePrompter prompter) {
        this.daemonRestarter = daemonRestarter;
        this.colorSchemeRefresher = colorSchemeRefresher;
        this.prompter = prompter;
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
        tableModel.attachParent(table);

        var nameOnlyColumn = table.getColumnModel().getColumn(1);
        var header = table.getTableHeader();
        var headerWidth = header.getFontMetrics(header.getFont())
                        .stringWidth(nameOnlyColumn.getHeaderValue().toString())
                + 24;
        nameOnlyColumn.setMinWidth(headerWidth);
        nameOnlyColumn.setPreferredWidth(headerWidth);
        nameOnlyColumn.setMaxWidth(headerWidth + 40);

        var decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(action -> addHeader())
                .setRemoveAction(action -> removeHeader());

        tablePanel = new JPanel(new java.awt.BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Highlighted Headers"));
        tablePanel.add(decorator.createPanel(), java.awt.BorderLayout.CENTER);

        highlightingEnabledCheckbox = new JCheckBox("Enable highlighting", settings.isHighlightingEnabled());
        highlightingEnabledCheckbox.addActionListener(event -> updateTableEnabled());
        updateTableEnabled();

        showAttachmentActionsCheckbox =
                new JCheckBox("Show attachment save actions", settings.isShowAttachmentActions());

        var root = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("Headers"))
                .addComponent(highlightingEnabledCheckbox)
                .addComponent(tablePanel)
                .addComponent(new TitledSeparator("Attachments"))
                .addComponent(showAttachmentActionsCheckbox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        rootPanel = root;
        return root;
    }

    private void updateTableEnabled() {
        var enabled = highlightingEnabledCheckbox.isSelected();
        table.setEnabled(enabled);
        tablePanel.setEnabled(enabled);
    }

    void addHeader() {
        var result = prompter.prompt(table, SUGGESTIONS);
        if (result == null || result.isBlank()) {
            return;
        }

        var name = result.trim();
        if (!isValidHeaderName(table, name)) {
            return;
        }
        if (isDuplicateHeader(table, tableModel.entries, name, -1)) {
            return;
        }

        tableModel.entries.add(new HeaderEntry(name, true));
        tableModel.fireTableRowsInserted(tableModel.entries.size() - 1, tableModel.entries.size() - 1);
    }

    void removeHeader() {
        var row = table.getSelectedRow();
        if (row >= 0) {
            tableModel.entries.remove(row);
            tableModel.fireTableRowsDeleted(row, row);
        }
    }

    private static boolean isValidHeaderName(Component parent, String name) {
        if (EmlHeaderSettings.VALID_HEADER_NAME.matcher(name).matches()) {
            return true;
        }
        JOptionPane.showMessageDialog(
                parent,
                "Invalid header name. Only letters, digits, and hyphens are allowed.",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
        return false;
    }

    private static boolean isDuplicateHeader(Component parent, List<HeaderEntry> entries, String name, int ignoreRow) {
        for (var index = 0; index < entries.size(); index++) {
            if (index == ignoreRow) {
                continue;
            }
            var existing = entries.get(index);
            if (existing.name.equalsIgnoreCase(name)) {
                JOptionPane.showMessageDialog(
                        parent,
                        "Header \"" + existing.name + "\" already exists.",
                        "Duplicate Header",
                        JOptionPane.WARNING_MESSAGE);
                return true;
            }
        }
        return false;
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
                || showAttachmentActionsCheckbox.isSelected() != settings.isShowAttachmentActions()
                || !currentHeaders.equals(settings.getHighlightedHeaders())
                || !currentNameOnly.equals(settings.getNameOnlyHeaders());
    }

    @Override
    public void apply() {
        var settings = EmlHeaderSettings.getInstance();
        settings.setHighlightingEnabled(highlightingEnabledCheckbox.isSelected());
        settings.setShowAttachmentActions(showAttachmentActionsCheckbox.isSelected());
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
        colorSchemeRefresher.refresh(rootPanel);
    }

    @Override
    public void reset() {
        var settings = EmlHeaderSettings.getInstance();
        highlightingEnabledCheckbox.setSelected(settings.isHighlightingEnabled());
        showAttachmentActionsCheckbox.setSelected(settings.isShowAttachmentActions());
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
        private Component dialogParent;

        HeaderTableModel(List<HeaderEntry> entries) {
            this.entries = entries;
        }

        void attachParent(Component parent) {
            this.dialogParent = parent;
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
            return true;
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
                return;
            }
            if (column != 0 || value == null) {
                return;
            }
            var renamed = value.toString().trim();
            var current = entries.get(row).name;
            if (renamed.isEmpty() || renamed.equals(current)) {
                fireTableCellUpdated(row, column);
                return;
            }
            if (!isValidHeaderName(dialogParent, renamed)) {
                fireTableCellUpdated(row, column);
                return;
            }
            if (isDuplicateHeader(dialogParent, entries, renamed, row)) {
                fireTableCellUpdated(row, column);
                return;
            }
            entries.get(row).name = renamed;
            fireTableCellUpdated(row, column);
        }
    }

    @Override
    public void disposeUIResources() {
        if (table != null && table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        // This Configurable instance is held by the long-lived application-level registry; null the
        // Swing references so the closed dialog's component tree is released. createComponent rebuilds
        // them all on reopen.
        table = null;
        tableModel = null;
        tablePanel = null;
        rootPanel = null;
        highlightingEnabledCheckbox = null;
        showAttachmentActionsCheckbox = null;
    }

    javax.swing.table.TableModel getTableModel() {
        return tableModel;
    }
}
