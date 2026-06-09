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
 * Wire-level knobs for a single SMTP transaction. Each phase folds in more knobs without changing
 * the record's public shape — callers reach the new bits via dedicated {@code with*} helpers.
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
        SMTP,
        ESMTP,
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
            var local = InetAddress.getLocalHost().getCanonicalHostName();
            return local == null || local.isBlank() ? "mailkit.local" : local;
        } catch (UnknownHostException ignored) {
            return "mailkit.local";
        }
    }
}
