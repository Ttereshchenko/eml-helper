package com.github.ttereshchenko.mailkit.smtp.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class IpFamilyTest {

    @Test
    void autoAcceptsBothFamilies() throws Exception {
        var ipv4 = InetAddress.getByName("127.0.0.1");
        var ipv6 = InetAddress.getByName("::1");
        assertTrue(IpFamily.AUTO.matches(ipv4));
        assertTrue(IpFamily.AUTO.matches(ipv6));
    }

    @Test
    void ipv4FilterAcceptsOnlyInet4() throws Exception {
        assertTrue(IpFamily.IPV4.matches(InetAddress.getByName("127.0.0.1")));
        assertFalse(IpFamily.IPV4.matches(InetAddress.getByName("::1")));
    }

    @Test
    void ipv6FilterAcceptsOnlyInet6() throws Exception {
        assertTrue(IpFamily.IPV6.matches(InetAddress.getByName("::1")));
        assertFalse(IpFamily.IPV6.matches(InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void inet4AndInet6ResolveAsExpected() throws Exception {
        // Sanity check on the JDK API the enum delegates to.
        assertTrue(InetAddress.getByName("127.0.0.1") instanceof Inet4Address);
        assertTrue(InetAddress.getByName("::1") instanceof Inet6Address);
    }
}
