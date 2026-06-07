package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpCredentialStore;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.Nullable;

/**
 * Settings page at {@code Settings → Tools → MailKit → SMTP}. Surfaces the global egress toggle
 * and a CRUD list of profiles. Per-row edits open {@link SmtpProfileEditorDialog}; passwords are
 * captured in that dialog and round-trip through {@link SmtpCredentialStore} (never the profile
 * state).
 */
public final class SmtpSettingsConfigurable implements Configurable {

    private JPanel rootPanel;
    private JCheckBox egressEnabled;
    private SmtpProfileTableModel tableModel;
    private JBTable profileTable;
    private final SmtpProfileService service = SmtpProfileService.getInstance();
    private final SmtpCredentialStore credentials = new SmtpCredentialStore();
    private List<SmtpProfile> editedProfiles = new ArrayList<>();
    private boolean editedEgress;
    private JCheckBox showEditorToolbarButton;
    private boolean editedShowToolbarButton;

    @Override
    public String getDisplayName() {
        return "SMTP";
    }

    @Override
    public @Nullable JComponent createComponent() {
        editedProfiles = service.getProfiles();
        editedEgress = service.isEgressEnabled();
        editedShowToolbarButton = service.isShowEditorToolbarButton();

        egressEnabled = new JCheckBox("Enable SMTP egress", editedEgress);
        egressEnabled.setToolTipText("When off, all Send via SMTP… actions are hidden / disabled.");

        showEditorToolbarButton = new JCheckBox("Show 'Send EML...' button in editor toolbar", editedShowToolbarButton);
        showEditorToolbarButton.setToolTipText("Show a quick access button in the top right of the EML editor");

        tableModel = new SmtpProfileTableModel(editedProfiles);
        profileTable = new JBTable(tableModel);
        profileTable.getEmptyText().setText("No SMTP profiles configured.");

        var decorator = ToolbarDecorator.createDecorator(profileTable)
                .setAddAction(button -> onAdd())
                .setEditAction(button -> onEdit())
                .setRemoveAction(button -> onRemove());
        var tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(decorator.createPanel(), BorderLayout.CENTER);
        var setDefaultButton = new javax.swing.JButton("Set as default");
        setDefaultButton.addActionListener(event -> onSetDefault());
        var actions = new JPanel();
        actions.add(setDefaultButton);
        tablePanel.add(actions, BorderLayout.SOUTH);
        tablePanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        var hint = new JLabel(
                "Passwords and TLS key passphrases are stored in IntelliJ's secure credential store, never in this XML.");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        rootPanel = FormBuilder.createFormBuilder()
                .addComponent(egressEnabled)
                .addComponent(showEditorToolbarButton)
                .addComponent(tablePanel)
                .addComponent(hint)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return rootPanel;
    }

    @Override
    public boolean isModified() {
        if (rootPanel == null) {
            return false;
        }
        if (egressEnabled.isSelected() != service.isEgressEnabled()) {
            return true;
        }
        if (showEditorToolbarButton.isSelected() != service.isShowEditorToolbarButton()) {
            return true;
        }
        var current = service.getProfiles();
        if (current.size() != editedProfiles.size()) {
            return true;
        }
        for (var index = 0; index < current.size(); index++) {
            if (!profileEquals(current.get(index), editedProfiles.get(index))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() {
        service.setEgressEnabled(egressEnabled.isSelected());
        service.setShowEditorToolbarButton(showEditorToolbarButton.isSelected());
        service.setProfiles(editedProfiles);
        editedEgress = egressEnabled.isSelected();
        editedShowToolbarButton = showEditorToolbarButton.isSelected();
    }

    @Override
    public void reset() {
        editedProfiles = service.getProfiles();
        editedEgress = service.isEgressEnabled();
        editedShowToolbarButton = service.isShowEditorToolbarButton();
        if (egressEnabled != null) {
            egressEnabled.setSelected(editedEgress);
        }
        if (showEditorToolbarButton != null) {
            showEditorToolbarButton.setSelected(editedShowToolbarButton);
        }
        if (tableModel != null) {
            tableModel.replaceAll(editedProfiles);
        }
    }

    @Override
    public void disposeUIResources() {
        rootPanel = null;
        egressEnabled = null;
        showEditorToolbarButton = null;
        profileTable = null;
        tableModel = null;
    }

    SmtpProfileTableModel tableModelForTests() {
        return tableModel;
    }

    JCheckBox egressCheckboxForTests() {
        return egressEnabled;
    }

    JCheckBox showEditorToolbarCheckboxForTests() {
        return showEditorToolbarButton;
    }

    List<SmtpProfile> editedProfilesForTests() {
        return editedProfiles;
    }

    private void onAdd() {
        var blank = new SmtpProfile();
        blank.identifier = UUID.randomUUID().toString();
        blank.name = "New SMTP profile";
        var dialog = new SmtpProfileEditorDialog(null, blank, credentials);
        if (dialog.showAndGet()) {
            editedProfiles.add(dialog.getProfile());
            tableModel.fireTableDataChanged();
        }
    }

    private void onEdit() {
        var row = profileTable.getSelectedRow();
        if (row < 0 || row >= editedProfiles.size()) {
            return;
        }
        var dialog = new SmtpProfileEditorDialog(null, editedProfiles.get(row).copy(), credentials);
        if (dialog.showAndGet()) {
            editedProfiles.set(row, dialog.getProfile());
            tableModel.fireTableDataChanged();
        }
    }

    private void onRemove() {
        var row = profileTable.getSelectedRow();
        if (row < 0 || row >= editedProfiles.size()) {
            return;
        }
        var profile = editedProfiles.get(row);
        var confirm = Messages.showYesNoDialog(
                rootPanel,
                "Delete profile \"" + profile.name + "\"? Stored credentials will be cleared.",
                "Delete SMTP Profile",
                Messages.getQuestionIcon());
        if (confirm != Messages.YES) {
            return;
        }
        credentials.forgetAll(profile.identifier);
        editedProfiles.remove(row);
        if (profile.isDefault && !editedProfiles.isEmpty()) {
            editedProfiles.get(0).isDefault = true;
        }
        tableModel.fireTableDataChanged();
    }

    private void onSetDefault() {
        var row = profileTable.getSelectedRow();
        if (row < 0 || row >= editedProfiles.size()) {
            return;
        }
        for (var index = 0; index < editedProfiles.size(); index++) {
            editedProfiles.get(index).isDefault = (index == row);
        }
        tableModel.fireTableDataChanged();
    }

    private static boolean profileEquals(SmtpProfile left, SmtpProfile right) {
        return left.identifier.equals(right.identifier)
                && left.name.equals(right.name)
                && left.host.equals(right.host)
                && left.port == right.port
                && left.protocol == right.protocol
                && left.tlsMode == right.tlsMode
                && left.authMechanism == right.authMechanism
                && left.username.equals(right.username)
                && left.allowPlaintextAuth == right.allowPlaintextAuth
                && left.isDefault == right.isDefault
                && defaultHeadersEqual(left.defaultHeaders, right.defaultHeaders);
    }

    private static boolean defaultHeadersEqual(
            List<SmtpProfile.DefaultHeader> left, List<SmtpProfile.DefaultHeader> right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (var index = 0; index < left.size(); index++) {
            var leftEntry = left.get(index);
            var rightEntry = right.get(index);
            if (!Objects.equals(leftEntry.name, rightEntry.name)
                    || !Objects.equals(leftEntry.value, rightEntry.value)) {
                return false;
            }
        }
        return true;
    }

    static final class SmtpProfileTableModel extends AbstractTableModel {
        private static final String[] COLUMN_NAMES = {"Name", "Host", "Port", "Default"};

        private List<SmtpProfile> profiles;

        SmtpProfileTableModel(List<SmtpProfile> profiles) {
            this.profiles = profiles;
        }

        void replaceAll(List<SmtpProfile> updated) {
            this.profiles = updated;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return profiles.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var profile = profiles.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> profile.name;
                case 1 -> profile.host;
                case 2 -> profile.port;
                case 3 -> profile.isDefault ? "✓" : "";
                default -> "";
            };
        }
    }
}
