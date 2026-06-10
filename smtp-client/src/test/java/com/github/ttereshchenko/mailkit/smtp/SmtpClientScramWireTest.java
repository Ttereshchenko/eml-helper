package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Drives SCRAM-SHA-256 through {@code SmtpClient} against a real (test-local) server-side SCRAM
 * implementation, covering both placements of the server-final message: a last {@code 334}
 * challenge answered with an empty line, and additional data riding inside the {@code 235}
 * success reply (rfc4954 §4 — regression for F7). Also proves a tampered server signature is
 * rejected as AUTH_FAILED (F5: mechanism exceptions must not escape unchecked).
 */
class SmtpClientScramWireTest {

    private static final String PASSWORD = "pencil";

    private enum ServerFinalPlacement {
        IN_334,
        IN_235,
        TAMPERED_IN_334
    }

    @Test
    void scramSha256CompletesWhenServerFinalArrivesIn334() throws Exception {
        runScramScenario(ServerFinalPlacement.IN_334);
    }

    @Test
    void scramSha256CompletesWhenServerFinalRidesInThe235Reply() throws Exception {
        runScramScenario(ServerFinalPlacement.IN_235);
    }

    @Test
    void tamperedServerSignatureSurfacesAuthFailed() throws Exception {
        try (var server = new ScramServer(ServerFinalPlacement.TAMPERED_IN_334)) {
            var config = clientConfig(server);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertEquals(Phase.AUTH, failure.phase());
                assertTrue(
                        failure.getMessage().contains("signature"),
                        "failure should mention the bad server signature: " + failure.getMessage());
            }
        }
    }

    private void runScramScenario(ServerFinalPlacement placement) throws Exception {
        try (var server = new ScramServer(placement)) {
            var config = clientConfig(server);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\n\nbody\n"));

            assertTrue(result.cleanlyClosed());
            assertNull(server.failure.get(), String.valueOf(server.failure.get()));
            assertTrue(server.proofVerified.get(), "server must have verified the client proof");
        }
    }

    private static SmtpConfig clientConfig(ScramServer server) {
        var auth =
                AuthConfig.forMechanism(AuthMechanism.SCRAM_SHA_256, AuthCredentials.of("user", PASSWORD::toCharArray));
        return SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withAuth(auth);
    }

    /** Minimal SMTP server that actually performs the server side of RFC 7677 SCRAM-SHA-256. */
    private static final class ScramServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread worker;
        private final ServerFinalPlacement placement;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Boolean> proofVerified = new AtomicReference<>(false);

        ScramServer(ServerFinalPlacement placement) throws IOException {
            this.placement = placement;
            this.serverSocket = new ServerSocket(0);
            this.serverSocket.setSoTimeout(5_000);
            this.worker = new Thread(this::serve, "scram-test-server");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            try (var socket = serverSocket.accept()) {
                socket.setSoTimeout(5_000);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var output = socket.getOutputStream();
                writeLine(output, "220 scram.test ESMTP");
                expectPrefix(reader.readLine(), "EHLO ");
                writeLine(output, "250-scram.test");
                writeLine(output, "250 AUTH SCRAM-SHA-256");

                var authLine = reader.readLine();
                expectPrefix(authLine, "AUTH SCRAM-SHA-256 ");
                var clientFirst = new String(
                        Base64.getDecoder().decode(authLine.substring("AUTH SCRAM-SHA-256 ".length())),
                        StandardCharsets.UTF_8);
                if (!clientFirst.startsWith("n,,")) {
                    throw new IOException("unexpected GS2 header: " + clientFirst);
                }
                var clientFirstBare = clientFirst.substring(3);
                var clientNonce = attribute(clientFirstBare, "r");

                var combinedNonce = clientNonce + "srvNonce123";
                var salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
                var iterations = 4096;
                var serverFirst =
                        "r=" + combinedNonce + ",s=" + Base64.getEncoder().encodeToString(salt) + ",i=" + iterations;
                writeLine(output, "334 " + base64(serverFirst));

                var clientFinal = new String(Base64.getDecoder().decode(reader.readLine()), StandardCharsets.UTF_8);
                var proofIndex = clientFinal.lastIndexOf(",p=");
                var clientFinalWithoutProof = clientFinal.substring(0, proofIndex);
                var proof = Base64.getDecoder().decode(clientFinal.substring(proofIndex + ",p=".length()));

                var saltedPassword = pbkdf2(PASSWORD.toCharArray(), salt, iterations);
                var clientKey = hmac(saltedPassword, "Client Key");
                var storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
                var authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof;
                var clientSignature = hmac(storedKey, authMessage);
                var recoveredClientKey = xor(proof, clientSignature);
                var recoveredStoredKey = MessageDigest.getInstance("SHA-256").digest(recoveredClientKey);
                if (!MessageDigest.isEqual(storedKey, recoveredStoredKey)) {
                    writeLine(output, "535 5.7.8 bad proof");
                    throw new IOException("client proof did not verify");
                }
                proofVerified.set(true);

                var serverKey = hmac(saltedPassword, "Server Key");
                var serverSignature = hmac(serverKey, authMessage);
                if (placement == ServerFinalPlacement.TAMPERED_IN_334) {
                    serverSignature[0] ^= 0x55;
                }
                var serverFinal = "v=" + Base64.getEncoder().encodeToString(serverSignature);
                switch (placement) {
                    case IN_334, TAMPERED_IN_334 -> {
                        writeLine(output, "334 " + base64(serverFinal));
                        var emptyAck = reader.readLine();
                        if (emptyAck == null) {
                            return; // client aborted (tampered-signature scenario)
                        }
                        writeLine(output, "235 2.7.0 authenticated");
                    }
                    case IN_235 -> writeLine(output, "235 " + base64(serverFinal));
                }

                expectPrefix(reader.readLine(), "MAIL FROM:");
                writeLine(output, "250 OK");
                expectPrefix(reader.readLine(), "RCPT TO:");
                writeLine(output, "250 OK");
                expectPrefix(reader.readLine(), "DATA");
                writeLine(output, "354 go");
                String line;
                while ((line = reader.readLine()) != null && !line.equals(".")) {
                    // drain message data
                }
                writeLine(output, "250 queued");
                expectPrefix(reader.readLine(), "QUIT");
                writeLine(output, "221 bye");
            } catch (Exception unexpected) {
                failure.compareAndSet(null, unexpected);
            }
        }

        private static void expectPrefix(String line, String prefix) throws IOException {
            if (line == null || !line.startsWith(prefix)) {
                throw new IOException("expected '" + prefix + "...' but got '" + line + "'");
            }
        }

        private static void writeLine(OutputStream output, String line) throws IOException {
            output.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static String base64(String text) {
            return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        private static String attribute(String message, String name) throws IOException {
            var map = new HashMap<String, String>();
            for (var token : message.split(",")) {
                var equals = token.indexOf('=');
                if (equals > 0) {
                    map.put(token.substring(0, equals), token.substring(equals + 1));
                }
            }
            return mapValue(map, name);
        }

        private static String mapValue(Map<String, String> map, String name) throws IOException {
            var value = map.get(name);
            if (value == null) {
                throw new IOException("missing SCRAM attribute: " + name);
            }
            return value;
        }

        private static byte[] hmac(byte[] key, String data) throws Exception {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
            var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(new PBEKeySpec(password, salt, iterations, 256))
                    .getEncoded();
        }

        private static byte[] xor(byte[] left, byte[] right) {
            var result = new byte[left.length];
            for (var index = 0; index < left.length; index++) {
                result[index] = (byte) (left[index] ^ right[index]);
            }
            return result;
        }

        @Override
        public void close() throws IOException {
            worker.interrupt();
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }
}
