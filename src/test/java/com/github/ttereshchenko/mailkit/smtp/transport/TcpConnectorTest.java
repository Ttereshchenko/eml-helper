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
}
