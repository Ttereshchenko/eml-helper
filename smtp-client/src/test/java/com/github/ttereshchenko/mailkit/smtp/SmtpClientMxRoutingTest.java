package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.transport.MxResolver;
import com.github.ttereshchenko.mailkit.smtp.transport.TcpConnector;
import com.github.ttereshchenko.mailkit.smtp.transport.TransportConfig;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import org.junit.jupiter.api.Test;

/**
 * The MX-routing wiring inside {@link SmtpClient}: domain extraction, null-MX refusal (rfc7505),
 * fallback to A/AAAA when no MX exists, fallback to the next MX host when the best one is
 * unreachable (rfc5321 §5.1), lookup failures, and the private-target guard.
 */
class SmtpClientMxRoutingTest {

    private static MxResolver resolverWith(Map<String, List<String>> mxByDomain) {
        return new MxResolver(() -> (DirContext) Proxy.newProxyInstance(
                DirContext.class.getClassLoader(), new Class<?>[] {DirContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAttributes")) {
                        var attributes = new BasicAttributes();
                        var records = mxByDomain.get((String) args[0]);
                        if (records != null) {
                            var attribute = new BasicAttribute("MX");
                            records.forEach(attribute::add);
                            attributes.put(attribute);
                        }
                        return attributes;
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static TcpConnector loopbackConnectorFor(Map<String, String> hostToIp) {
        return new TcpConnector(host -> {
            var ipLiteral = hostToIp.get(host);
            if (ipLiteral == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(ipLiteral)};
        });
    }

    private static SmtpConfig mxConfig(int port, boolean allowPrivateTargets) {
        var transport = TransportConfig.defaults().withMxRouting(true).withAllowPrivateMxTargets(allowPrivateTargets);
        return SmtpConfig.defaults("unused.invalid").withPort(port).withTransport(transport);
    }

    private static SendResult sendVia(SmtpClient client, SmtpConfig config) throws SmtpException {
        return client.send(
                config, SmtpEnvelope.of("from@sender.example", "to@example.com"), MessageSource.ofString("body"));
    }

    private static FakeSmtpServer happyServer() throws Exception {
        return FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start();
    }

    @Test
    void mailFromWithoutDomainFailsBeforeConnecting() throws Exception {
        var client = new SmtpClient(new TcpConnector(), resolverWith(Map.of()));
        var config = mxConfig(2525, true);
        try {
            client.send(config, SmtpEnvelope.of("bare-localpart", "to@example.com"), MessageSource.ofString("x"));
            fail("expected CONNECT_FAILED");
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.CONNECT_FAILED, failure.kind());
            assertTrue(failure.getMessage().contains("no domain"), failure.getMessage());
        }
    }

    @Test
    void nullMxRefusesToSendPerRfc7505() throws Exception {
        var resolver = resolverWith(Map.of("sender.example", List.of("0 .")));
        var client = new SmtpClient(new TcpConnector(), resolver);
        try {
            sendVia(client, mxConfig(2525, true));
            fail("expected CONNECT_FAILED");
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.CONNECT_FAILED, failure.kind());
            assertTrue(failure.getMessage().contains("rfc7505"), failure.getMessage());
        }
    }

    @Test
    void mxLookupFailureSurfacesConnectFailed() throws Exception {
        var resolver = new MxResolver(() -> {
            throw new NamingException("dns broken");
        });
        var client = new SmtpClient(new TcpConnector(), resolver);
        try {
            sendVia(client, mxConfig(2525, true));
            fail("expected CONNECT_FAILED");
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.CONNECT_FAILED, failure.kind());
            assertTrue(failure.getMessage().contains("MX lookup failed"), failure.getMessage());
        }
    }

    @Test
    void noMxRecordsFallsBackToTheDomainItself() throws Exception {
        try (var server = happyServer()) {
            var resolver = resolverWith(Map.of("sender.example", List.of()));
            var connector = loopbackConnectorFor(Map.of("sender.example", "127.0.0.1"));
            var client = new SmtpClient(connector, resolver);

            var result = sendVia(client, mxConfig(server.port(), true));

            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void unreachableBestMxFallsBackToNextPreference() throws Exception {
        try (var server = happyServer()) {
            var resolver = resolverWith(Map.of("sender.example", List.of("10 mx1.example", "20 mx2.example")));
            // mx1 does not resolve at all; mx2 points at the fake server.
            var connector = loopbackConnectorFor(Map.of("mx2.example", "127.0.0.1"));
            var client = new SmtpClient(connector, resolver);

            var result = sendVia(client, mxConfig(server.port(), true));

            assertTrue(result.cleanlyClosed());
            var transcriptText = result.transcript().render(false);
            assertTrue(
                    transcriptText.contains("could not connect to mx1.example"),
                    "transcript should note the failed MX: " + transcriptText);
        }
    }

    @Test
    void privateMxTargetsAreSkippedWithoutOptIn() throws Exception {
        var resolver = resolverWith(Map.of("sender.example", List.of("10 mx1.example")));
        var connector = loopbackConnectorFor(Map.of("mx1.example", "127.0.0.1"));
        var client = new SmtpClient(connector, resolver);
        try {
            sendVia(client, mxConfig(2525, false));
            fail("expected CONNECT_FAILED");
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.CONNECT_FAILED, failure.kind());
            assertTrue(failure.getMessage().contains("no addresses match"), failure.getMessage());
        }
    }
}
