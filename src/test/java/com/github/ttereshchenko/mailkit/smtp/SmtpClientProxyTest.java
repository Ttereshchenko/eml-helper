package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyConfig;
import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyV2Writer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks that the PROXY header is sent on the wire BEFORE any SMTP byte. The fake
 * server here is hand-rolled (rather than {@link com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer})
 * because PROXY headers are read raw (v2 is binary) before the SMTP exchange begins.
 */
class SmtpClientProxyTest {

    @Test
    void v1ProxyLineIsWrittenBeforeBanner() throws Exception {
        var captured = new AtomicReference<byte[]>(new byte[0]);
        try (var server = new ServerSocket(0)) {
            var serverThread = new Thread(() -> runProxyV1Server(server, captured), "proxy-v1-server");
            serverThread.setDaemon(true);
            serverThread.start();

            var config = SmtpConfig.defaults("127.0.0.1")
                    .withPort(server.getLocalPort())
                    .withTimeout(Duration.ofSeconds(5))
                    .withProxy(ProxyConfig.v1Tcp4("198.51.100.7", 56324, "203.0.113.5", 25));

            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: hi\r\n\r\nbody\r\n"));

            serverThread.join(3_000);
            var captureBytes = captured.get();
            var captureText = new String(captureBytes, StandardCharsets.US_ASCII);
            assertTrue(captureText.startsWith("PROXY TCP4 198.51.100.7"), captureText);
            assertEquals(
                    "PROXY TCP4 198.51.100.7 203.0.113.5 56324 25\r\n",
                    captureText.substring(0, "PROXY TCP4 198.51.100.7 203.0.113.5 56324 25\r\n".length()));
        }
    }

    @Test
    void v2ProxyBinaryHeaderIsWrittenBeforeBanner() throws Exception {
        var captured = new AtomicReference<byte[]>(new byte[0]);
        try (var server = new ServerSocket(0)) {
            var serverThread = new Thread(() -> runProxyV2Server(server, captured), "proxy-v2-server");
            serverThread.setDaemon(true);
            serverThread.start();

            var proxy = ProxyConfig.v2Tcp4("198.51.100.7", 56324, "203.0.113.5", 25);
            var config = SmtpConfig.defaults("127.0.0.1")
                    .withPort(server.getLocalPort())
                    .withTimeout(Duration.ofSeconds(5))
                    .withProxy(proxy);

            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: hi\r\n\r\nbody\r\n"));

            serverThread.join(3_000);
            assertArrayEquals(ProxyV2Writer.format(proxy), captured.get());
        }
    }

    private static void runProxyV1Server(ServerSocket server, AtomicReference<byte[]> captured) {
        try (var socket = server.accept()) {
            var input = socket.getInputStream();
            var buffer = new ByteArrayOutputStream();
            // Read until \r\n
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                buffer.write(current);
                if (previous == '\r' && current == '\n') {
                    break;
                }
                previous = current;
            }
            captured.set(buffer.toByteArray());

            var output = socket.getOutputStream();
            output.write("220 fake.local ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            walkBasicSmtp(socket, input, output);
        } catch (IOException ignored) {
            // server-side errors are surfaced via the client's transcript / exception
        }
    }

    private static void runProxyV2Server(ServerSocket server, AtomicReference<byte[]> captured) {
        try (var socket = server.accept()) {
            var input = socket.getInputStream();
            var header = input.readNBytes(16);
            var payloadLength = ((header[14] & 0xFF) << 8) | (header[15] & 0xFF);
            var payload = input.readNBytes(payloadLength);
            var combined = new byte[header.length + payload.length];
            System.arraycopy(header, 0, combined, 0, header.length);
            System.arraycopy(payload, 0, combined, header.length, payload.length);
            captured.set(combined);

            var output = socket.getOutputStream();
            output.write("220 fake.local ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            walkBasicSmtp(socket, input, output);
        } catch (IOException ignored) {
            // ignored as above
        }
    }

    private static void walkBasicSmtp(java.net.Socket socket, java.io.InputStream input, java.io.OutputStream output)
            throws IOException {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        // Drive a minimal SMTP flow so the client send() returns normally.
        readLineAndReply(reader, output, "250-fake.local\r\n250 OK\r\n"); // EHLO
        readLineAndReply(reader, output, "250 OK\r\n"); // MAIL FROM
        readLineAndReply(reader, output, "250 OK\r\n"); // RCPT TO
        readLineAndReply(reader, output, "354 go\r\n"); // DATA
        // Read payload until "."
        String line;
        while ((line = reader.readLine()) != null) {
            if (".".equals(line)) {
                break;
            }
        }
        output.write("250 queued\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        readLineAndReply(reader, output, "221 bye\r\n"); // QUIT
        socket.close();
    }

    private static void readLineAndReply(java.io.BufferedReader reader, java.io.OutputStream output, String reply)
            throws IOException {
        reader.readLine();
        output.write(reply.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }
}
