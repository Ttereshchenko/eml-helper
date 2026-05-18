package com.github.ttereshchenko.mailkit.smtp.profile;

import com.github.ttereshchenko.mailkit.smtp.EsmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import com.github.ttereshchenko.mailkit.smtp.transport.IpFamily;
import com.github.ttereshchenko.mailkit.smtp.transport.TransportConfig;
import java.nio.file.Path;
import java.time.Duration;

/** Bridges the persisted {@link SmtpProfile} JavaBean to the runtime {@link SmtpConfig} record. */
public final class SmtpProfiles {

    private SmtpProfiles() {}

    public static SmtpConfig toConfig(SmtpProfile profile, SmtpCredentialStore credentials) {
        var ehlo = profile.ehloHost == null || profile.ehloHost.isBlank() ? defaultEhloHost() : profile.ehloHost;
        var base = SmtpConfig.defaults(profile.host)
                .withPort(profile.port)
                .withEhloHost(ehlo)
                .withProtocol(mapProtocol(profile.protocol))
                .withTimeout(Duration.ofSeconds(Math.max(1, profile.timeoutSeconds)))
                .withTls(buildTls(profile))
                .withEsmtp(buildEsmtp(profile))
                .withTransport(buildTransport(profile))
                .withAuth(buildAuth(profile, credentials));
        return base;
    }

    private static SmtpConfig.Protocol mapProtocol(SmtpProfile.Protocol protocol) {
        return switch (protocol) {
            case SMTP -> SmtpConfig.Protocol.SMTP;
            case ESMTP -> SmtpConfig.Protocol.ESMTP;
            case LMTP -> SmtpConfig.Protocol.LMTP;
        };
    }

    private static TlsConfig buildTls(SmtpProfile profile) {
        var mode =
                switch (profile.tlsMode) {
                    case NONE -> TlsConfig.Mode.NONE;
                    case STARTTLS_REQUIRED -> TlsConfig.Mode.STARTTLS_REQUIRED;
                    case STARTTLS_OPTIONAL -> TlsConfig.Mode.STARTTLS_OPTIONAL;
                    case STARTTLS_OPTIONAL_STRICT -> TlsConfig.Mode.STARTTLS_OPTIONAL_STRICT;
                    case TLS_ON_CONNECT -> TlsConfig.Mode.TLS_ON_CONNECT;
                };
        var tls = new TlsConfig(
                mode,
                java.util.List.of(),
                java.util.List.of(),
                profile.verifyCa,
                profile.verifyHostname,
                blankToNull(profile.hostnameOverride),
                pathOrNull(profile.caBundlePath),
                pathOrNull(profile.clientCertPath),
                pathOrNull(profile.clientKeyPath),
                pathOrNull(profile.clientChainPath),
                blankToNull(profile.sniHost),
                false);
        return tls;
    }

    private static EsmtpConfig buildEsmtp(SmtpProfile profile) {
        var policy =
                switch (profile.eightBitMime) {
                    case REQUIRE_WHEN_NEEDED -> EsmtpConfig.EightBitMimePolicy.REQUIRE_WHEN_NEEDED;
                    case DOWNGRADE_IF_UNADVERTISED -> EsmtpConfig.EightBitMimePolicy.DOWNGRADE_IF_UNADVERTISED;
                    case NEVER -> EsmtpConfig.EightBitMimePolicy.NEVER;
                };
        return new EsmtpConfig(
                profile.usePipelining,
                profile.useBdat,
                profile.usePrdr,
                profile.enforceSmtpUtf8,
                profile.honorSize,
                policy,
                true);
    }

    private static TransportConfig buildTransport(SmtpProfile profile) {
        var family =
                switch (profile.ipFamily) {
                    case AUTO -> IpFamily.AUTO;
                    case IPV4 -> IpFamily.IPV4;
                    case IPV6 -> IpFamily.IPV6;
                };
        return new TransportConfig(
                family, blankToNull(profile.localInterface), profile.localPort, profile.useMxRouting);
    }

    private static AuthConfig buildAuth(SmtpProfile profile, SmtpCredentialStore credentials) {
        if (profile.authMechanism == SmtpProfile.AuthMechanismChoice.DISABLED) {
            return AuthConfig.disabled();
        }
        var passwordSupplier = credentials.passwordSupplier(profile.identifier);
        var creds = AuthCredentials.of(profile.username, passwordSupplier);
        var mechanism = mapMechanism(profile.authMechanism);
        var auth = mechanism == null ? AuthConfig.auto(creds) : AuthConfig.forMechanism(mechanism, creds);
        return auth.withAllowPlaintextAuth(profile.allowPlaintextAuth);
    }

    private static AuthMechanism mapMechanism(SmtpProfile.AuthMechanismChoice choice) {
        return switch (choice) {
            case DISABLED, AUTO -> null;
            case PLAIN -> AuthMechanism.PLAIN;
            case LOGIN -> AuthMechanism.LOGIN;
            case CRAM_MD5 -> AuthMechanism.CRAM_MD5;
            case DIGEST_MD5 -> AuthMechanism.DIGEST_MD5;
            case SCRAM_SHA_1 -> AuthMechanism.SCRAM_SHA_1;
            case SCRAM_SHA_256 -> AuthMechanism.SCRAM_SHA_256;
            case EXTERNAL -> AuthMechanism.EXTERNAL;
            case XOAUTH2 -> AuthMechanism.XOAUTH2;
            case OAUTHBEARER -> AuthMechanism.OAUTHBEARER;
        };
    }

    private static String defaultEhloHost() {
        try {
            var local = java.net.InetAddress.getLocalHost().getCanonicalHostName();
            return local == null || local.isBlank() ? "mailkit.local" : local;
        } catch (java.net.UnknownHostException ignored) {
            return "mailkit.local";
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Path pathOrNull(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
