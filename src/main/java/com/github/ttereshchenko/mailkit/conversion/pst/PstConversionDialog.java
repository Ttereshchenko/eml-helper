package com.github.ttereshchenko.mailkit.conversion.pst;

import com.github.ttereshchenko.mailkit.pst.Message;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ContextHelpLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nullable;

public class PstConversionDialog extends DialogWrapper {

    private final TextFieldWithBrowseButton targetDirectoryField;
    private final JComboBox<PstToEmlConverter.DuplicateHandling> duplicateHandlingCombo;
    private final JComboBox<Message.AddressPreference> addressPreferenceCombo;
    private final JBTextField messageCountLimitField;
    private final JBCheckBox useOriginalSmtpHeadersCheck;
    private final JBCheckBox skipEmptyFoldersCheck;
    private final JBCheckBox recoverDeletedItemsCheck;
    private final JBCheckBox scanOrphansCheck;
    private final JBTextField maxNodeSizeField;
    private final JPanel centerPanel;

    public PstConversionDialog(@Nullable Project project, VirtualFile source) {
        super(project, true);
        setTitle("Convert PST to EML");

        var targetPath = source.getParent().getPath() + "/" + source.getNameWithoutExtension() + "_eml";

        targetDirectoryField = new TextFieldWithBrowseButton();
        targetDirectoryField.setText(targetPath);
        var descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Target Directory");
        descriptor.setDescription("Choose the directory to extract EML files to");
        targetDirectoryField.addBrowseFolderListener(project, descriptor);

        duplicateHandlingCombo = new JComboBox<>(PstToEmlConverter.DuplicateHandling.values());
        duplicateHandlingCombo.setSelectedItem(PstToEmlConverter.DuplicateHandling.SUFFIX_COUNTER);

        addressPreferenceCombo = new JComboBox<>(Message.AddressPreference.values());
        addressPreferenceCombo.setSelectedItem(Message.AddressPreference.PREFER_SMTP);

        messageCountLimitField = new JBTextField();
        messageCountLimitField.getEmptyText().setText("Leave empty for no limit");

        useOriginalSmtpHeadersCheck = new JBCheckBox("Use original SMTP headers when available", true);
        skipEmptyFoldersCheck = new JBCheckBox("Skip empty folders", true);
        recoverDeletedItemsCheck = new JBCheckBox("Recover soft-deleted messages (Recoverable Items)", true);
        scanOrphansCheck = new JBCheckBox("Recover orphaned messages (deep NBT scan)", true);

        maxNodeSizeField = new JBTextField("64");
        maxNodeSizeField.getEmptyText().setText("Max size in MB (default 64)");

        var helpLabel = ContextHelpLabel.create("<b>Supported items:</b> Emails (IPM.Note), Reports (REPORT.*),<br>"
                + "Calendar/Meetings (IPM.Appointment, IPM.Schedule.Meeting.*).<br><br>"
                + "<i>Other types (Contacts, Tasks, Notes, etc.) are ignored.</i>");
        var helpPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        helpPanel.add(new com.intellij.ui.components.JBLabel("Which items are converted? "));
        helpPanel.add(helpLabel);

        var issueLink = new com.intellij.ui.components.ActionLink(
                "Missing a message type? Request it on GitHub", (java.awt.event.ActionListener) event ->
                        com.intellij.ide.BrowserUtil.browse("https://github.com/Ttereshchenko/mailkit/issues"));
        helpPanel.add(javax.swing.Box.createHorizontalStrut(15));
        helpPanel.add(issueLink);

        centerPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Target directory:", targetDirectoryField)
                .addLabeledComponent("Duplicate handling:", duplicateHandlingCombo)
                .addLabeledComponent("Address preference:", addressPreferenceCombo)
                .addLabeledComponent("Message-count limit:", messageCountLimitField)
                .addComponent(useOriginalSmtpHeadersCheck)
                .addComponent(skipEmptyFoldersCheck)
                .addComponent(recoverDeletedItemsCheck)
                .addComponent(scanOrphansCheck)
                .addLabeledComponent("Max single attachment size (MB):", maxNodeSizeField)
                .addComponent(helpPanel)
                .getPanel();

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return centerPanel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (targetDirectoryField.getText().trim().isEmpty()) {
            return new ValidationInfo("Choose a target directory", targetDirectoryField);
        }
        var limitText = messageCountLimitField.getText().trim();
        if (!limitText.isEmpty()) {
            try {
                if (Integer.parseInt(limitText) <= 0) {
                    return new ValidationInfo("Message-count limit must be a positive number", messageCountLimitField);
                }
            } catch (NumberFormatException ignored) {
                return new ValidationInfo("Message-count limit must be a whole number", messageCountLimitField);
            }
        }
        var sizeText = maxNodeSizeField.getText().trim();
        if (!sizeText.isEmpty()) {
            try {
                long megabytes = Long.parseLong(sizeText);
                if (megabytes <= 0) {
                    return new ValidationInfo("Max attachment size must be a positive number of MB", maxNodeSizeField);
                }
                if (megabytes > 4096) {
                    return new ValidationInfo("Max attachment size must be at most 4096 MB", maxNodeSizeField);
                }
            } catch (NumberFormatException ignored) {
                return new ValidationInfo("Max attachment size must be a whole number of MB", maxNodeSizeField);
            }
        }
        return null;
    }

    public Path getTargetDirectory() {
        return Paths.get(targetDirectoryField.getText());
    }

    public PstToEmlConverter.DuplicateHandling getDuplicateHandling() {
        return (PstToEmlConverter.DuplicateHandling) duplicateHandlingCombo.getSelectedItem();
    }

    public Message.AddressPreference getAddressPreference() {
        return (Message.AddressPreference) addressPreferenceCombo.getSelectedItem();
    }

    public Integer getMessageCountLimit() {
        var text = messageCountLimitField.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean useOriginalSmtpHeaders() {
        return useOriginalSmtpHeadersCheck.isSelected();
    }

    public boolean skipEmptyFolders() {
        return skipEmptyFoldersCheck.isSelected();
    }

    public boolean recoverDeletedItems() {
        return recoverDeletedItemsCheck.isSelected();
    }

    public boolean scanOrphans() {
        return scanOrphansCheck.isSelected();
    }

    public long getMaxNodeSize() {
        var text = maxNodeSizeField.getText().trim();
        if (text.isEmpty()) {
            return 64L * 1024 * 1024;
        }
        try {
            return Long.parseLong(text) * 1024 * 1024;
        } catch (NumberFormatException ignored) {
            return 64L * 1024 * 1024;
        }
    }
}
