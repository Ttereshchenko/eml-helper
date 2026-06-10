package com.github.ttereshchenko.mailkit.smtp.transport;

import java.util.Objects;

/**
 * Per-send transport knobs: IP family, local interface / port bind, and whether to derive the
 * destination host from the {@code MAIL FROM} domain via DNS MX (swaks's {@code --copy-routing}).
 *
 * <p><b>MX routing routes on the sender domain</b> — recipients' domains are never consulted.
 * This mirrors swaks; it is NOT general direct-to-recipient delivery. When MX routing is on,
 * targets that resolve to loopback / private / link-local addresses are skipped unless
 * {@link #allowPrivateMxTargets} is set (guards against DNS answers steering the client into the
 * local network).
 */
public record TransportConfig(
        IpFamily ipFamily,
        String localInterface,
        Integer localPort,
        boolean useMxRouting,
        boolean allowPrivateMxTargets) {

    public TransportConfig {
        Objects.requireNonNull(ipFamily, "ipFamily");
        if (localPort != null && (localPort < 0 || localPort > 65535)) {
            throw new IllegalArgumentException("localPort out of range: " + localPort);
        }
    }

    /** Compatibility constructor — private MX targets stay disallowed. */
    public TransportConfig(IpFamily ipFamily, String localInterface, Integer localPort, boolean useMxRouting) {
        this(ipFamily, localInterface, localPort, useMxRouting, false);
    }

    public static TransportConfig defaults() {
        return new TransportConfig(IpFamily.AUTO, null, null, false, false);
    }

    public TransportConfig withIpFamily(IpFamily family) {
        return new TransportConfig(family, localInterface, localPort, useMxRouting, allowPrivateMxTargets);
    }

    public TransportConfig withLocalInterface(String iface) {
        return new TransportConfig(ipFamily, iface, localPort, useMxRouting, allowPrivateMxTargets);
    }

    public TransportConfig withLocalPort(Integer port) {
        return new TransportConfig(ipFamily, localInterface, port, useMxRouting, allowPrivateMxTargets);
    }

    public TransportConfig withMxRouting(boolean useMx) {
        return new TransportConfig(ipFamily, localInterface, localPort, useMx, allowPrivateMxTargets);
    }

    public TransportConfig withAllowPrivateMxTargets(boolean allow) {
        return new TransportConfig(ipFamily, localInterface, localPort, useMxRouting, allow);
    }
}
