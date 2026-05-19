package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.MessageSource;
import com.github.ttereshchenko.mailkit.smtp.Phase;
import com.github.ttereshchenko.mailkit.smtp.SmtpClient;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpCredentialStore;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfiles;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.FormBuilder;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.Nullable;

/**
 * Edit a single {@link SmtpProfile}. Connection / Auth / TLS / Protocol live on tabs that mirror
 * the wire-client config surface. Passwords flow through {@link SmtpCredentialStore} only — the
 * password text field's value is never written to the profile JavaBean.
 */
public final class SmtpProfileEditorDialog extends DialogWrapper {

    private final SmtpProfile profile;
    private final SmtpCredentialStore credentials;
    private final JTextField nameField = new JTextField();
    private final JTextField hostField = new JTextField();
    private final JSpinner portSpinner = buildPortSpinner();
    private final JTextField ehloField = new JTextField();
    private final JComboBox<SmtpProfile.Protocol> protocolBox = new JComboBox<>(SmtpProfile.Protocol.values());
    private final JComboBox<SmtpProfile.TlsMode> tlsModeBox = new JComboBox<>(SmtpProfile.TlsMode.values());
    private final JCheckBox verifyCaBox = new JCheckBox("Verify CA chain");
    private final JCheckBox verifyHostnameBox = new JCheckBox("Verify hostname");
    private final JTextField caBundleField = new JTextField();
    private final JComboBox<SmtpProfile.AuthMechanismChoice> authMechBox =
            new JComboBox<>(SmtpProfile.AuthMechanismChoice.values());
    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox allowPlaintextAuthBox = new JCheckBox("Allow plaintext AUTH over non-TLS (dangerous)");
    private final JCheckBox usePipeliningBox = new JCheckBox("Use PIPELINING if advertised");
    private final JCheckBox useBdatBox = new JCheckBox("Use BDAT instead of DATA when CHUNKING advertised");
    private final JCheckBox usePrdrBox = new JCheckBox("Use PRDR if advertised");

    public SmtpProfileEditorDialog(@Nullable Project project, SmtpProfile profile, SmtpCredentialStore credentials) {
        super(project);
        this.profile = profile;
        this.credentials = credentials;
        setTitle("SMTP Profile");
        setOKButtonText("Save");
        setCancelButtonText("Cancel");
        init();
        getRootPane().setDefaultButton(null); // Cancel-is-default policy: Save is never default-focused.
        populateFromProfile();
    }

    @Override
    protected JComponent createCenterPanel() {
        var tabs = new JBTabbedPane();
        tabs.addTab("Connection", buildConnectionPanel());
        tabs.addTab("Auth", buildAuthPanel());
        tabs.addTab("TLS", buildTlsPanel());
        tabs.addTab("Protocol", buildProtocolPanel());
        var wrapper = new JPanel(new java.awt.BorderLayout());
        wrapper.setPreferredSize(new Dimension(560, 380));
        wrapper.add(tabs, java.awt.BorderLayout.CENTER);
        var testButton = new JButton("Test connection");
        testButton.addActionListener(event -> testConnection());
        var bottom = new JPanel();
        bottom.add(testButton);
        wrapper.add(bottom, java.awt.BorderLayout.SOUTH);
        return wrapper;
    }

    private JPanel buildConnectionPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Profile name:", nameField)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portSpinner)
                .addLabeledComponent("EHLO host:", ehloField)
                .addLabeledComponent("Protocol:", protocolBox)
                .getPanel();
    }

    private JPanel buildAuthPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Mechanism:", authMechBox)
                .addLabeledComponent("Username:", usernameField)
                .addLabeledComponent("Password:", passwordField)
                .addComponent(allowPlaintextAuthBox)
                .getPanel();
    }

    private JPanel buildTlsPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Mode:", tlsModeBox)
                .addComponent(verifyCaBox)
                .addComponent(verifyHostnameBox)
                .addLabeledComponent("CA bundle path:", caBundleField)
                .getPanel();
    }

    private JPanel buildProtocolPanel() {
        return FormBuilder.createFormBuilder()
                .addComponent(usePipeliningBox)
                .addComponent(useBdatBox)
                .addComponent(usePrdrBox)
                .getPanel();
    }

    private void populateFromProfile() {
        nameField.setText(profile.name);
        hostField.setText(profile.host);
        portSpinner.setValue(profile.port);
        ehloField.setText(profile.ehloHost);
        protocolBox.setSelectedItem(profile.protocol);
        tlsModeBox.setSelectedItem(profile.tlsMode);
        verifyCaBox.setSelected(profile.verifyCa);
        verifyHostnameBox.setSelected(profile.verifyHostname);
        caBundleField.setText(profile.caBundlePath);
        authMechBox.setSelectedItem(profile.authMechanism);
        usernameField.setText(profile.username);
        allowPlaintextAuthBox.setSelected(profile.allowPlaintextAuth);
        usePipeliningBox.setSelected(profile.usePipelining);
        useBdatBox.setSelected(profile.useBdat);
        usePrdrBox.setSelected(profile.usePrdr);
    }

    @Override
    protected void doOKAction() {
        try {
            commitToProfile();
            super.doOKAction();
        } catch (ConfigurationException failure) {
            Messages.showErrorDialog(getContentPanel(), failure.getLocalizedMessage(), "Invalid Profile");
        }
    }

    private void commitToProfile() throws ConfigurationException {
        var name = nameField.getText().trim();
        if (name.isEmpty()) {
            throw new ConfigurationException("Profile name cannot be empty.");
        }
        var host = hostField.getText().trim();
        if (host.isEmpty()) {
            throw new ConfigurationException("Host cannot be empty.");
        }
        profile.name = name;
        profile.host = host;
        profile.port = (Integer) portSpinner.getValue();
        profile.ehloHost = ehloField.getText().trim();
        profile.protocol = (SmtpProfile.Protocol) protocolBox.getSelectedItem();
        profile.tlsMode = (SmtpProfile.TlsMode) tlsModeBox.getSelectedItem();
        profile.verifyCa = verifyCaBox.isSelected();
        profile.verifyHostname = verifyHostnameBox.isSelected();
        profile.caBundlePath = caBundleField.getText().trim();
        profile.authMechanism = (SmtpProfile.AuthMechanismChoice) authMechBox.getSelectedItem();
        profile.username = usernameField.getText().trim();
        profile.allowPlaintextAuth = allowPlaintextAuthBox.isSelected();
        profile.usePipelining = usePipeliningBox.isSelected();
        profile.useBdat = useBdatBox.isSelected();
        profile.usePrdr = usePrdrBox.isSelected();

        var typed = new String(passwordField.getPassword());
        if (!typed.isEmpty()) {
            credentials.setPassword(profile.identifier, typed);
        }
    }

    public SmtpProfile getProfile() {
        return profile;
    }

    private void testConnection() {
        try {
            commitToProfile();
        } catch (ConfigurationException failure) {
            Messages.showErrorDialog(getContentPanel(), failure.getLocalizedMessage(), "Test Connection");
            return;
        }
        var config = SmtpProfiles.toConfig(profile, credentials);
        var stopAt = profile.authMechanism == SmtpProfile.AuthMechanismChoice.DISABLED ? Phase.HELO : Phase.AUTH;
        var configWithStop = config.withStopAfter(stopAt, false);
        var dummyEnvelope = SmtpEnvelope.of("test@" + profile.host, "test@" + profile.host);
        try {
            new SmtpClient().send(configWithStop, dummyEnvelope, MessageSource.ofString(""));
            Messages.showInfoMessage(
                    getContentPanel(), "Connection succeeded — reached " + stopAt + ".", "Test Connection");
        } catch (Exception failure) {
            if (failure.getClass().getSimpleName().equals("SmtpException")
                    && failure.getMessage() != null
                    && failure.getMessage().startsWith("stopped after")) {
                Messages.showInfoMessage(
                        getContentPanel(), "Connection succeeded — reached " + stopAt + ".", "Test Connection");
                return;
            }
            Messages.showErrorDialog(getContentPanel(), failure.getMessage(), "Test Connection");
        }
    }

    private static JSpinner buildPortSpinner() {
        var spinner = new JSpinner(new SpinnerNumberModel(587, 1, 65535, 1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }
}
