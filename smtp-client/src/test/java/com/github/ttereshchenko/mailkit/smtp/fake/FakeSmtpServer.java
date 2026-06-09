package com.github.ttereshchenko.mailkit.smtp.fake;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * In-process scriptable SMTP server for unit tests. Binds to an ephemeral port, accepts one
 * connection, and walks a pre-recorded script of {@link Step}s — each step either reads a
 * client line and matches a prefix, reads a DATA payload until the terminating dot-line, or
 * drops the socket. Tests assert against {@link #receivedLines()} and {@link #receivedDataPayload()}
 * after the client finishes.
 */
public final class FakeSmtpServer implements AutoCloseable {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> bannerLines = List.of("220 fake.local ESMTP ready");
        private final List<Step> steps = new ArrayList<>();
        private boolean dropOnConnect;
        private boolean tlsOnConnect;

        public Builder banner(String... lines) {
            bannerLines = List.of(lines);
            return this;
        }

        public Builder dropOnConnect() {
            dropOnConnect = true;
            return this;
        }

        public Builder tlsOnConnect() {
            tlsOnConnect = true;
            return this;
        }

        public Builder expect(String linePrefix, String... reply) {
            steps.add(new Step.ExpectLine(linePrefix, List.of(reply)));
            return this;
        }

        public Builder expectData(String... reply) {
            steps.add(new Step.ExpectData(List.of(reply)));
            return this;
        }

        public Builder expectStartTls(String reply) {
            steps.add(new Step.StartTls(reply));
            return this;
        }

        public Builder expectBdat(String reply) {
            steps.add(new Step.Bdat(reply));
            return this;
        }

        public Builder dropConnection() {
            steps.add(new Step.Drop());
            return this;
        }

        public FakeSmtpServer start() throws IOException {
            var server = new FakeSmtpServer(bannerLines, steps, dropOnConnect, tlsOnConnect);
            server.startListening();
            return server;
        }
    }

    sealed interface Step {
        record ExpectLine(String prefix, List<String> reply) implements Step {}

        record ExpectData(List<String> reply) implements Step {}

        record StartTls(String reply) implements Step {}

        record Bdat(String reply) implements Step {}

        record Drop() implements Step {}
    }

    private final List<String> bannerLines;
    private final List<Step> steps;
    private final boolean dropOnConnect;
    private final boolean tlsOnConnect;
    private final ServerSocket serverSocket;
    private final Thread worker;
    private final List<String> receivedLines = new CopyOnWriteArrayList<>();
    private final AtomicReference<byte[]> receivedDataPayload = new AtomicReference<>(new byte[0]);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private FakeSmtpServer(List<String> bannerLines, List<Step> steps, boolean dropOnConnect, boolean tlsOnConnect)
            throws IOException {
        this.bannerLines = bannerLines;
        this.steps = steps;
        this.dropOnConnect = dropOnConnect;
        this.tlsOnConnect = tlsOnConnect;
        this.serverSocket = new ServerSocket(0);
        this.serverSocket.setSoTimeout(5_000);
        this.worker = new Thread(this::serve, "FakeSmtpServer-" + serverSocket.getLocalPort());
        this.worker.setDaemon(true);
    }

    private void startListening() {
        worker.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public List<String> receivedLines() {
        return Collections.unmodifiableList(new ArrayList<>(receivedLines));
    }

    public byte[] receivedDataPayload() {
        return receivedDataPayload.get().clone();
    }

    public Throwable serverFailure() {
        return failure.get();
    }

    public void awaitCompletion() throws InterruptedException {
        worker.join(5_000);
    }

    @Override
    public void close() throws IOException {
        worker.interrupt();
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    private void serve() {
        try (var rawSocket = serverSocket.accept()) {
            if (dropOnConnect) {
                return;
            }
            rawSocket.setSoTimeout(5_000);
            var connection = new ServerConnection();
            try {
                if (tlsOnConnect) {
                    connection.upgrade(upgradeToTls(rawSocket));
                } else {
                    connection.initialize(rawSocket);
                }
                writeLines(connection.output(), bannerLines);
                for (var step : steps) {
                    if (!handleStep(step, connection)) {
                        return;
                    }
                }
            } finally {
                connection.close();
            }
        } catch (IOException ioFailure) {
            failure.set(ioFailure);
        } catch (RuntimeException unexpected) {
            failure.set(unexpected);
        }
    }

    private boolean handleStep(Step step, ServerConnection connection) throws IOException {
        return switch (step) {
            case Step.ExpectLine expect -> {
                var line = connection.reader().readLine();
                if (line == null) {
                    failure.set(new AssertionError("client closed before " + expect.prefix()));
                    yield false;
                }
                receivedLines.add(line);
                if (!line.startsWith(expect.prefix())) {
                    failure.set(new AssertionError(
                            "expected line starting with '" + expect.prefix() + "' but received '" + line + "'"));
                    yield false;
                }
                writeLines(connection.output(), expect.reply());
                yield true;
            }
            case Step.ExpectData data -> {
                readDataPayload(connection.reader());
                writeLines(connection.output(), data.reply());
                yield true;
            }
            case Step.StartTls startTls -> {
                var line = connection.reader().readLine();
                if (line == null || !line.startsWith("STARTTLS")) {
                    failure.set(new AssertionError("expected STARTTLS but received '" + line + "'"));
                    yield false;
                }
                receivedLines.add(line);
                writeLines(connection.output(), List.of(startTls.reply()));
                connection.upgrade(upgradeToTls(connection.socket()));
                yield true;
            }
            case Step.Bdat bdat -> {
                var line = connection.reader().readLine();
                if (line == null || !line.startsWith("BDAT ")) {
                    failure.set(new AssertionError("expected BDAT but received '" + line + "'"));
                    yield false;
                }
                receivedLines.add(line);
                readBdatPayload(connection, line);
                writeLines(connection.output(), List.of(bdat.reply()));
                yield true;
            }
            case Step.Drop drop -> {
                Objects.requireNonNull(drop);
                connection.close();
                yield false;
            }
        };
    }

    private void readBdatPayload(ServerConnection connection, String bdatLine) throws IOException {
        // Parse: "BDAT <size> [LAST]"
        var parts = bdatLine.split("\\s+");
        if (parts.length < 2) {
            throw new IOException("malformed BDAT line: " + bdatLine);
        }
        int size;
        try {
            size = Integer.parseInt(parts[1]);
        } catch (NumberFormatException badNumber) {
            throw new IOException("bad BDAT size: " + bdatLine, badNumber);
        }
        // BufferedReader has consumed only the BDAT line. The payload starts in the underlying
        // socket's stream — but BufferedReader may have already buffered some payload bytes
        // because it reads ahead during readLine(). The fake's only BDAT consumer is single-chunk
        // tests where the entire payload fits within one readLine call (line-terminated by EOF
        // or by the test harness reading exactly `size` bytes). For simplicity we read remaining
        // bytes from BufferedReader's underlying stream, draining its character buffer first.
        var buffer = new ByteArrayOutputStream(size);
        var remaining = size;
        // Drain anything the BufferedReader has already buffered as chars.
        while (remaining > 0 && connection.reader().ready()) {
            var character = connection.reader().read();
            if (character < 0) {
                break;
            }
            buffer.write((char) character);
            remaining--;
        }
        // Read the rest as bytes via the socket stream directly.
        while (remaining > 0) {
            var read = connection.socket().getInputStream().read();
            if (read < 0) {
                break;
            }
            buffer.write(read);
            remaining--;
        }
        receivedDataPayload.set(buffer.toByteArray());
    }

    private Socket upgradeToTls(Socket underlying) throws IOException {
        SSLContext context;
        try {
            context = TestTlsResources.serverContext();
        } catch (Exception failureLoading) {
            throw new IOException("could not load test TLS context", failureLoading);
        }
        var sslSocket = (SSLSocket) context.getSocketFactory()
                .createSocket(underlying, underlying.getInetAddress().getHostAddress(), underlying.getPort(), true);
        sslSocket.setUseClientMode(false);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static final class ServerConnection {

        private Socket socket;
        private OutputStream output;
        private BufferedReader reader;

        void initialize(Socket fresh) throws IOException {
            socket = fresh;
            output = fresh.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
        }

        void upgrade(Socket upgraded) throws IOException {
            socket = upgraded;
            output = upgraded.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(upgraded.getInputStream(), StandardCharsets.UTF_8));
        }

        Socket socket() {
            return socket;
        }

        OutputStream output() {
            return output;
        }

        BufferedReader reader() {
            return reader;
        }

        void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void readDataPayload(BufferedReader reader) throws IOException {
        var buffer = new ByteArrayOutputStream();
        while (true) {
            var line = reader.readLine();
            if (line == null) {
                throw new IOException("client closed mid-DATA");
            }
            if (line.equals(".")) {
                receivedDataPayload.set(buffer.toByteArray());
                return;
            }
            // RFC 5321 dot-stuffing: a leading "." on the wire is the client's stuffing — strip one.
            var payload = line.startsWith(".") ? line.substring(1) : line;
            buffer.write(payload.getBytes(StandardCharsets.UTF_8));
            buffer.write('\r');
            buffer.write('\n');
        }
    }

    private void writeLines(OutputStream output, List<String> lines) throws IOException {
        for (var line : lines) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.write('\r');
            output.write('\n');
        }
        output.flush();
    }
}
