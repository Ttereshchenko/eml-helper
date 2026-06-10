package com.github.ttereshchenko.mailkit.smtp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TcpConnectorTest {

    @Test
    void connectsToLoopbackWithDefaultIpFamily() throws Exception {
        try (var server = new ServerSocket(0)) {
            var socket = new TcpConnector()
                    .connect("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2), TransportConfig.defaults());
            assertNotNull(socket);
            assertTrue(socket.isConnected());
            socket.close();
        }
    }

    @Test
    void ipv4OnlyFilterPicksIpv4Address() throws Exception {
        try (var server = new ServerSocket(0)) {
            var config = TransportConfig.defaults().withIpFamily(IpFamily.IPV4);
            var socket = new TcpConnector().connect("localhost", server.getLocalPort(), Duration.ofSeconds(2), config);
            assertTrue(
                    socket.getInetAddress() instanceof Inet4Address,
                    "expected an Inet4Address, got: " + socket.getInetAddress());
            socket.close();
        }
    }

    @Test
    void noMatchingFamilyRaisesIoException() throws Exception {
        TcpConnector.AddressResolver resolver = host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        var connector = new TcpConnector(resolver);
        try (var server = new ServerSocket(0)) {
            try {
                connector.connect(
                        "any-host",
                        server.getLocalPort(),
                        Duration.ofSeconds(2),
                        TransportConfig.defaults().withIpFamily(IpFamily.IPV6));
                fail("expected IOException — no IPv6 candidate exists");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("IPV6"), expected.getMessage());
            }
        }
    }

    @Test
    void localBindHonoursTheRequestedInterfaceAndPort() throws Exception {
        try (var server = new ServerSocket(0)) {
            var config = TransportConfig.defaults().withLocalInterface("127.0.0.1");
            var socket = new TcpConnector().connect("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2), config);
            assertEquals("127.0.0.1", socket.getLocalAddress().getHostAddress());
            socket.close();
        }
    }

    @Test
    void mxModeFiltersPrivateRangesIncludingUlaAndCgnat() throws Exception {
        TcpConnector.AddressResolver resolver = host -> new InetAddress[] {
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("10.1.2.3"),
            InetAddress.getByName("100.64.0.1"), // CGNAT 100.64/10
            InetAddress.getByName("fd00::1") // IPv6 ULA fc00::/7
        };
        var connector = new TcpConnector(resolver);
        var config = TransportConfig.defaults().withMxRouting(true);
        try {
            connector.connect("mx.example", 25, Duration.ofSeconds(1), config);
            fail("expected IOException — every candidate is in a private range");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no addresses match"), expected.getMessage());
        }
    }

    @Test
    void mxModeAllowsPrivateTargetsWhenExplicitlyOptedIn() throws Exception {
        try (var server = new ServerSocket(0)) {
            TcpConnector.AddressResolver resolver = host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            var connector = new TcpConnector(resolver);
            var config = TransportConfig.defaults().withMxRouting(true).withAllowPrivateMxTargets(true);

            var socket = connector.connect("mx.example", server.getLocalPort(), Duration.ofSeconds(2), config);

            assertTrue(socket.isConnected());
            socket.close();
        }
    }
}
