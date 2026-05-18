package com.github.ttereshchenko.mailkit.smtp.tls;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * TLS knobs for a single SMTP transaction. Mirrors swaks's {@code --tls*} option family so users
 * can reason about behavior in familiar terms. {@code allowSelfSigned} is intentionally
 * per-send-only — it is never persisted into a {@link com.github.ttereshchenko.mailkit.smtp.SmtpConfig
 * SmtpConfig} via a saved profile.
 */
public record TlsConfig(
        Mode mode,
        List<String> protocols,
        List<String> cipherSuites,
        boolean verifyCa,
        boolean verifyHostname,
        String hostnameOverride,
        Path caBundlePath,
        Path clientCertPath,
        Path clientKeyPath,
        Path clientChainPath,
        String sniHost,
        boolean allowSelfSigned) {

    public enum Mode {
        /** Plain socket — no TLS negotiation. */
        NONE,
        /** STARTTLS must be advertised; failure to negotiate is fatal. */
        STARTTLS_REQUIRED,
        /** STARTTLS used when advertised; falls through if not. */
        STARTTLS_OPTIONAL,
        /** STARTTLS used when advertised; failure to negotiate is fatal but absence is fine. */
        STARTTLS_OPTIONAL_STRICT,
        /** TLS-on-connect (implicit TLS, traditional port 465). */
        TLS_ON_CONNECT
    }

    public TlsConfig {
        Objects.requireNonNull(mode, "mode");
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
        cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
    }

    public static TlsConfig none() {
        return new TlsConfig(Mode.NONE, List.of(), List.of(), true, true, null, null, null, null, null, null, false);
    }

    public static TlsConfig starttlsRequired() {
        return new TlsConfig(
                Mode.STARTTLS_REQUIRED, List.of(), List.of(), true, true, null, null, null, null, null, null, false);
    }

    public static TlsConfig starttlsOptional() {
        return new TlsConfig(
                Mode.STARTTLS_OPTIONAL, List.of(), List.of(), true, true, null, null, null, null, null, null, false);
    }

    public TlsConfig withMode(Mode newMode) {
        return new TlsConfig(
                newMode,
                protocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                hostnameOverride,
                caBundlePath,
                clientCertPath,
                clientKeyPath,
                clientChainPath,
                sniHost,
                allowSelfSigned);
    }

    public static TlsConfig tlsOnConnect() {
        return new TlsConfig(
                Mode.TLS_ON_CONNECT, List.of(), List.of(), true, true, null, null, null, null, null, null, false);
    }

    public boolean negotiateTls() {
        return mode != Mode.NONE;
    }

    public TlsConfig withAllowSelfSigned(boolean allow) {
        return new TlsConfig(
                mode,
                protocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                hostnameOverride,
                caBundlePath,
                clientCertPath,
                clientKeyPath,
                clientChainPath,
                sniHost,
                allow);
    }

    public TlsConfig withProtocols(List<String> newProtocols) {
        return new TlsConfig(
                mode,
                newProtocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                hostnameOverride,
                caBundlePath,
                clientCertPath,
                clientKeyPath,
                clientChainPath,
                sniHost,
                allowSelfSigned);
    }

    public TlsConfig withCaBundle(Path newCaBundlePath) {
        return new TlsConfig(
                mode,
                protocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                hostnameOverride,
                newCaBundlePath,
                clientCertPath,
                clientKeyPath,
                clientChainPath,
                sniHost,
                allowSelfSigned);
    }

    public TlsConfig withClientCertificate(Path certPath, Path keyPath, Path chainPath) {
        return new TlsConfig(
                mode,
                protocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                hostnameOverride,
                caBundlePath,
                certPath,
                keyPath,
                chainPath,
                sniHost,
                allowSelfSigned);
    }

    public TlsConfig withHostnameOverride(String overrideHost) {
        return new TlsConfig(
                mode,
                protocols,
                cipherSuites,
                verifyCa,
                verifyHostname,
                overrideHost,
                caBundlePath,
                clientCertPath,
                clientKeyPath,
                clientChainPath,
                sniHost,
                allowSelfSigned);
    }
}
