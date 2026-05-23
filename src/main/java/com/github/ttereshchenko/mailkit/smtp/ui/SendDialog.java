package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.Phase;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpCredentialStore;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfiles;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.Nullable;

/**
 * The "Send via SMTP…" dialog. Basic view only in Phase 7 — Advanced tabs (Connection / Auth / TLS
 * / Headers / Protocol / XCLIENT / PROXY / DSN / Output) land in Phase 8 polish. The dialog's
 * Cancel button is intentionally the default-focused control; the test in
 * {@code SendDialogTest} asserts this.
 */
public final class SendDialog extends DialogWrapper {

    private final Project project;
    private final VirtualFile sourceFile;
    private final SmtpProfileService profileService;
    private final SmtpCredentialStore credentialStore;

    private final JComboBox<SmtpProfile> profilePicker = new JComboBox<>();
    private final JTextField envelopeFromField = new JTextField();
    private final JTextField envelopeToField = new JTextField();
    private final JTextField hostField = new JTextField();
    private final JSpinner portSpinner = buildPortSpinner();
    private final JLabel sourceSummaryLabel = new JLabel();
    private final JLabel subjectLabel = new JLabel();
    private final JLabel fromHeaderLabel = new JLabel();
    private final JComboBox<StopAfterChoice> stopAfterPicker = new JComboBox<>();
    private final JPasswordField passwordField = new JPasswordField();

    private SendRequest committedRequest;

    public SendDialog(@Nullable Project project, VirtualFile sourceFile) {
        super(project);
        this.project = project;
        this.sourceFile = sourceFile;
        this.profileService = SmtpProfileService.getInstance();
        this.credentialStore = new SmtpCredentialStore();
        setTitle("Send EML");
        setOKButtonText("Send");
        setCancelButtonText("Cancel");
        init();
        // Cancel-is-default-focus policy: clear the DEFAULT_ACTION flag on the OK (Send) action
        // so Enter does NOT trigger Send. SendDialogTest asserts this state.
        getOKAction().putValue(DialogWrapper.DEFAULT_ACTION, Boolean.FALSE);
    }

    @Override
    protected JComponent createCenterPanel() {
        profilePicker.setRenderer(SimpleListCellRenderer.create(
                "(unnamed)", profile -> profile.name == null || profile.name.isBlank() ? "(unnamed)" : profile.name));
        populateProfilePicker();
        populateStopAfterPicker();
        var defaults = parseEmlHeaders();
        sourceSummaryLabel.setText(
                sourceFile == null ? "(no file — composing from envelope only)" : formatSource(sourceFile));
        subjectLabel.setText(defaults.subject);
        fromHeaderLabel.setText(defaults.from);
        onProfilePicked();
        profilePicker.addActionListener(event -> onProfilePicked());

        var panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Source:", sourceSummaryLabel)
                .addLabeledComponent("Profile:", profilePicker)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portSpinner)
                .addSeparator()
                .addLabeledComponent("Envelope From:", envelopeFromField)
                .addLabeledComponent("Envelope To:", envelopeToField)
                .addSeparator()
                .addLabeledComponent("Message Subject:", subjectLabel)
                .addLabeledComponent("Message From:", fromHeaderLabel)
                .addSeparator()
                .addLabeledComponent("Stop after phase:", stopAfterPicker)
                .addLabeledComponent("Password (one-time):", passwordField)
                .getPanel();
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(620, 480));
        wrapper.add(panel, BorderLayout.CENTER);
        return wrapper;
    }

    @Override
    protected void doOKAction() {
        try {
            committedRequest = buildSendRequest();
            super.doOKAction();
        } catch (ConfigurationException failure) {
            Messages.showErrorDialog(getContentPanel(), failure.getLocalizedMessage(), "Invalid Send Request");
        }
    }

    public SendRequest getCommittedRequest() {
        return committedRequest;
    }

    public Project project() {
        return project;
    }

    /** Package-private — exposed for the Cancel-is-default-focus assertion in SendDialogTest. */
    boolean isOkActionMarkedAsDefault() {
        return Boolean.TRUE.equals(getOKAction().getValue(DialogWrapper.DEFAULT_ACTION));
    }

    private void populateProfilePicker() {
        var profiles = profileService.getProfiles();
        profilePicker.removeAllItems();
        for (var profile : profiles) {
            profilePicker.addItem(profile);
        }
        profileService.findDefault().ifPresent(profilePicker::setSelectedItem);
    }

    private void populateStopAfterPicker() {
        for (var choice : StopAfterChoice.values()) {
            stopAfterPicker.addItem(choice);
        }
        stopAfterPicker.setSelectedItem(StopAfterChoice.NONE);
    }

    private void onProfilePicked() {
        var selected = (SmtpProfile) profilePicker.getSelectedItem();
        if (selected == null) {
            envelopeFromField.setText("");
            envelopeToField.setText("");
            return;
        }
        hostField.setText(selected.host);
        portSpinner.setValue(selected.port);
        envelopeFromField.setText(selected.findDefaultHeaderValue("From"));
        envelopeToField.setText(selected.findDefaultHeaderValue("To"));
    }

    private SendRequest buildSendRequest() throws ConfigurationException {
        var selected = (SmtpProfile) profilePicker.getSelectedItem();
        if (selected == null) {
            throw new ConfigurationException("Pick a profile or add one in Settings → Tools → MailKit SMTP.");
        }
        var from = envelopeFromField.getText().trim();
        if (from.isEmpty()) {
            throw new ConfigurationException("Envelope From cannot be empty.");
        }
        var recipients = new ArrayList<String>();
        for (var raw : envelopeToField.getText().split(",")) {
            var trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                recipients.add(trimmed);
            }
        }
        if (recipients.isEmpty()) {
            throw new ConfigurationException("At least one recipient (To) is required.");
        }
        var envelope = SmtpEnvelope.of(from, recipients.toArray(new String[0]));

        var typedPassword = new String(passwordField.getPassword());
        if (!typedPassword.isEmpty()) {
            credentialStore.setPassword(selected.identifier, typedPassword);
        }

        var stopChoice = (StopAfterChoice) stopAfterPicker.getSelectedItem();
        var stopPhase = stopChoice == null ? null : stopChoice.phase;
        var hostOverride = hostField.getText().trim();
        var portOverride = (Integer) portSpinner.getValue();
        var config = SmtpProfiles.toConfig(selected, credentialStore).withStopAfter(stopPhase, false);
        if (!hostOverride.isEmpty() && !hostOverride.equals(selected.host)) {
            // Per-send override — we rebuild the host/port without persisting back to the profile.
            config = new SmtpConfig(
                    hostOverride,
                    portOverride,
                    config.ehloHost(),
                    config.protocol(),
                    config.timeout(),
                    config.stopAfter(),
                    config.dropAfter(),
                    config.tls(),
                    config.auth(),
                    config.esmtp(),
                    config.transport(),
                    config.proxy(),
                    config.xclient());
        } else if (portOverride != selected.port) {
            config = config.withPort(portOverride);
        }
        return new SendRequest(config, envelope, sourceFile);
    }

    private DefaultsFromHeaders parseEmlHeaders() {
        var defaults = new DefaultsFromHeaders();
        if (sourceFile == null) {
            return defaults;
        }
        try {
            var bytes = sourceFile.contentsToByteArray();
            var text = new String(bytes, StandardCharsets.UTF_8);
            var headerEnd = text.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                headerEnd = text.indexOf("\n\n");
            }
            if (headerEnd < 0) {
                headerEnd = text.length();
            }
            var headerBlock = text.substring(0, headerEnd);
            for (var line : Arrays.asList(headerBlock.split("\r?\n"))) {
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) {
                    continue;
                }
                var colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                var name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                var value = line.substring(colon + 1).trim();
                switch (name) {
                    case "from" -> defaults.from = stripAngles(value);
                    case "subject" -> defaults.subject = value;
                    default -> {
                        /* ignored */
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort — leave defaults blank
        }
        return defaults;
    }

    private static String stripAngles(String value) {
        var openAngle = value.indexOf('<');
        var closeAngle = value.indexOf('>');
        if (openAngle >= 0 && closeAngle > openAngle) {
            return value.substring(openAngle + 1, closeAngle);
        }
        return value;
    }

    private String formatSource(VirtualFile file) {
        var base = project == null ? null : ProjectUtil.guessProjectDir(project);
        var relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
        var path = relative != null ? relative : file.getPath();
        return path + " · " + formatSize(file.getLength());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public record SendRequest(SmtpConfig config, SmtpEnvelope envelope, VirtualFile sourceFile) {
        public List<String> recipients() {
            var addresses = new ArrayList<String>(envelope.recipients().size());
            for (var rcpt : envelope.recipients()) {
                addresses.add(rcpt.address());
            }
            return addresses;
        }

        public Path sourcePath() {
            return sourceFile == null ? null : Path.of(sourceFile.getPath());
        }
    }

    private static final class DefaultsFromHeaders {
        String from = "";
        String subject = "";
    }

    private enum StopAfterChoice {
        NONE(null, "— complete the send —"),
        BANNER(Phase.BANNER, "BANNER"),
        FIRST_HELO(Phase.FIRST_HELO, "FIRST_HELO"),
        STARTTLS(Phase.STARTTLS, "STARTTLS"),
        TLS(Phase.TLS, "TLS"),
        HELO(Phase.HELO, "HELO"),
        AUTH(Phase.AUTH, "AUTH"),
        MAIL(Phase.MAIL, "MAIL"),
        RCPT(Phase.RCPT, "RCPT"),
        DATA(Phase.DATA, "DATA"),
        DOT(Phase.DOT, "DOT");

        final Phase phase;
        final String label;

        StopAfterChoice(Phase phase, String label) {
            this.phase = phase;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static JSpinner buildPortSpinner() {
        var spinner = new JSpinner(new SpinnerNumberModel(587, 1, 65535, 1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }
}
