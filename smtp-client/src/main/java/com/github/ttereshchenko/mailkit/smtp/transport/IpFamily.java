package com.github.ttereshchenko.mailkit.smtp.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Mirrors swaks's {@code -4} / {@code -6} switches: pick which IP family to dial out on. */
public enum IpFamily {
    AUTO,
    IPV4,
    IPV6;

    public boolean matches(InetAddress address) {
        return switch (this) {
            case AUTO -> true;
            case IPV4 -> address instanceof Inet4Address;
            case IPV6 -> address instanceof Inet6Address;
        };
    }
}
