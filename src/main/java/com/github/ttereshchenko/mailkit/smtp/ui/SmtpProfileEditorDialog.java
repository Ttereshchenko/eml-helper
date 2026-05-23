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
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.jetbrains.annotations.Nullable;

/**
 * Edit a single {@link SmtpProfile}. Tabs mirror the grouping documented in
 * {@code smtp-profile-config-groups.md} §"Suggested UI tab layout (after improvements)":
 * Connection · Security/TLS · Auth · ESMTP extensions · Transport/Network · Relay framing · Envelope.
 * Passwords flow through {@link SmtpCredentialStore} only — the password field's value is never
 * written to the profile JavaBean.
 */
public final class SmtpProfileEditorDialog extends DialogWrapper {

    private final SmtpProfile profile;
    private final SmtpCredentialStore credentials;

    // Connection tab
    private final JTextField nameField = new JTextField();
    private final JTextField hostField = new JTextField();
    private final JSpinner portSpinner = buildPortSpinner(587);
    private final JTextField ehloField = new JTextField();
    private final JComboBox<SmtpProfile.Protocol> protocolBox = new JComboBox<>(SmtpProfile.Protocol.values());
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(60, 1, 3600, 1));

    // Security / TLS tab
    private final JComboBox<SmtpProfile.TlsMode> tlsModeBox = new JComboBox<>(SmtpProfile.TlsMode.values());
    private final JPanel tlsBody = new JPanel();
    private final JCheckBox verifyCaBox = new JCheckBox("Verify CA chain");
    private final JCheckBox verifyHostnameBox = new JCheckBox("Verify hostname");
    private final JTextField caBundleField = new JTextField();
    private final JTextField hostnameOverrideField = new JTextField();
    private final JTextField sniHostField = new JTextField();
    private final JCheckBox showTlsAdvancedBox = new JCheckBox("Show advanced (protocols, cipher suites, client cert)");
    private final JPanel tlsAdvancedPanel = new JPanel();
    private final JTextField protocolsField = new JTextField();
    private final JTextField cipherSuitesField = new JTextField();
    private final JTextField clientCertPathField = new JTextField();
    private final JTextField clientKeyPathField = new JTextField();
    private final JTextField clientChainPathField = new JTextField();

    // Auth tab
    private final JComboBox<SmtpProfile.AuthMechanismChoice> authMechBox =
            new JComboBox<>(SmtpProfile.AuthMechanismChoice.values());
    private final JPanel authCoreBlock = new JPanel();
    private final JTextField usernameField = new JTextField();
    private final JLabel passwordLabel = new JLabel("Password:");
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox allowPlaintextAuthBox = new JCheckBox("Allow plaintext AUTH over non-TLS (dangerous)");
    private final JCheckBox authOptionalBox = new JCheckBox("AUTH is optional (server may not require it)");
    private final JCheckBox authOptionalStrictBox =
            new JCheckBox("Strict: fail if AUTH advertised but optional handling diverges");
    private final JTextField authzIdField = new JTextField();

    // ESMTP extensions tab
    private final JCheckBox usePipeliningBox = new JCheckBox("Use PIPELINING if advertised");
    private final JCheckBox useBdatBox = new JCheckBox("Use BDAT instead of DATA when CHUNKING advertised");
    private final JCheckBox usePrdrBox = new JCheckBox("Use PRDR if advertised");
    private final JCheckBox enforceSmtpUtf8Box = new JCheckBox("Enforce SMTPUTF8 when needed");
    private final JCheckBox honorSizeBox = new JCheckBox("Honour advertised SIZE limit");
    private final JComboBox<SmtpProfile.EightBitMimePolicy> eightBitMimeBox =
            new JComboBox<>(SmtpProfile.EightBitMimePolicy.values());
    private final JCheckBox declareSizeOnMailBox = new JCheckBox("Declare size on MAIL FROM (BODY=SIZE=…)");

    // Transport / Network tab
    private final JComboBox<SmtpProfile.IpFamilyChoice> ipFamilyBox =
            new JComboBox<>(SmtpProfile.IpFamilyChoice.values());
    private final JTextField localInterfaceField = new JTextField();
    private final JTextField localPortField = new JTextField();
    private final JCheckBox useMxRoutingBox = new JCheckBox("Use DNS MX routing from MAIL FROM domain");

    // Relay framing tab — PROXY card
    private final JComboBox<SmtpProfile.ProxyVersion> proxyVersionBox =
            new JComboBox<>(SmtpProfile.ProxyVersion.values());
    private final JComboBox<SmtpProfile.ProxyCommand> proxyCommandBox =
            new JComboBox<>(SmtpProfile.ProxyCommand.values());
    private final JComboBox<SmtpProfile.ProxyFamily> proxyFamilyBox = new JComboBox<>(SmtpProfile.ProxyFamily.values());
    private final JTextField proxySourceAddressField = new JTextField();
    private final JSpinner proxySourcePortSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JTextField proxyDestAddressField = new JTextField();
    private final JSpinner proxyDestPortSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));

    // Relay framing tab — XCLIENT card
    private final JTextField xclientAddrField = new JTextField();
    private final JTextField xclientNameField = new JTextField();
    private final JTextField xclientPortField = new JTextField();
    private final JTextField xclientProtoField = new JTextField();
    private final JTextField xclientHeloField = new JTextField();
    private final JTextField xclientLoginField = new JTextField();
    private final JTextField xclientDestAddrField = new JTextField();
    private final JTextField xclientDestPortField = new JTextField();
    private final JTextField xclientReverseNameField = new JTextField();
    private final XclientExtraTableModel xclientExtraModel = new XclientExtraTableModel();
    private final JBTable xclientExtraTable = new JBTable(xclientExtraModel);
    private final JTextField xclientRawCommandField = new JTextField();
    private final JCheckBox xclientBeforeStartTlsBox = new JCheckBox("Send XCLIENT before STARTTLS");
    private final JCheckBox xclientOptionalBox = new JCheckBox("Optional (no-op if server doesn't advertise XCLIENT)");

    // Envelope tab
    private final JTextField envelopeFromField = new JTextField();
    private final JTextField envelopeToField = new JTextField();

    // Dynamic-reveal bookkeeping
    private static final int ESMTP_TAB_INSERT_INDEX = 3; // after Connection (0) / TLS (1) / Auth (2)
    private JBTabbedPane tabs;
    private JComponent esmtpTabComponent;
    private boolean esmtpTabPresent = false;

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
        wireRevealListeners();
        syncAllReveals();
    }

    @Override
    protected JComponent createCenterPanel() {
        tabs = new JBTabbedPane();
        tabs.addTab("Connection", wrapTab(buildConnectionPanel()));
        tabs.addTab("Security / TLS", wrapTab(buildSecurityTlsPanel()));
        tabs.addTab("Auth", wrapTab(buildAuthPanel()));
        esmtpTabComponent = wrapTab(buildEsmtpPanel());
        tabs.insertTab("ESMTP extensions", null, esmtpTabComponent, null, ESMTP_TAB_INSERT_INDEX);
        esmtpTabPresent = true;
        tabs.addTab("Transport / Network", wrapTab(buildTransportPanel()));
        tabs.addTab("Relay framing", wrapTab(buildRelayFramingPanel()));
        tabs.addTab("Envelope", wrapTab(buildEnvelopePanel()));

        var wrapper = new JPanel(new BorderLayout());
        // Preferred (initial) size only; per-tab JBScrollPane wrappers keep the minimum tiny so
        // the user can shrink the window freely and scrollbars take over on tall tabs.
        wrapper.setPreferredSize(new Dimension(720, 540));
        wrapper.add(tabs, BorderLayout.CENTER);
        var testButton = new JButton("Test connection");
        testButton.addActionListener(event -> testConnection());
        var bottom = new JPanel();
        bottom.add(testButton);
        wrapper.add(bottom, BorderLayout.SOUTH);
        return wrapper;
    }

    private static JComponent wrapTab(JComponent content) {
        var topAligned = new JPanel(new BorderLayout());
        topAligned.add(content, BorderLayout.NORTH);
        var scroll = new JBScrollPane(topAligned);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setMinimumSize(new Dimension(0, 0));
        return scroll;
    }

    private JPanel buildConnectionPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Profile name:", nameField)
                .addLabeledComponent("Host:", hostField)
                .addLabeledComponent("Port:", portSpinner)
                .addLabeledComponent("EHLO host:", ehloField)
                .addLabeledComponent("Protocol:", protocolBox)
                .addLabeledComponent("Timeout (seconds):", timeoutSpinner)
                .getPanel();
    }

    private JPanel buildSecurityTlsPanel() {
        tlsBody.setLayout(new BoxLayout(tlsBody, BoxLayout.Y_AXIS));

        var verifyAndCertsTop = FormBuilder.createFormBuilder()
                .addComponent(verifyCaBox)
                .addComponent(verifyHostnameBox)
                .addLabeledComponent("CA bundle path:", caBundleField)
                .addLabeledComponent("Hostname override:", hostnameOverrideField)
                .addLabeledComponent("SNI host:", sniHostField)
                .addComponent(showTlsAdvancedBox)
                .getPanel();
        tlsBody.add(verifyAndCertsTop);

        tlsAdvancedPanel.setLayout(new BorderLayout());
        var advancedForm = FormBuilder.createFormBuilder()
                .addLabeledComponent("Protocols (comma-sep):", protocolsField)
                .addLabeledComponent("Cipher suites (comma-sep):", cipherSuitesField)
                .addSeparator()
                .addLabeledComponent("Client cert path:", clientCertPathField)
                .addLabeledComponent("Client key path:", clientKeyPathField)
                .addLabeledComponent("Client chain path:", clientChainPathField)
                .getPanel();
        advancedForm.setBorder(BorderFactory.createTitledBorder("Advanced"));
        tlsAdvancedPanel.add(advancedForm, BorderLayout.CENTER);
        tlsBody.add(tlsAdvancedPanel);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Mode:", tlsModeBox)
                .addComponent(tlsBody)
                .getPanel();
    }

    private JPanel buildAuthPanel() {
        authCoreBlock.setLayout(new BorderLayout());
        var coreForm = FormBuilder.createFormBuilder()
                .addLabeledComponent("Username:", usernameField)
                .addLabeledComponent(passwordLabel, passwordField)
                .addComponent(allowPlaintextAuthBox)
                .addComponent(authOptionalBox)
                .addComponent(authOptionalStrictBox)
                .addLabeledComponent("Authz-Id:", authzIdField)
                .getPanel();
        authCoreBlock.add(coreForm, BorderLayout.CENTER);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Mechanism:", authMechBox)
                .addComponent(authCoreBlock)
                .getPanel();
    }

    private JPanel buildEsmtpPanel() {
        return FormBuilder.createFormBuilder()
                .addComponent(usePipeliningBox)
                .addComponent(useBdatBox)
                .addComponent(usePrdrBox)
                .addComponent(enforceSmtpUtf8Box)
                .addComponent(honorSizeBox)
                .addLabeledComponent("8BITMIME policy:", eightBitMimeBox)
                .addComponent(declareSizeOnMailBox)
                .getPanel();
    }

    private JPanel buildTransportPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("IP family:", ipFamilyBox)
                .addLabeledComponent("Local interface:", localInterfaceField)
                .addLabeledComponent("Local port (blank = any):", localPortField)
                .addComponent(useMxRoutingBox)
                .getPanel();
    }

    private JPanel buildRelayFramingPanel() {
        var proxyForm = FormBuilder.createFormBuilder()
                .addLabeledComponent("Version:", proxyVersionBox)
                .addLabeledComponent("Command:", proxyCommandBox)
                .addLabeledComponent("Family:", proxyFamilyBox)
                .addLabeledComponent("Source address:", proxySourceAddressField)
                .addLabeledComponent("Source port:", proxySourcePortSpinner)
                .addLabeledComponent("Destination address:", proxyDestAddressField)
                .addLabeledComponent("Destination port:", proxyDestPortSpinner)
                .getPanel();
        proxyForm.setBorder(BorderFactory.createTitledBorder("PROXY protocol (written before SMTP banner)"));

        var xclientExtraDecorator = ToolbarDecorator.createDecorator(xclientExtraTable)
                .setAddAction(event -> {
                    if (xclientExtraTable.isEditing()) {
                        xclientExtraTable.getCellEditor().stopCellEditing();
                    }
                    xclientExtraModel.addRow();
                })
                .setRemoveAction(event -> {
                    var row = xclientExtraTable.getSelectedRow();
                    if (row >= 0) {
                        if (xclientExtraTable.isEditing()) {
                            xclientExtraTable.getCellEditor().stopCellEditing();
                        }
                        xclientExtraModel.removeRow(row);
                    }
                });

        var xclientForm = FormBuilder.createFormBuilder()
                .addLabeledComponent("ADDR:", xclientAddrField)
                .addLabeledComponent("NAME:", xclientNameField)
                .addLabeledComponent("PORT (blank = unset):", xclientPortField)
                .addLabeledComponent("PROTO:", xclientProtoField)
                .addLabeledComponent("HELO:", xclientHeloField)
                .addLabeledComponent("LOGIN:", xclientLoginField)
                .addLabeledComponent("DESTADDR:", xclientDestAddrField)
                .addLabeledComponent("DESTPORT (blank = unset):", xclientDestPortField)
                .addLabeledComponent("REVERSE-NAME:", xclientReverseNameField)
                .addLabeledComponent("Extra attributes:", xclientExtraDecorator.createPanel())
                .addLabeledComponent("Raw command (overrides):", xclientRawCommandField)
                .addComponent(xclientBeforeStartTlsBox)
                .addComponent(xclientOptionalBox)
                .getPanel();
        xclientForm.setBorder(BorderFactory.createTitledBorder("XCLIENT (Postfix)"));

        var container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.add(proxyForm);
        container.add(xclientForm);
        return container;
    }

    private JPanel buildEnvelopePanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("From:", envelopeFromField)
                .addLabeledComponent("To:", envelopeToField)
                .getPanel();
    }

    private void populateFromProfile() {
        nameField.setText(profile.name);
        hostField.setText(profile.host);
        portSpinner.setValue(profile.port);
        ehloField.setText(profile.ehloHost);
        protocolBox.setSelectedItem(profile.protocol);
        timeoutSpinner.setValue(Math.max(1, profile.timeoutSeconds));

        tlsModeBox.setSelectedItem(profile.tlsMode);
        verifyCaBox.setSelected(profile.verifyCa);
        verifyHostnameBox.setSelected(profile.verifyHostname);
        caBundleField.setText(profile.caBundlePath);
        hostnameOverrideField.setText(profile.hostnameOverride);
        sniHostField.setText(profile.sniHost);
        protocolsField.setText(String.join(", ", profile.protocols == null ? List.of() : profile.protocols));
        cipherSuitesField.setText(String.join(", ", profile.cipherSuites == null ? List.of() : profile.cipherSuites));
        clientCertPathField.setText(profile.clientCertPath);
        clientKeyPathField.setText(profile.clientKeyPath);
        clientChainPathField.setText(profile.clientChainPath);
        showTlsAdvancedBox.setSelected(!profile.protocols.isEmpty()
                || !profile.cipherSuites.isEmpty()
                || !profile.clientCertPath.isBlank()
                || !profile.clientKeyPath.isBlank()
                || !profile.clientChainPath.isBlank());

        authMechBox.setSelectedItem(profile.authMechanism);
        usernameField.setText(profile.username);
        authzIdField.setText(profile.authzId);
        allowPlaintextAuthBox.setSelected(profile.allowPlaintextAuth);
        authOptionalBox.setSelected(profile.authOptional);
        authOptionalStrictBox.setSelected(profile.authOptionalStrict);

        usePipeliningBox.setSelected(profile.usePipelining);
        useBdatBox.setSelected(profile.useBdat);
        usePrdrBox.setSelected(profile.usePrdr);
        enforceSmtpUtf8Box.setSelected(profile.enforceSmtpUtf8);
        honorSizeBox.setSelected(profile.honorSize);
        eightBitMimeBox.setSelectedItem(profile.eightBitMime);
        declareSizeOnMailBox.setSelected(profile.declareSizeOnMail);

        ipFamilyBox.setSelectedItem(profile.ipFamily);
        localInterfaceField.setText(profile.localInterface);
        localPortField.setText(profile.localPort == null ? "" : profile.localPort.toString());
        useMxRoutingBox.setSelected(profile.useMxRouting);

        var proxySettings =
                profile.proxyProtocol == null ? new SmtpProfile.ProxyProtocolSettings() : profile.proxyProtocol;
        proxyVersionBox.setSelectedItem(proxySettings.version);
        proxyCommandBox.setSelectedItem(proxySettings.command);
        proxyFamilyBox.setSelectedItem(proxySettings.family);
        proxySourceAddressField.setText(proxySettings.sourceAddress);
        proxySourcePortSpinner.setValue(clampPort(proxySettings.sourcePort));
        proxyDestAddressField.setText(proxySettings.destAddress);
        proxyDestPortSpinner.setValue(clampPort(proxySettings.destPort));

        var xclientSettings = profile.xclient == null ? new SmtpProfile.XclientSettings() : profile.xclient;
        xclientAddrField.setText(xclientSettings.addr);
        xclientNameField.setText(xclientSettings.name);
        xclientPortField.setText(xclientSettings.port == null ? "" : xclientSettings.port.toString());
        xclientProtoField.setText(xclientSettings.proto);
        xclientHeloField.setText(xclientSettings.helo);
        xclientLoginField.setText(xclientSettings.login);
        xclientDestAddrField.setText(xclientSettings.destAddr);
        xclientDestPortField.setText(xclientSettings.destPort == null ? "" : xclientSettings.destPort.toString());
        xclientReverseNameField.setText(xclientSettings.reverseName);
        xclientExtraModel.load(xclientSettings.extra);
        xclientRawCommandField.setText(xclientSettings.rawCommand);
        xclientBeforeStartTlsBox.setSelected(xclientSettings.beforeStartTls);
        xclientOptionalBox.setSelected(xclientSettings.optional);

        envelopeFromField.setText(profile.findDefaultHeaderValue("From"));
        envelopeToField.setText(profile.findDefaultHeaderValue("To"));
    }

    private void wireRevealListeners() {
        tlsModeBox.addItemListener(event -> syncTlsReveals());
        showTlsAdvancedBox.addItemListener(event -> syncTlsReveals());
        authMechBox.addItemListener(event -> syncAuthReveals());
        protocolBox.addItemListener(event -> syncEsmtpTabVisible());
        proxyVersionBox.addItemListener(event -> syncProxyReveals());
        xclientRawCommandField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                syncXclientReveals();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                syncXclientReveals();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                syncXclientReveals();
            }
        });
    }

    private void syncAllReveals() {
        syncTlsReveals();
        syncAuthReveals();
        syncEsmtpTabVisible();
        syncProxyReveals();
        syncXclientReveals();
    }

    private void syncTlsReveals() {
        var mode = (SmtpProfile.TlsMode) tlsModeBox.getSelectedItem();
        var tlsEnabled = mode != null && mode != SmtpProfile.TlsMode.NONE;
        tlsBody.setVisible(tlsEnabled);
        tlsAdvancedPanel.setVisible(tlsEnabled && showTlsAdvancedBox.isSelected());
        tlsBody.revalidate();
        tlsBody.repaint();
    }

    private void syncAuthReveals() {
        var mech = (SmtpProfile.AuthMechanismChoice) authMechBox.getSelectedItem();
        var authEnabled = mech != null && mech != SmtpProfile.AuthMechanismChoice.DISABLED;
        authCoreBlock.setVisible(authEnabled);
        if (mech == SmtpProfile.AuthMechanismChoice.XOAUTH2 || mech == SmtpProfile.AuthMechanismChoice.OAUTHBEARER) {
            passwordLabel.setText("Access token:");
        } else {
            passwordLabel.setText("Password:");
        }
        authCoreBlock.revalidate();
        authCoreBlock.repaint();
    }

    private void syncEsmtpTabVisible() {
        if (tabs == null || esmtpTabComponent == null) {
            return;
        }
        var isEsmtp = protocolBox.getSelectedItem() == SmtpProfile.Protocol.ESMTP;
        if (isEsmtp && !esmtpTabPresent) {
            tabs.insertTab("ESMTP extensions", null, esmtpTabComponent, null, ESMTP_TAB_INSERT_INDEX);
            esmtpTabPresent = true;
        } else if (!isEsmtp && esmtpTabPresent) {
            var index = tabs.indexOfComponent(esmtpTabComponent);
            if (index >= 0) {
                if (tabs.getSelectedIndex() == index) {
                    tabs.setSelectedIndex(0);
                }
                tabs.removeTabAt(index);
            }
            esmtpTabPresent = false;
        }
    }

    private void syncProxyReveals() {
        var version = (SmtpProfile.ProxyVersion) proxyVersionBox.getSelectedItem();
        var enabled = version != null && version != SmtpProfile.ProxyVersion.NONE;
        proxyCommandBox.setEnabled(enabled);
        proxyFamilyBox.setEnabled(enabled);
        proxySourceAddressField.setEnabled(enabled);
        proxySourcePortSpinner.setEnabled(enabled);
        proxyDestAddressField.setEnabled(enabled);
        proxyDestPortSpinner.setEnabled(enabled);
    }

    private void syncXclientReveals() {
        var rawSet = !xclientRawCommandField.getText().isBlank();
        var individualEnabled = !rawSet;
        xclientAddrField.setEnabled(individualEnabled);
        xclientNameField.setEnabled(individualEnabled);
        xclientPortField.setEnabled(individualEnabled);
        xclientProtoField.setEnabled(individualEnabled);
        xclientHeloField.setEnabled(individualEnabled);
        xclientLoginField.setEnabled(individualEnabled);
        xclientDestAddrField.setEnabled(individualEnabled);
        xclientDestPortField.setEnabled(individualEnabled);
        xclientReverseNameField.setEnabled(individualEnabled);
        xclientExtraTable.setEnabled(individualEnabled);
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
        profile.timeoutSeconds = (Integer) timeoutSpinner.getValue();

        profile.tlsMode = (SmtpProfile.TlsMode) tlsModeBox.getSelectedItem();
        profile.verifyCa = verifyCaBox.isSelected();
        profile.verifyHostname = verifyHostnameBox.isSelected();
        profile.caBundlePath = caBundleField.getText().trim();
        profile.hostnameOverride = hostnameOverrideField.getText().trim();
        profile.sniHost = sniHostField.getText().trim();
        profile.protocols = splitCommaList(protocolsField.getText());
        profile.cipherSuites = splitCommaList(cipherSuitesField.getText());
        profile.clientCertPath = clientCertPathField.getText().trim();
        profile.clientKeyPath = clientKeyPathField.getText().trim();
        profile.clientChainPath = clientChainPathField.getText().trim();

        profile.authMechanism = (SmtpProfile.AuthMechanismChoice) authMechBox.getSelectedItem();
        profile.username = usernameField.getText().trim();
        profile.authzId = authzIdField.getText().trim();
        profile.allowPlaintextAuth = allowPlaintextAuthBox.isSelected();
        profile.authOptional = authOptionalBox.isSelected();
        profile.authOptionalStrict = authOptionalStrictBox.isSelected();

        profile.usePipelining = usePipeliningBox.isSelected();
        profile.useBdat = useBdatBox.isSelected();
        profile.usePrdr = usePrdrBox.isSelected();
        profile.enforceSmtpUtf8 = enforceSmtpUtf8Box.isSelected();
        profile.honorSize = honorSizeBox.isSelected();
        profile.eightBitMime = (SmtpProfile.EightBitMimePolicy) eightBitMimeBox.getSelectedItem();
        profile.declareSizeOnMail = declareSizeOnMailBox.isSelected();

        profile.ipFamily = (SmtpProfile.IpFamilyChoice) ipFamilyBox.getSelectedItem();
        profile.localInterface = localInterfaceField.getText().trim();
        profile.localPort = parseNullableInt(localPortField.getText(), "Local port", 0, 65535);
        profile.useMxRouting = useMxRoutingBox.isSelected();

        var proxySettings =
                profile.proxyProtocol == null ? new SmtpProfile.ProxyProtocolSettings() : profile.proxyProtocol;
        proxySettings.version = (SmtpProfile.ProxyVersion) proxyVersionBox.getSelectedItem();
        proxySettings.command = (SmtpProfile.ProxyCommand) proxyCommandBox.getSelectedItem();
        proxySettings.family = (SmtpProfile.ProxyFamily) proxyFamilyBox.getSelectedItem();
        proxySettings.sourceAddress = proxySourceAddressField.getText().trim();
        proxySettings.sourcePort = (Integer) proxySourcePortSpinner.getValue();
        proxySettings.destAddress = proxyDestAddressField.getText().trim();
        proxySettings.destPort = (Integer) proxyDestPortSpinner.getValue();
        if (proxySettings.version != SmtpProfile.ProxyVersion.NONE) {
            if (proxySettings.sourceAddress.isEmpty() || proxySettings.destAddress.isEmpty()) {
                throw new ConfigurationException("PROXY protocol requires source and destination addresses.");
            }
        }
        profile.proxyProtocol = proxySettings;

        if (xclientExtraTable.isEditing()) {
            xclientExtraTable.getCellEditor().stopCellEditing();
        }
        var xclientSettings = profile.xclient == null ? new SmtpProfile.XclientSettings() : profile.xclient;
        xclientSettings.addr = xclientAddrField.getText().trim();
        xclientSettings.name = xclientNameField.getText().trim();
        xclientSettings.port = parseNullableInt(xclientPortField.getText(), "XCLIENT PORT", 0, 65535);
        xclientSettings.proto = xclientProtoField.getText().trim();
        xclientSettings.helo = xclientHeloField.getText().trim();
        xclientSettings.login = xclientLoginField.getText().trim();
        xclientSettings.destAddr = xclientDestAddrField.getText().trim();
        xclientSettings.destPort = parseNullableInt(xclientDestPortField.getText(), "XCLIENT DESTPORT", 0, 65535);
        xclientSettings.reverseName = xclientReverseNameField.getText().trim();
        xclientSettings.extra = xclientExtraModel.snapshot();
        xclientSettings.rawCommand = xclientRawCommandField.getText().trim();
        xclientSettings.beforeStartTls = xclientBeforeStartTlsBox.isSelected();
        xclientSettings.optional = xclientOptionalBox.isSelected();
        profile.xclient = xclientSettings;

        var envelope = new ArrayList<SmtpProfile.DefaultHeader>(2);
        envelope.add(new SmtpProfile.DefaultHeader(
                "From", envelopeFromField.getText().trim()));
        envelope.add(
                new SmtpProfile.DefaultHeader("To", envelopeToField.getText().trim()));
        profile.defaultHeaders = envelope;

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

    private static JSpinner buildPortSpinner(int initial) {
        var spinner = new JSpinner(new SpinnerNumberModel(initial, 1, 65535, 1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }

    private static List<String> splitCommaList(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        var result = new ArrayList<String>();
        for (var part : value.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static Integer parseNullableInt(String raw, String label, int min, int max) throws ConfigurationException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new ConfigurationException(label + " out of range [" + min + ".." + max + "].");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new ConfigurationException(label + " must be an integer.");
        }
    }

    private static int clampPort(int raw) {
        if (raw < 0) {
            return 0;
        }
        if (raw > 65535) {
            return 65535;
        }
        return raw;
    }

    private static final class XclientExtraTableModel extends AbstractTableModel {
        private static final String[] COLUMN_NAMES = {"Attribute", "Value"};
        private final List<SmtpProfile.DefaultHeader> rows = new ArrayList<>();

        void load(List<SmtpProfile.DefaultHeader> source) {
            rows.clear();
            if (source != null) {
                for (var entry : source) {
                    rows.add(new SmtpProfile.DefaultHeader(
                            entry.name == null ? "" : entry.name, entry.value == null ? "" : entry.value));
                }
            }
            fireTableDataChanged();
        }

        List<SmtpProfile.DefaultHeader> snapshot() {
            var copy = new ArrayList<SmtpProfile.DefaultHeader>(rows.size());
            for (var entry : rows) {
                var key = entry.name == null ? "" : entry.name.trim();
                if (key.isEmpty()) {
                    continue;
                }
                copy.add(new SmtpProfile.DefaultHeader(key, Objects.requireNonNullElse(entry.value, "")));
            }
            return copy;
        }

        void addRow() {
            rows.add(new SmtpProfile.DefaultHeader("", ""));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeRow(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            var entry = rows.get(row);
            return column == 0 ? entry.name : entry.value;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            var text = value == null ? "" : value.toString();
            var entry = rows.get(row);
            if (column == 0) {
                entry.name = text;
            } else {
                entry.value = text;
            }
            fireTableCellUpdated(row, column);
        }
    }
}
