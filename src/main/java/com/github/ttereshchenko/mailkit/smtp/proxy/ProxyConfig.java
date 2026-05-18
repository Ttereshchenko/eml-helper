package com.github.ttereshchenko.mailkit.smtp.proxy;

import java.util.Objects;

/**
 * Per-send HAProxy PROXY protocol header configuration. When non-disabled, a v1 ASCII or v2 binary
 * header is written to the socket BEFORE any SMTP byte — this lets a downstream MTA that expects
 * PROXY-protocol-wrapped connections see the spoofed client identity.
 */
public record ProxyConfig(
        Version version,
        Command command,
        Family family,
        String sourceAddress,
        int sourcePort,
        String destAddress,
        int destPort) {

    public enum Version {
        /** Disabled — no PROXY header is written. */
        NONE,
        /** ASCII line: {@code PROXY TCP4 src dst sp dp\r\n}. */
        V1,
        /** Binary header: 12-byte signature + version/cmd + family/proto + length + payload. */
        V2
    }

    public enum Command {
        /** PROXY: the connection is a proxied one with the addresses below. */
        PROXY,
        /** LOCAL: the connection is local to the proxy itself (v2 only). */
        LOCAL
    }

    public enum Family {
        TCP4,
        TCP6
    }

    public ProxyConfig {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(family, "family");
        sourceAddress = sourceAddress == null ? "" : sourceAddress;
        destAddress = destAddress == null ? "" : destAddress;
    }

    public static ProxyConfig disabled() {
        return new ProxyConfig(Version.NONE, Command.PROXY, Family.TCP4, "", 0, "", 0);
    }

    public static ProxyConfig v1Tcp4(String sourceAddress, int sourcePort, String destAddress, int destPort) {
        return new ProxyConfig(
                Version.V1, Command.PROXY, Family.TCP4, sourceAddress, sourcePort, destAddress, destPort);
    }

    public static ProxyConfig v2Tcp4(String sourceAddress, int sourcePort, String destAddress, int destPort) {
        return new ProxyConfig(
                Version.V2, Command.PROXY, Family.TCP4, sourceAddress, sourcePort, destAddress, destPort);
    }

    public boolean isEnabled() {
        return version != Version.NONE;
    }
}
