package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.Phase;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpCredentialStore;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfiles;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "Send via SMTP…" dialog. Accepts one or more {@code .eml} files; the envelope (From / To)
 * is entered once and shared by every message, while each file's content goes out unchanged as
 * DATA. After Send is pressed the dialog stays open: the file table shows live per-message
 * progress, the Cancel button becomes "Cancel Remaining" while the batch runs and "Close" once it
 * finishes. The dialog's Cancel button is intentionally the default-focused control; the test in
 * {@code SendDialogTest} asserts this.
 */
public final class SendDialog extends DialogWrapper {

    /**
     * Upper bound on how many leading bytes {@link #parseEmlHeaders()} reads to pull the From /
     * Subject preview. Headers precede the body, so a bounded prefix is enough — and because this
     * runs on the EDT during dialog construction, we must never pull a whole multi-megabyte {@code
     * .eml} into memory just to read its top few lines.
     */
    private static final int MAX_HEADER_SCAN_BYTES = 64 * 1024;

    /** What the batch does with the remaining messages after one of them fails. */
    public enum FailurePolicy {
        CONTINUE_ON_FAILURE("Continue with remaining messages"),
        STOP_ON_FIRST_FAILURE("Stop at first failure");

        private final String label;

        FailurePolicy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum DialogState {
        IDLE,
        SENDING,
        DONE
    }

    private final Project project;
    private final List<VirtualFile> sourceFiles;
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
    private final JComboBox<FailurePolicy> failurePolicyPicker = new JComboBox<>(FailurePolicy.values());
    private final JPasswordField passwordField = new JPasswordField();
    private final JBCheckBox updateProfileBox = new JBCheckBox();
    private final FileStatusTableModel statusTableModel;

    private DialogState state = DialogState.IDLE;
    private BatchSendController controller;
    private SendRequest committedRequest;

    public SendDialog(@Nullable Project project, @Nullable VirtualFile sourceFile) {
        this(project, sourceFile == null ? List.of() : List.of(sourceFile));
    }

    public SendDialog(@Nullable Project project, List<VirtualFile> sourceFiles) {
        super(project);
        this.project = project;
        this.sourceFiles = List.copyOf(sourceFiles);
        this.profileService = SmtpProfileService.getInstance();
        this.credentialStore = new SmtpCredentialStore();
        this.statusTableModel = new FileStatusTableModel(buildFileLabels());
        setTitle(this.sourceFiles.size() > 1 ? "Send " + this.sourceFiles.size() + " EML Files" : "Send EML");
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
        failurePolicyPicker.setSelectedItem(FailurePolicy.CONTINUE_ON_FAILURE);
        sourceSummaryLabel.setText(formatSourceSummary());
        subjectLabel.setText("");
        fromHeaderLabel.setText("");
        onProfilePicked();
        if (sourceFiles.size() == 1) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                var defaults = parseEmlHeaders();
                // any(): a pure label update that must also run while this dialog is modal.
                ApplicationManager.getApplication()
                        .invokeLater(
                                () -> {
                                    subjectLabel.setText(defaults.subject);
                                    fromHeaderLabel.setText(defaults.from);
                                },
                                ModalityState.any());
            });
        }
        profilePicker.addActionListener(event -> onProfilePicked());

        var refreshOnEdit = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                refreshUpdateProfileBox();
            }
        };
        hostField.getDocument().addDocumentListener(refreshOnEdit);
        envelopeFromField.getDocument().addDocumentListener(refreshOnEdit);
        envelopeToField.getDocument().addDocumentListener(refreshOnEdit);
        portSpinner.addChangeListener(event -> refreshUpdateProfileBox());

        var formBuilder = FormBuilder.createFormBuilder()
                .addLabeledComponent("Source:", sourceSummaryLabel)
                .addLabeledComponent("Profile:", profilePicker)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portSpinner)
                .addSeparator()
                .addLabeledComponent("Envelope From:", envelopeFromField)
                .addLabeledComponent("Envelope To:", envelopeToField)
                .addComponentToRightColumn(updateProfileBox)
                .addSeparator();
        if (sourceFiles.size() == 1) {
            formBuilder
                    .addLabeledComponent("Message Subject:", subjectLabel)
                    .addLabeledComponent("Message From:", fromHeaderLabel)
                    .addSeparator();
        }
        var form = formBuilder
                .addLabeledComponent("On failure:", failurePolicyPicker)
                .addLabeledComponent("Stop after phase:", stopAfterPicker)
                .addLabeledComponent("Password (one-time):", passwordField)
                .getPanel();

        var statusTable = new JBTable(statusTableModel);
        statusTable.setShowGrid(false);
        statusTable.getColumnModel().getColumn(1).setMaxWidth(100);

        var wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setPreferredSize(new Dimension(620, 480));
        wrapper.add(form, BorderLayout.NORTH);
        wrapper.add(new JBScrollPane(statusTable), BorderLayout.CENTER);
        return wrapper;
    }

    @Override
    protected void doOKAction() {
        if (state != DialogState.IDLE) {
            return;
        }
        try {
            var request = buildSendRequest();
            if (!confirmInsecureTransport(request.config())) {
                return;
            }
            persistOverridesToProfileIfRequested();
            committedRequest = request;
            startBatch(request);
        } catch (ConfigurationException failure) {
            Messages.showErrorDialog(getContentPanel(), failure.getLocalizedMessage(), "Invalid Send Request");
        }
    }

    @Override
    public void doCancelAction() {
        switch (state) {
            case IDLE, DONE -> super.doCancelAction();
            case SENDING -> {
                // First click only asks the batch to stop; the button is re-enabled as "Close"
                // when batchFinished arrives. Never dispose the dialog while a send is in flight.
                if (controller != null) {
                    controller.requestCancel();
                }
                getCancelAction().setEnabled(false);
            }
        }
    }

    private void startBatch(SendRequest request) {
        state = DialogState.SENDING;
        setInputsEnabled(false);
        getOKAction().setEnabled(false);
        setOKButtonText("Sending…");
        setCancelButtonText("Cancel Remaining");
        controller = new BatchSendController(project);
        // Default-modality runnables queue behind this modal dialog and would never run while it
        // is open — callbacks must be scheduled in the dialog's own modality state.
        var modality = ModalityState.stateForComponent(getContentPanel());
        controller.start(request, new BatchSendController.BatchListener() {
            @Override
            public void fileStarted(int index) {
                onEdt(() -> statusTableModel.setStatus(index, BatchSendController.FileStatus.SENDING, ""));
            }

            @Override
            public void fileFinished(int index, BatchSendController.FileStatus status, String detail) {
                onEdt(() -> statusTableModel.setStatus(index, status, detail));
            }

            @Override
            public void batchFinished(int sent, int failed, int skipped, boolean cancelled) {
                onEdt(SendDialog.this::onBatchFinished);
            }

            private void onEdt(Runnable update) {
                ApplicationManager.getApplication()
                        .invokeLater(
                                () -> {
                                    if (!isDisposed()) {
                                        update.run();
                                    }
                                },
                                modality);
            }
        });
    }

    private void onBatchFinished() {
        state = DialogState.DONE;
        setCancelButtonText("Close");
        getCancelAction().setEnabled(true);
    }

    private void setInputsEnabled(boolean enabled) {
        profilePicker.setEnabled(enabled);
        envelopeFromField.setEnabled(enabled);
        envelopeToField.setEnabled(enabled);
        hostField.setEnabled(enabled);
        portSpinner.setEnabled(enabled);
        stopAfterPicker.setEnabled(enabled);
        failurePolicyPicker.setEnabled(enabled);
        passwordField.setEnabled(enabled);
        updateProfileBox.setEnabled(enabled);
    }

    /**
     * Warns before sending over a channel that is not guaranteed-encrypted — TLS mode NONE
     * (cleartext) or an OPTIONAL STARTTLS mode that can be stripped to cleartext. Returns
     * {@code true} when the send should proceed. Asked once per batch, before the first message.
     */
    private boolean confirmInsecureTransport(SmtpConfig config) {
        if (config.tls().guaranteesEncryption()) {
            return true;
        }
        var detail = config.tls().mode() == TlsConfig.Mode.NONE
                ? "without TLS — the message and envelope cross the network in cleartext"
                : "with optional STARTTLS, which an active attacker can strip to force cleartext";
        var answer = Messages.showYesNoDialog(
                project,
                "This profile will connect to " + config.host() + ":" + config.port() + " " + detail
                        + ".\n\nSend anyway?",
                "Insecure SMTP Transport",
                "Send Anyway",
                "Cancel",
                Messages.getWarningIcon());
        return answer == Messages.YES;
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
        refreshUpdateProfileBox();
    }

    /**
     * Shows the "Update profile … with these values" checkbox only while the editable
     * profile-sourced fields (host, port, envelope From/To) diverge from the selected profile.
     * While the fields match, the box hides and unchecks, so saving is always a per-send opt-in.
     */
    private void refreshUpdateProfileBox() {
        var selected = (SmtpProfile) profilePicker.getSelectedItem();
        if (selected == null) {
            updateProfileBox.setSelected(false);
            updateProfileBox.setVisible(false);
            return;
        }
        var dirty = !hostField.getText().trim().equals(Objects.requireNonNullElse(selected.host, ""))
                || (Integer) portSpinner.getValue() != selected.port
                || !envelopeFromField.getText().trim().equals(selected.findDefaultHeaderValue("From"))
                || !envelopeToField.getText().trim().equals(selected.findDefaultHeaderValue("To"));
        if (!dirty) {
            updateProfileBox.setSelected(false);
        }
        var displayName = selected.name == null || selected.name.isBlank() ? "(unnamed)" : selected.name;
        updateProfileBox.setText("Update profile \"" + displayName + "\" with these values");
        updateProfileBox.setVisible(dirty);
    }

    /**
     * Writes the overridden host/port/From/To back to the selected profile when the user ticked
     * the update checkbox. Runs only after {@link #buildSendRequest()} validated the values. The
     * one-time password is never part of the save. After the upsert the picker is repopulated so
     * the in-dialog profile matches what was persisted.
     */
    private void persistOverridesToProfileIfRequested() {
        if (!updateProfileBox.isVisible() || !updateProfileBox.isSelected()) {
            return;
        }
        var selected = (SmtpProfile) profilePicker.getSelectedItem();
        if (selected == null) {
            return;
        }
        var updated = selected.copy();
        updated.host = hostField.getText().trim();
        updated.port = (Integer) portSpinner.getValue();
        updated.setDefaultHeaderValue("From", envelopeFromField.getText().trim());
        updated.setDefaultHeaderValue("To", envelopeToField.getText().trim());
        profileService.upsert(updated);
        populateProfilePicker();
        for (var index = 0; index < profilePicker.getItemCount(); index++) {
            if (Objects.equals(profilePicker.getItemAt(index).identifier, updated.identifier)) {
                profilePicker.setSelectedIndex(index);
                break;
            }
        }
    }

    SendRequest buildSendRequest() throws ConfigurationException {
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
        SmtpEnvelope envelope;
        try {
            envelope = SmtpEnvelope.of(from, recipients.toArray(new String[0]));
        } catch (IllegalArgumentException invalid) {
            throw new ConfigurationException(invalid.getMessage());
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

        // The "Password (one-time)" field is exactly that: used for this batch only, never
        // persisted to PasswordSafe. Override the auth credentials with a transient supplier
        // instead. The supplier clones the array per send, so it serves every message in the
        // batch.
        var oneTimePassword = passwordField.getPassword();
        if (oneTimePassword.length > 0) {
            config = applyOneTimePassword(config, oneTimePassword);
        }
        var failurePolicy = (FailurePolicy) failurePolicyPicker.getSelectedItem();
        return new SendRequest(
                config,
                envelope,
                sourceFiles,
                failurePolicy == null ? FailurePolicy.CONTINUE_ON_FAILURE : failurePolicy);
    }

    /**
     * Returns a copy of {@code config} whose AUTH password is supplied by the typed one-time value
     * instead of whatever is stored in {@code PasswordSafe}. The override is transient — it lives
     * only inside the returned {@link SmtpConfig}, so the batch uses it and nothing is written
     * back to the credential store. When auth is disabled the password has no destination and the
     * config is returned unchanged.
     */
    private static SmtpConfig applyOneTimePassword(SmtpConfig config, char[] oneTimePassword) {
        var auth = config.auth();
        if (auth.isDisabled()) {
            return config;
        }
        var current = auth.credentials();
        var transientCredentials = new AuthCredentials(
                current.username(), () -> oneTimePassword.clone(), current.authzId(), current.authExtra());
        var transientAuth = new AuthConfig(
                auth.mechanism(),
                transientCredentials,
                auth.authMap(),
                auth.allowPlaintextAuth(),
                auth.optional(),
                auth.optionalStrict());
        return config.withAuth(transientAuth);
    }

    private DefaultsFromHeaders parseEmlHeaders() {
        var defaults = new DefaultsFromHeaders();
        if (sourceFiles.size() != 1) {
            return defaults;
        }
        var sourceFile = sourceFiles.get(0);
        try {
            byte[] bytes;
            try (var input = sourceFile.getInputStream()) {
                bytes = input.readNBytes(MAX_HEADER_SCAN_BYTES);
            }
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
                var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
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

    private String formatSourceSummary() {
        if (sourceFiles.isEmpty()) {
            return "(no file — composing from envelope only)";
        }
        if (sourceFiles.size() == 1) {
            return formatSource(sourceFiles.get(0));
        }
        var totalBytes = 0L;
        for (var file : sourceFiles) {
            totalBytes += file.getLength();
        }
        return sourceFiles.size() + " files · " + formatSize(totalBytes);
    }

    private List<String> buildFileLabels() {
        if (sourceFiles.isEmpty()) {
            return List.of("(envelope only)");
        }
        var labels = new ArrayList<String>(sourceFiles.size());
        for (var file : sourceFiles) {
            labels.add(projectRelativePath(file));
        }
        return labels;
    }

    private String projectRelativePath(VirtualFile file) {
        var base = project == null ? null : ProjectUtil.guessProjectDir(project);
        var relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
        return relative != null ? relative : file.getPath();
    }

    private String formatSource(VirtualFile file) {
        return projectRelativePath(file) + " · " + formatSize(file.getLength());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public record SendRequest(
            SmtpConfig config, SmtpEnvelope envelope, List<VirtualFile> sourceFiles, FailurePolicy failurePolicy) {
        public List<String> recipients() {
            var addresses = new ArrayList<String>(envelope.recipients().size());
            for (var rcpt : envelope.recipients()) {
                addresses.add(rcpt.address());
            }
            return addresses;
        }
    }

    /** Per-file progress rows backing the dialog's status table. */
    static final class FileStatusTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"File", "Status", "Detail"};

        private final List<String> fileLabels;
        private final List<BatchSendController.FileStatus> statuses;
        private final List<String> details;

        FileStatusTableModel(List<String> fileLabels) {
            this.fileLabels = fileLabels;
            this.statuses = new ArrayList<>(fileLabels.size());
            this.details = new ArrayList<>(fileLabels.size());
            for (var ignored : fileLabels) {
                statuses.add(BatchSendController.FileStatus.PENDING);
                details.add("");
            }
        }

        void setStatus(int index, BatchSendController.FileStatus status, String detail) {
            statuses.set(index, status);
            details.set(index, detail == null ? "" : detail);
            fireTableRowsUpdated(index, index);
        }

        BatchSendController.FileStatus statusAt(int index) {
            return statuses.get(index);
        }

        @Override
        public int getRowCount() {
            return fileLabels.size();
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
            return switch (columnIndex) {
                case 0 -> fileLabels.get(rowIndex);
                case 1 -> statuses.get(rowIndex);
                case 2 -> details.get(rowIndex);
                default -> "";
            };
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

    // --- Test seams (package-private) ---

    void setEnvelopeForTest(String from, String toAddresses) {
        envelopeFromField.setText(from);
        envelopeToField.setText(toAddresses);
    }

    void setHostPortForTest(String host, int port) {
        hostField.setText(host);
        portSpinner.setValue(port);
    }

    JBCheckBox updateProfileBoxForTest() {
        return updateProfileBox;
    }

    void persistOverridesForTest() {
        persistOverridesToProfileIfRequested();
    }

    void setOneTimePasswordForTest(String password) {
        passwordField.setText(password);
    }

    SmtpCredentialStore credentialStore() {
        return credentialStore;
    }

    String messageFromLabelText() {
        return fromHeaderLabel.getText();
    }

    String messageSubjectLabelText() {
        return subjectLabel.getText();
    }

    FileStatusTableModel statusTableModel() {
        return statusTableModel;
    }

    FailurePolicy selectedFailurePolicy() {
        return (FailurePolicy) failurePolicyPicker.getSelectedItem();
    }

    boolean hasHeaderPreviewRows() {
        return sourceFiles.size() == 1;
    }

    private static JSpinner buildPortSpinner() {
        var spinner = new JSpinner(new SpinnerNumberModel(587, 1, 65535, 1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }
}
