package com.github.ttereshchenko.mailkit.smtp.transport;

import java.util.Objects;

/**
 * Per-send transport knobs: IP family, local interface / port bind, and whether to derive the
 * destination host from the {@code MAIL FROM} domain via DNS MX (swaks's {@code --copy-routing}).
 */
public record TransportConfig(IpFamily ipFamily, String localInterface, Integer localPort, boolean useMxRouting) {

    public TransportConfig {
        Objects.requireNonNull(ipFamily, "ipFamily");
        if (localPort != null && (localPort < 0 || localPort > 65535)) {
            throw new IllegalArgumentException("localPort out of range: " + localPort);
        }
    }

    public static TransportConfig defaults() {
        return new TransportConfig(IpFamily.AUTO, null, null, false);
    }

    public TransportConfig withIpFamily(IpFamily family) {
        return new TransportConfig(family, localInterface, localPort, useMxRouting);
    }

    public TransportConfig withLocalInterface(String iface) {
        return new TransportConfig(ipFamily, iface, localPort, useMxRouting);
    }

    public TransportConfig withLocalPort(Integer port) {
        return new TransportConfig(ipFamily, localInterface, port, useMxRouting);
    }

    public TransportConfig withMxRouting(boolean useMx) {
        return new TransportConfig(ipFamily, localInterface, localPort, useMx);
    }
}
