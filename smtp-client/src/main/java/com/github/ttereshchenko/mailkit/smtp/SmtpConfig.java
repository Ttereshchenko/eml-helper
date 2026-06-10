package com.github.ttereshchenko.mailkit.smtp;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyConfig;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import com.github.ttereshchenko.mailkit.smtp.transport.TransportConfig;
import com.github.ttereshchenko.mailkit.smtp.xclient.XclientConfig;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/**
 * Wire-level knobs for a single SMTP transaction. Callers usually start from
 * {@link #defaults(String)} and adjust individual knobs via the {@code with*} helpers.
 */
public record SmtpConfig(
        String host,
        int port,
        String ehloHost,
        Protocol protocol,
        Duration timeout,
        Phase stopAfter,
        boolean dropAfter,
        TlsConfig tls,
        AuthConfig auth,
        EsmtpConfig esmtp,
        TransportConfig transport,
        ProxyConfig proxy,
        XclientConfig xclient) {

    public enum Protocol {
        /** Plain rfc5321 HELO, no extensions. */
        SMTP,
        /** EHLO with extension negotiation (falls back to HELO when EHLO is rejected). */
        ESMTP,
        /**
         * Reserved: rfc2033 LMTP (LHLO + per-recipient final replies) is not implemented yet, and
         * configuring it is rejected rather than silently behaving like ESMTP.
         */
        LMTP
    }

    public static final int DEFAULT_SUBMISSION_PORT = 587;

    public SmtpConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(ehloHost, "ehloHost");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(esmtp, "esmtp");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(xclient, "xclient");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (protocol == Protocol.LMTP) {
            throw new IllegalArgumentException(
                    "LMTP is not supported yet (requires LHLO and per-recipient DATA replies, rfc2033)");
        }
        SmtpEnvelope.requireNoLineBreaks(host, "host");
        SmtpEnvelope.requireNoLineBreaks(ehloHost, "ehloHost");
    }

    public static SmtpConfig defaults(String host) {
        return new SmtpConfig(
                host,
                DEFAULT_SUBMISSION_PORT,
                defaultEhloHost(),
                Protocol.ESMTP,
                Duration.ofSeconds(60),
                null,
                false,
                TlsConfig.none(),
                AuthConfig.disabled(),
                EsmtpConfig.defaults(),
                TransportConfig.defaults(),
                ProxyConfig.disabled(),
                XclientConfig.disabled());
    }

    public SmtpConfig withHost(String newHost) {
        return new SmtpConfig(
                newHost, port, ehloHost, protocol, timeout, stopAfter, dropAfter, tls, auth, esmtp, transport, proxy,
                xclient);
    }

    public SmtpConfig withPort(int newPort) {
        return new SmtpConfig(
                host, newPort, ehloHost, protocol, timeout, stopAfter, dropAfter, tls, auth, esmtp, transport, proxy,
                xclient);
    }

    public SmtpConfig withEhloHost(String newEhloHost) {
        return new SmtpConfig(
                host,
                port,
                newEhloHost,
                protocol,
                timeout,
                stopAfter,
                dropAfter,
                tls,
                auth,
                esmtp,
                transport,
                proxy,
                xclient);
    }

    public SmtpConfig withProtocol(Protocol newProtocol) {
        return new SmtpConfig(
                host,
                port,
                ehloHost,
                newProtocol,
                timeout,
                stopAfter,
                dropAfter,
                tls,
                auth,
                esmtp,
                transport,
                proxy,
                xclient);
    }

    public SmtpConfig withTimeout(Duration newTimeout) {
        return new SmtpConfig(
                host,
                port,
                ehloHost,
                protocol,
                newTimeout,
                stopAfter,
                dropAfter,
                tls,
                auth,
                esmtp,
                transport,
                proxy,
                xclient);
    }

    public SmtpConfig withStopAfter(Phase newStopAfter, boolean newDropAfter) {
        return new SmtpConfig(
                host,
                port,
                ehloHost,
                protocol,
                timeout,
                newStopAfter,
                newDropAfter,
                tls,
                auth,
                esmtp,
                transport,
                proxy,
                xclient);
    }

    public SmtpConfig withTls(TlsConfig newTls) {
        return new SmtpConfig(
                host, port, ehloHost, protocol, timeout, stopAfter, dropAfter, newTls, auth, esmtp, transport, proxy,
                xclient);
    }

    public SmtpConfig withAuth(AuthConfig newAuth) {
        return new SmtpConfig(
                host, port, ehloHost, protocol, timeout, stopAfter, dropAfter, tls, newAuth, esmtp, transport, proxy,
                xclient);
    }

    public SmtpConfig withEsmtp(EsmtpConfig newEsmtp) {
        return new SmtpConfig(
                host, port, ehloHost, protocol, timeout, stopAfter, dropAfter, tls, auth, newEsmtp, transport, proxy,
                xclient);
    }

    public SmtpConfig withTransport(TransportConfig newTransport) {
        return new SmtpConfig(
                host,
                port,
                ehloHost,
                protocol,
                timeout,
                stopAfter,
                dropAfter,
                tls,
                auth,
                esmtp,
                newTransport,
                proxy,
                xclient);
    }

    public SmtpConfig withProxy(ProxyConfig newProxy) {
        return new SmtpConfig(
                host, port, ehloHost, protocol, timeout, stopAfter, dropAfter, tls, auth, esmtp, transport, newProxy,
                xclient);
    }

    public SmtpConfig withXclient(XclientConfig newXclient) {
        return new SmtpConfig(
                host,
                port,
                ehloHost,
                protocol,
                timeout,
                stopAfter,
                dropAfter,
                tls,
                auth,
                esmtp,
                transport,
                proxy,
                newXclient);
    }

    static String defaultEhloHost() {
        try {
            // getHostName, not getCanonicalHostName: the canonical lookup does a reverse-DNS
            // query that can stall for seconds on misconfigured resolvers.
            var local = InetAddress.getLocalHost().getHostName();
            return local == null || local.isBlank() ? "mailkit.local" : bracketIfAddressLiteral(local);
        } catch (UnknownHostException ignored) {
            return "mailkit.local";
        }
    }

    /** rfc5321 §4.1.3: an IP used as the EHLO identity must be sent as an address literal. */
    static String bracketIfAddressLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return "[IPv6:" + host + "]";
        }
        var digitsAndDots = host.chars().allMatch(character -> character == '.' || Character.isDigit(character));
        return digitsAndDots && host.indexOf('.') >= 0 ? "[" + host + "]" : host;
    }
}
