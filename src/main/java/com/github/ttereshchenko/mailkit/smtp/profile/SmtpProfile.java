package com.github.ttereshchenko.mailkit.smtp.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent description of an SMTP destination. Plain JavaBean shape so IntelliJ's
 * {@code XmlSerializer} (used by {@link com.intellij.openapi.components.PersistentStateComponent
 * PersistentStateComponent}) can round-trip the fields without any custom converters.
 *
 * <p>**Never** carry passwords or private-key bytes here — those live in
 * {@link SmtpCredentialStore} (which is backed by {@code PasswordSafe}). Only paths to key files
 * are stored, never the contents.
 */
public final class SmtpProfile {

    public String identifier = UUID.randomUUID().toString();
    public String name = "New SMTP profile";

    public String host = "";
    public int port = 587;
    public String ehloHost = "";
    public Protocol protocol = Protocol.ESMTP;
    public int timeoutSeconds = 60;

    public TlsMode tlsMode = TlsMode.NONE;
    public boolean verifyCa = true;
    public boolean verifyHostname = true;
    public String hostnameOverride = "";
    public String caBundlePath = "";
    public String clientCertPath = "";
    public String clientKeyPath = "";
    public String clientChainPath = "";
    public String sniHost = "";
    public List<String> protocols = new ArrayList<>();
    public List<String> cipherSuites = new ArrayList<>();

    public AuthMechanismChoice authMechanism = AuthMechanismChoice.DISABLED;
    public String username = "";
    public String authzId = "";
    public boolean allowPlaintextAuth = false;
    public boolean authOptional = false;
    public boolean authOptionalStrict = false;

    public boolean usePipelining = true;
    public boolean useBdat = false;
    public boolean usePrdr = false;
    public boolean enforceSmtpUtf8 = true;
    public boolean honorSize = true;
    public EightBitMimePolicy eightBitMime = EightBitMimePolicy.REQUIRE_WHEN_NEEDED;
    public boolean declareSizeOnMail = true;

    public IpFamilyChoice ipFamily = IpFamilyChoice.AUTO;
    public String localInterface = "";
    public Integer localPort = null;
    public boolean useMxRouting = false;

    public ProxyProtocolSettings proxyProtocol = new ProxyProtocolSettings();
    public XclientSettings xclient = new XclientSettings();

    public boolean isDefault = false;

    public List<DefaultHeader> defaultHeaders = defaultHeaderSeed();

    public SmtpProfile() {}

    private static List<DefaultHeader> defaultHeaderSeed() {
        var seed = new ArrayList<DefaultHeader>(2);
        seed.add(new DefaultHeader("From", ""));
        seed.add(new DefaultHeader("To", ""));
        return seed;
    }

    public String findDefaultHeaderValue(String name) {
        if (Objects.isNull(name) || Objects.isNull(defaultHeaders)) {
            return "";
        }
        for (var header : defaultHeaders) {
            if (Objects.nonNull(header.name)
                    && header.name.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return Objects.requireNonNullElse(header.value, "");
            }
        }
        return "";
    }

    public SmtpProfile copy() {
        var copy = new SmtpProfile();
        copy.identifier = identifier;
        copy.name = name;
        copy.host = host;
        copy.port = port;
        copy.ehloHost = ehloHost;
        copy.protocol = protocol;
        copy.timeoutSeconds = timeoutSeconds;
        copy.tlsMode = tlsMode;
        copy.verifyCa = verifyCa;
        copy.verifyHostname = verifyHostname;
        copy.hostnameOverride = hostnameOverride;
        copy.caBundlePath = caBundlePath;
        copy.clientCertPath = clientCertPath;
        copy.clientKeyPath = clientKeyPath;
        copy.clientChainPath = clientChainPath;
        copy.sniHost = sniHost;
        copy.protocols = new ArrayList<>(Objects.requireNonNullElseGet(protocols, ArrayList::new));
        copy.cipherSuites = new ArrayList<>(Objects.requireNonNullElseGet(cipherSuites, ArrayList::new));
        copy.authMechanism = authMechanism;
        copy.username = username;
        copy.authzId = authzId;
        copy.allowPlaintextAuth = allowPlaintextAuth;
        copy.authOptional = authOptional;
        copy.authOptionalStrict = authOptionalStrict;
        copy.usePipelining = usePipelining;
        copy.useBdat = useBdat;
        copy.usePrdr = usePrdr;
        copy.enforceSmtpUtf8 = enforceSmtpUtf8;
        copy.honorSize = honorSize;
        copy.eightBitMime = eightBitMime;
        copy.declareSizeOnMail = declareSizeOnMail;
        copy.ipFamily = ipFamily;
        copy.localInterface = localInterface;
        copy.localPort = localPort;
        copy.useMxRouting = useMxRouting;
        copy.proxyProtocol = proxyProtocol == null ? new ProxyProtocolSettings() : proxyProtocol.copy();
        copy.xclient = xclient == null ? new XclientSettings() : xclient.copy();
        copy.isDefault = isDefault;
        var headerCopies = new ArrayList<DefaultHeader>();
        if (Objects.nonNull(defaultHeaders)) {
            for (var header : defaultHeaders) {
                headerCopies.add(new DefaultHeader(header.name, header.value));
            }
        }
        copy.defaultHeaders = headerCopies;
        return copy;
    }

    public static final class DefaultHeader {
        public String name = "";
        public String value = "";

        public DefaultHeader() {}

        public DefaultHeader(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public enum Protocol {
        SMTP,
        ESMTP,
        LMTP
    }

    public enum TlsMode {
        NONE,
        STARTTLS_REQUIRED,
        STARTTLS_OPTIONAL,
        STARTTLS_OPTIONAL_STRICT,
        TLS_ON_CONNECT
    }

    public enum AuthMechanismChoice {
        DISABLED,
        AUTO,
        PLAIN,
        LOGIN,
        CRAM_MD5,
        DIGEST_MD5,
        SCRAM_SHA_1,
        SCRAM_SHA_256,
        EXTERNAL,
        XOAUTH2,
        OAUTHBEARER
    }

    public enum EightBitMimePolicy {
        REQUIRE_WHEN_NEEDED,
        DOWNGRADE_IF_UNADVERTISED,
        NEVER
    }

    public enum IpFamilyChoice {
        AUTO,
        IPV4,
        IPV6
    }

    public enum ProxyVersion {
        NONE,
        V1,
        V2
    }

    public enum ProxyCommand {
        PROXY,
        LOCAL
    }

    public enum ProxyFamily {
        TCP4,
        TCP6
    }

    public static final class ProxyProtocolSettings {
        public ProxyVersion version = ProxyVersion.NONE;
        public ProxyCommand command = ProxyCommand.PROXY;
        public ProxyFamily family = ProxyFamily.TCP4;
        public String sourceAddress = "";
        public int sourcePort = 0;
        public String destAddress = "";
        public int destPort = 0;

        public ProxyProtocolSettings() {}

        public ProxyProtocolSettings copy() {
            var copy = new ProxyProtocolSettings();
            copy.version = version;
            copy.command = command;
            copy.family = family;
            copy.sourceAddress = sourceAddress;
            copy.sourcePort = sourcePort;
            copy.destAddress = destAddress;
            copy.destPort = destPort;
            return copy;
        }
    }

    public static final class XclientSettings {
        public String addr = "";
        public String name = "";
        public Integer port = null;
        public String proto = "";
        public String helo = "";
        public String login = "";
        public String destAddr = "";
        public Integer destPort = null;
        public String reverseName = "";
        public List<DefaultHeader> extra = new ArrayList<>();
        public String rawCommand = "";
        public boolean beforeStartTls = false;
        public boolean optional = true;

        public XclientSettings() {}

        public XclientSettings copy() {
            var copy = new XclientSettings();
            copy.addr = addr;
            copy.name = name;
            copy.port = port;
            copy.proto = proto;
            copy.helo = helo;
            copy.login = login;
            copy.destAddr = destAddr;
            copy.destPort = destPort;
            copy.reverseName = reverseName;
            copy.extra = new ArrayList<>();
            if (Objects.nonNull(extra)) {
                for (var entry : extra) {
                    copy.extra.add(new DefaultHeader(entry.name, entry.value));
                }
            }
            copy.rawCommand = rawCommand;
            copy.beforeStartTls = beforeStartTls;
            copy.optional = optional;
            return copy;
        }
    }
}
