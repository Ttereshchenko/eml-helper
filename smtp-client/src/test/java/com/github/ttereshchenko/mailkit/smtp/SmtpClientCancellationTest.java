package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.Test;

class SmtpClientCancellationTest {

    @Test
    void cancelDuringBlockedReadSurfacesCancelledNotTimeout() throws Exception {
        // A server that accepts the connection but never sends the banner parks the client inside
        // readResponse(BANNER). Before the fix the cancellation token was only polled between
        // phases, so the blocked read waited out the full SO_TIMEOUT and surfaced as TIMEOUT; now a
        // watcher closes the socket on the first observed cancel and the send fails fast as CANCELLED.
        try (var silentServer = new ServerSocket(0)) {
            silentServer.setSoTimeout(5_000);
            var heldSocket = new ArrayBlockingQueue<Socket>(1);
            var acceptor = new Thread(() -> {
                try {
                    heldSocket.add(silentServer.accept());
                } catch (IOException ignored) {
                    // server closed by the test teardown — nothing to do
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            var startNanos = System.nanoTime();
            CancellationToken cancelAfterDelay = () ->
                    System.nanoTime() - startNanos > Duration.ofMillis(200).toNanos();

            var config = SmtpConfig.defaults("127.0.0.1")
                    .withPort(silentServer.getLocalPort())
                    .withTimeout(Duration.ofSeconds(3));
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"),
                                cancelAfterDelay,
                                SmtpTranscript.NULL_LISTENER);
                fail("expected CANCELLED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.CANCELLED, failure.kind());
            } finally {
                var socket = heldSocket.poll();
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }
}
