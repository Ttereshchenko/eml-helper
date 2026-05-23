package com.github.ttereshchenko.mailkit.smtp.profile;

import com.github.ttereshchenko.mailkit.smtp.EsmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyConfig;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import com.github.ttereshchenko.mailkit.smtp.transport.IpFamily;
import com.github.ttereshchenko.mailkit.smtp.transport.TransportConfig;
import com.github.ttereshchenko.mailkit.smtp.xclient.XclientConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                .withAuth(buildAuth(profile, credentials))
                .withProxy(buildProxy(profile))
                .withXclient(buildXclient(profile));
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
                filterBlanks(profile.protocols),
                filterBlanks(profile.cipherSuites),
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
                profile.declareSizeOnMail);
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
        var creds = new AuthCredentials(profile.username, passwordSupplier, profile.authzId, Map.of());
        var mechanism = mapMechanism(profile.authMechanism);
        return new AuthConfig(
                mechanism,
                creds,
                Map.of(),
                profile.allowPlaintextAuth,
                profile.authOptional,
                profile.authOptionalStrict);
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

    private static ProxyConfig buildProxy(SmtpProfile profile) {
        var settings = profile.proxyProtocol;
        if (settings == null || settings.version == SmtpProfile.ProxyVersion.NONE) {
            return ProxyConfig.disabled();
        }
        return new ProxyConfig(
                mapProxyVersion(settings.version),
                mapProxyCommand(settings.command),
                mapProxyFamily(settings.family),
                Objects.requireNonNullElse(settings.sourceAddress, ""),
                settings.sourcePort,
                Objects.requireNonNullElse(settings.destAddress, ""),
                settings.destPort);
    }

    private static XclientConfig buildXclient(SmtpProfile profile) {
        var settings = profile.xclient;
        if (settings == null) {
            return XclientConfig.disabled();
        }
        var extra = toAttributeMap(settings.extra);
        var raw = blankToNull(settings.rawCommand);
        var anySet = raw != null
                || !isBlank(settings.addr)
                || !isBlank(settings.name)
                || settings.port != null
                || !isBlank(settings.proto)
                || !isBlank(settings.helo)
                || !isBlank(settings.login)
                || !isBlank(settings.destAddr)
                || settings.destPort != null
                || !isBlank(settings.reverseName)
                || !extra.isEmpty();
        if (!anySet) {
            return XclientConfig.disabled();
        }
        return new XclientConfig(
                blankToNull(settings.addr),
                blankToNull(settings.name),
                settings.port,
                blankToNull(settings.proto),
                blankToNull(settings.helo),
                blankToNull(settings.login),
                blankToNull(settings.destAddr),
                settings.destPort,
                blankToNull(settings.reverseName),
                extra,
                raw,
                settings.beforeStartTls,
                settings.optional);
    }

    private static ProxyConfig.Version mapProxyVersion(SmtpProfile.ProxyVersion value) {
        return switch (value) {
            case NONE -> ProxyConfig.Version.NONE;
            case V1 -> ProxyConfig.Version.V1;
            case V2 -> ProxyConfig.Version.V2;
        };
    }

    private static ProxyConfig.Command mapProxyCommand(SmtpProfile.ProxyCommand value) {
        return switch (value) {
            case PROXY -> ProxyConfig.Command.PROXY;
            case LOCAL -> ProxyConfig.Command.LOCAL;
        };
    }

    private static ProxyConfig.Family mapProxyFamily(SmtpProfile.ProxyFamily value) {
        return switch (value) {
            case TCP4 -> ProxyConfig.Family.TCP4;
            case TCP6 -> ProxyConfig.Family.TCP6;
        };
    }

    private static Map<String, String> toAttributeMap(List<SmtpProfile.DefaultHeader> entries) {
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            if (entry == null) {
                continue;
            }
            var key = entry.name == null ? "" : entry.name.trim();
            if (key.isEmpty()) {
                continue;
            }
            map.put(key, Objects.requireNonNullElse(entry.value, ""));
        }
        return map;
    }

    private static List<String> filterBlanks(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        var copy = new ArrayList<String>(values.size());
        for (var value : values) {
            if (value == null) {
                continue;
            }
            var trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                copy.add(trimmed);
            }
        }
        return List.copyOf(copy);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
