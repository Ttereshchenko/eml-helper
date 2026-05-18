package com.github.ttereshchenko.mailkit.smtp.profile;

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
    public Protocol protocol = Protocol.SMTP;
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

    public AuthMechanismChoice authMechanism = AuthMechanismChoice.DISABLED;
    public String username = "";
    public boolean allowPlaintextAuth = false;
    public boolean authOptional = false;

    public boolean usePipelining = true;
    public boolean useBdat = false;
    public boolean usePrdr = false;
    public boolean enforceSmtpUtf8 = true;
    public boolean honorSize = true;
    public EightBitMimePolicy eightBitMime = EightBitMimePolicy.REQUIRE_WHEN_NEEDED;

    public IpFamilyChoice ipFamily = IpFamilyChoice.AUTO;
    public String localInterface = "";
    public Integer localPort = null;
    public boolean useMxRouting = false;

    public boolean isDefault = false;

    public SmtpProfile() {}

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
        copy.authMechanism = authMechanism;
        copy.username = username;
        copy.allowPlaintextAuth = allowPlaintextAuth;
        copy.authOptional = authOptional;
        copy.usePipelining = usePipelining;
        copy.useBdat = useBdat;
        copy.usePrdr = usePrdr;
        copy.enforceSmtpUtf8 = enforceSmtpUtf8;
        copy.honorSize = honorSize;
        copy.eightBitMime = eightBitMime;
        copy.ipFamily = ipFamily;
        copy.localInterface = localInterface;
        copy.localPort = localPort;
        copy.useMxRouting = useMxRouting;
        copy.isDefault = isDefault;
        return copy;
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
}
