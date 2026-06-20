package com.github.ttereshchenko.mailkit.smtp;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthClient;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthClients;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanismSelector;
import com.github.ttereshchenko.mailkit.smtp.esmtp.DsnEnvelopeFormatter;
import com.github.ttereshchenko.mailkit.smtp.esmtp.EhloResponseParser;
import com.github.ttereshchenko.mailkit.smtp.esmtp.EightBitMimeDetector;
import com.github.ttereshchenko.mailkit.smtp.esmtp.SizePreflight;
import com.github.ttereshchenko.mailkit.smtp.esmtp.Smtputf8Detector;
import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyV1Writer;
import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyV2Writer;
import com.github.ttereshchenko.mailkit.smtp.tls.PeerCertExtractor;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsContextFactory;
import com.github.ttereshchenko.mailkit.smtp.transport.MxResolver;
import com.github.ttereshchenko.mailkit.smtp.transport.TcpConnector;
import com.github.ttereshchenko.mailkit.smtp.xclient.XclientCommandBuilder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.naming.NamingException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Drives a single SMTP / ESMTP transaction. Owns no state between calls; each {@link #send} runs
 * against a fresh socket. Covers STARTTLS and TLS-on-connect, SASL authentication, PIPELINING,
 * CHUNKING (BDAT), PRDR, DSN parameters, the PROXY protocol preamble, and XCLIENT.
 */
public final class SmtpClient {

    private static final byte[] DOT_CRLF = {'.', '\r', '\n'};
    private static final byte[] CRLF = {'\r', '\n'};
    private static final int CHUNK_SIZE = 8192;
    private static final int BDAT_CHUNK_SIZE = 256 * 1024;
    // rfc5321 §4.5.3.1.6: a text line including its CRLF must not exceed 1000 octets — 998 of content.
    private static final int MAX_WIRE_LINE_CONTENT_OCTETS = 998;
    // Bounds for server replies: a hostile or broken server must not be able to grow client
    // memory without limit by streaming endless "250-..." continuations or a newline-free line.
    private static final int MAX_REPLY_LINES = 500;
    private static final int MAX_REPLY_LINE_CHARS = 8192;

    private final TcpConnector connector;
    private final MxResolver mxResolver;

    public SmtpClient() {
        this(new TcpConnector(), new MxResolver());
    }

    /** Seam for custom transports and DNS resolution (and for tests that fake either). */
    public SmtpClient(TcpConnector connector, MxResolver mxResolver) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.mxResolver = Objects.requireNonNull(mxResolver, "mxResolver");
    }

    public SendResult send(SmtpConfig config, SmtpEnvelope envelope, MessageSource source) throws SmtpException {
        return send(config, envelope, source, CancellationToken.NEVER, SmtpTranscript.NULL_LISTENER);
    }

    public SendResult send(
            SmtpConfig config,
            SmtpEnvelope envelope,
            MessageSource source,
            CancellationToken cancel,
            SmtpTranscript.Listener listener)
            throws SmtpException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(listener, "listener");

        var transcript = new SmtpTranscript(listener);
        var session = new SmtpSession();
        var startNanos = System.nanoTime();
        try (var connection = new Connection();
                var cancelWatch = new CancellationWatch(connection, cancel)) {
            return runTransaction(connection, config, envelope, source, cancel, transcript, session, startNanos);
        } catch (SmtpException smtpFailure) {
            throw smtpFailure.withTranscript(transcript).withTls(session.tlsOutcome());
        } catch (SocketTimeoutException timeout) {
            if (cancel.isCancelled()) {
                throw cancelled(session, transcript);
            }
            throw new SmtpException(
                            SmtpException.Kind.TIMEOUT,
                            session.currentPhase(),
                            "timeout in " + session.currentPhase(),
                            timeout)
                    .withTranscript(transcript)
                    .withTls(session.tlsOutcome());
        } catch (IOException ioFailure) {
            // A socket closed by the cancellation watcher unblocks the in-flight read here; surface
            // it as a clean cancellation rather than a generic I/O error.
            if (cancel.isCancelled()) {
                throw cancelled(session, transcript);
            }
            throw new SmtpException(
                            SmtpException.Kind.IO_ERROR, session.currentPhase(), ioFailure.getMessage(), ioFailure)
                    .withTranscript(transcript)
                    .withTls(session.tlsOutcome());
        }
    }

    private static SmtpException cancelled(SmtpSession session, SmtpTranscript transcript) {
        return new SmtpException(SmtpException.Kind.CANCELLED, session.currentPhase(), "cancelled by caller")
                .withTranscript(transcript)
                .withTls(session.tlsOutcome());
    }

    private SendResult runTransaction(
            Connection connection,
            SmtpConfig config,
            SmtpEnvelope envelope,
            MessageSource source,
            CancellationToken cancel,
            SmtpTranscript transcript,
            SmtpSession session,
            long startNanos)
            throws SmtpException, IOException {
        session.enterPhase(Phase.CONNECT);
        cancel(cancel, session);
        var destinationHosts = resolveDestinationHosts(config, envelope, transcript);
        Socket fresh = null;
        IOException lastConnectFailure = null;
        // config.timeout() is applied per host (and per address inside the connector), not as an
        // overall connect budget — so several unreachable MX hosts can take up to timeout × hosts to
        // exhaust before failover completes. See TcpConnector for the rationale.
        for (var destinationHost : destinationHosts) {
            try {
                fresh = connector.connect(destinationHost, config.port(), config.timeout(), config.transport());
                break;
            } catch (IOException failure) {
                // RFC 5321 §5.1: when the best-preference MX is unreachable, try the alternates.
                lastConnectFailure = failure;
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        ("could not connect to " + destinationHost + ":" + config.port() + " — " + failure.getMessage())
                                .getBytes(StandardCharsets.UTF_8),
                        Phase.CONNECT);
            }
        }
        if (fresh == null) {
            throw new SmtpException(
                    SmtpException.Kind.CONNECT_FAILED,
                    Phase.CONNECT,
                    "could not connect to " + String.join(", ", destinationHosts) + ":" + config.port() + " — "
                            + lastConnectFailure.getMessage(),
                    lastConnectFailure);
        }
        connection.initialize(fresh);

        writeProxyHeaderIfConfigured(connection, config, transcript);

        if (config.tls().mode() == TlsConfig.Mode.TLS_ON_CONNECT) {
            upgradeToTls(connection, config, transcript, session, Phase.TLS);
        }

        if (shouldStop(config, Phase.CONNECT)) {
            throw stop(config, connection, transcript, Phase.CONNECT);
        }

        session.enterPhase(Phase.BANNER);
        var banner = readResponse(connection, transcript, Phase.BANNER);
        if (banner.code() != 220) {
            // rfc5321 §4.2.1/§3.1: a 4yz greeting (e.g. 421 "service not available") is a transient
            // condition the caller can retry — distinguish it from a genuine protocol violation.
            throw new SmtpException(
                    banner.isTransientNegative() ? SmtpException.Kind.TRANSIENT : SmtpException.Kind.PROTOCOL_VIOLATION,
                    Phase.BANNER,
                    "unexpected banner: " + banner.code() + " " + banner.firstLine());
        }
        session.setGreeting(banner.firstLine());
        if (shouldStop(config, Phase.BANNER)) {
            throw stop(config, connection, transcript, Phase.BANNER);
        }
        cancel(cancel, session);

        issueEhlo(connection, config, transcript, session, Phase.FIRST_HELO);
        if (shouldStop(config, Phase.FIRST_HELO)) {
            throw stop(config, connection, transcript, Phase.FIRST_HELO);
        }
        cancel(cancel, session);

        if (config.xclient().isEnabled() && config.xclient().beforeStartTls()) {
            applyXclient(connection, config, transcript, session);
        }

        var starttlsAccepted = issueStartTlsCommand(connection, config, transcript, session);
        if (starttlsAccepted && shouldStop(config, Phase.STARTTLS)) {
            throw stop(config, connection, transcript, Phase.STARTTLS);
        }
        if (starttlsAccepted) {
            upgradeToTls(connection, config, transcript, session, Phase.TLS);
            if (shouldStop(config, Phase.TLS)) {
                throw stop(config, connection, transcript, Phase.TLS);
            }
            issueEhlo(connection, config, transcript, session, Phase.HELO);
            if (shouldStop(config, Phase.HELO)) {
                throw stop(config, connection, transcript, Phase.HELO);
            }
            cancel(cancel, session);
        }

        if (config.xclient().isEnabled() && !config.xclient().beforeStartTls()) {
            applyXclient(connection, config, transcript, session);
        }

        performAuth(connection, config, transcript, session);
        if (shouldStop(config, Phase.AUTH)) {
            throw stop(config, connection, transcript, Phase.AUTH);
        }
        cancel(cancel, session);

        var negotiation = negotiateExtensions(config, envelope, source, session);
        var pipelining = config.esmtp().usePipelining()
                && session.supports("PIPELINING")
                && config.stopAfter() != Phase.MAIL
                && config.stopAfter() != Phase.RCPT
                && config.stopAfter() != Phase.DATA
                && !negotiation.useBdat();

        var mailLine = DsnEnvelopeFormatter.formatMailFrom(envelope, buildMailParameters(config, session, negotiation));
        var dispositions = new ArrayList<SendResult.RecipientDisposition>(
                envelope.recipients().size());
        var dataAlreadyTransferred = false;

        if (pipelining) {
            // Batch: MAIL FROM + all RCPT TOs + DATA without inline reads.
            session.enterPhase(Phase.MAIL);
            writeCommand(connection.output(), transcript, Phase.MAIL, mailLine);
            for (var recipient : envelope.recipients()) {
                writeCommand(connection.output(), transcript, Phase.RCPT, DsnEnvelopeFormatter.formatRcptTo(recipient));
            }
            session.enterPhase(Phase.DATA);
            writeCommand(connection.output(), transcript, Phase.DATA, "DATA");
            // Read MAIL response first.
            var mailResponse = readResponse(connection, transcript, Phase.MAIL);
            if (!mailResponse.isPositiveCompletion()) {
                // Drain the RCPT and DATA replies already pipelined onto the wire so the teardown is
                // symmetric with the all-RCPT-rejected branch below (the connection is closed either way,
                // but an unread reply would corrupt the stream if connection reuse is ever added).
                for (var ignoredRecipient : envelope.recipients()) {
                    drainResponse(connection, transcript, Phase.RCPT);
                }
                drainResponse(connection, transcript, Phase.DATA);
                throw new SmtpException(
                        SmtpException.Kind.MAIL_REJECTED,
                        Phase.MAIL,
                        "MAIL FROM rejected: " + mailResponse.code() + " " + mailResponse.firstLine());
            }
            // Then all RCPT responses in order.
            var anyAccepted = false;
            for (var recipient : envelope.recipients()) {
                var rcptResponse = readResponse(connection, transcript, Phase.RCPT);
                var accepted = rcptResponse.isPositiveCompletion();
                dispositions.add(new SendResult.RecipientDisposition(
                        recipient.address(), rcptResponse.code(), rcptResponse.firstLine(), accepted));
                if (accepted) {
                    anyAccepted = true;
                }
            }
            if (!anyAccepted) {
                // Drain the DATA response so the server isn't left holding a half-written reply.
                drainResponse(connection, transcript, Phase.DATA);
                throw new SmtpException(SmtpException.Kind.RCPT_REJECTED, Phase.RCPT, "no recipients accepted");
            }
            // Finally the DATA response.
            var dataResponse = readResponse(connection, transcript, Phase.DATA);
            if (!dataResponse.isPositiveIntermediate()) {
                throw new SmtpException(
                        SmtpException.Kind.DATA_REJECTED,
                        Phase.DATA,
                        "DATA rejected: " + dataResponse.code() + " " + dataResponse.firstLine());
            }
            cancel(cancel, session);

            streamPayload(connection.output(), transcript, source, cancel, session, config.normalizeLineEndings());

            session.enterPhase(Phase.DOT);
            writeLineRaw(connection.output(), transcript, Phase.DOT, DOT_CRLF, SmtpTranscript.Direction.CLIENT, ".");
            readDataVerdict(connection, transcript, dispositions, negotiation.usePrdr(), Phase.DOT);
            if (shouldStop(config, Phase.DOT)) {
                throw stop(config, connection, transcript, Phase.DOT);
            }
            cancel(cancel, session);
            dataAlreadyTransferred = true;
        }

        if (!pipelining) {
            session.enterPhase(Phase.MAIL);
            var mailResponse = command(connection, transcript, Phase.MAIL, mailLine);
            if (!mailResponse.isPositiveCompletion()) {
                throw new SmtpException(
                        SmtpException.Kind.MAIL_REJECTED,
                        Phase.MAIL,
                        "MAIL FROM rejected: " + mailResponse.code() + " " + mailResponse.firstLine());
            }
            if (shouldStop(config, Phase.MAIL)) {
                throw stop(config, connection, transcript, Phase.MAIL);
            }
            cancel(cancel, session);

            session.enterPhase(Phase.RCPT);
            var anyAccepted = false;
            for (var recipient : envelope.recipients()) {
                var rcptResponse =
                        command(connection, transcript, Phase.RCPT, DsnEnvelopeFormatter.formatRcptTo(recipient));
                var accepted = rcptResponse.isPositiveCompletion();
                dispositions.add(new SendResult.RecipientDisposition(
                        recipient.address(), rcptResponse.code(), rcptResponse.firstLine(), accepted));
                if (accepted) {
                    anyAccepted = true;
                }
                cancel(cancel, session);
            }
            if (!anyAccepted) {
                throw new SmtpException(SmtpException.Kind.RCPT_REJECTED, Phase.RCPT, "no recipients accepted");
            }
            if (shouldStop(config, Phase.RCPT)) {
                throw stop(config, connection, transcript, Phase.RCPT);
            }

            if (negotiation.useBdat()) {
                session.enterPhase(Phase.BDAT);
                performBdat(
                        connection,
                        transcript,
                        source,
                        cancel,
                        session,
                        dispositions,
                        negotiation.usePrdr(),
                        config.normalizeLineEndings());
                if (shouldStop(config, Phase.BDAT)) {
                    throw stop(config, connection, transcript, Phase.BDAT);
                }
            } else {
                session.enterPhase(Phase.DATA);
                var dataResponse = command(connection, transcript, Phase.DATA, "DATA");
                if (!dataResponse.isPositiveIntermediate()) {
                    throw new SmtpException(
                            SmtpException.Kind.DATA_REJECTED,
                            Phase.DATA,
                            "DATA rejected: " + dataResponse.code() + " " + dataResponse.firstLine());
                }
                if (shouldStop(config, Phase.DATA)) {
                    throw stop(config, connection, transcript, Phase.DATA);
                }
                cancel(cancel, session);

                streamPayload(connection.output(), transcript, source, cancel, session, config.normalizeLineEndings());

                session.enterPhase(Phase.DOT);
                writeLineRaw(
                        connection.output(), transcript, Phase.DOT, DOT_CRLF, SmtpTranscript.Direction.CLIENT, ".");
                readDataVerdict(connection, transcript, dispositions, negotiation.usePrdr(), Phase.DOT);
                if (shouldStop(config, Phase.DOT)) {
                    throw stop(config, connection, transcript, Phase.DOT);
                }
                cancel(cancel, session);
            }
        }

        // `dataAlreadyTransferred` is true only when the pipelining branch above completed DATA.
        // Used here to keep the variable visibly part of the control flow even though both
        // branches converge to the same QUIT logic.
        if (dataAlreadyTransferred) {
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    "DATA delivered via PIPELINING batch".getBytes(StandardCharsets.UTF_8),
                    Phase.DATA);
        }

        session.enterPhase(Phase.QUIT);
        if (shouldStop(config, Phase.QUIT)) {
            writeCommand(connection.output(), transcript, Phase.QUIT, "QUIT");
            connection.close();
            return new SendResult(
                    transcript,
                    dispositions,
                    Duration.ofNanos(System.nanoTime() - startNanos),
                    Phase.QUIT,
                    false,
                    session.capabilities(),
                    session.tlsOutcome());
        }
        var quitResponse = command(connection, transcript, Phase.QUIT, "QUIT");
        var cleanlyClosed = quitResponse.code() == 221;
        return new SendResult(
                transcript,
                dispositions,
                Duration.ofNanos(System.nanoTime() - startNanos),
                Phase.QUIT,
                cleanlyClosed,
                session.capabilities(),
                session.tlsOutcome());
    }

    /**
     * Sends the STARTTLS command and reads the response. Returns true when the server returned
     * 2xx and the client should proceed to the TLS handshake. Does NOT do the handshake itself —
     * the caller invokes {@link #upgradeToTls} after honoring any {@code stopAfter=STARTTLS}.
     */
    private boolean issueStartTlsCommand(
            Connection connection, SmtpConfig config, SmtpTranscript transcript, SmtpSession session)
            throws IOException, SmtpException {
        var mode = config.tls().mode();
        if (mode == TlsConfig.Mode.NONE || mode == TlsConfig.Mode.TLS_ON_CONNECT || session.tlsActive()) {
            return false;
        }
        var advertised = session.supports("STARTTLS");
        if (!advertised) {
            switch (mode) {
                case STARTTLS_REQUIRED ->
                    throw new SmtpException(
                            SmtpException.Kind.TLS_FAILED, Phase.STARTTLS, "server does not advertise STARTTLS");
                case STARTTLS_OPTIONAL, STARTTLS_OPTIONAL_STRICT -> {
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }
        session.enterPhase(Phase.STARTTLS);
        var response = command(connection, transcript, Phase.STARTTLS, "STARTTLS");
        if (!response.isPositiveCompletion()) {
            if (mode == TlsConfig.Mode.STARTTLS_OPTIONAL) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        ("STARTTLS rejected (" + response.code() + ") — continuing in cleartext (STARTTLS_OPTIONAL)")
                                .getBytes(StandardCharsets.UTF_8),
                        Phase.STARTTLS);
                return false;
            }
            throw new SmtpException(
                    SmtpException.Kind.TLS_FAILED,
                    Phase.STARTTLS,
                    "STARTTLS rejected: " + response.code() + " " + response.firstLine());
        }
        return true;
    }

    private void upgradeToTls(
            Connection connection, SmtpConfig config, SmtpTranscript transcript, SmtpSession session, Phase reportedAs)
            throws SmtpException, IOException {
        session.enterPhase(reportedAs);
        try {
            var built = TlsContextFactory.build(config.tls());
            var factory = built.sslContext().getSocketFactory();
            var sniHost = pickSniHost(config);
            var sslSocket = (SSLSocket) factory.createSocket(
                    connection.socket(), sniHost, connection.socket().getPort(), true);
            sslSocket.setUseClientMode(true);
            sslSocket.setSSLParameters(built.parameters());
            sslSocket.startHandshake();
            connection.upgrade(sslSocket);
            session.markTlsActive(
                    sslSocket.getSession().getProtocol(),
                    sslSocket.getSession().getCipherSuite(),
                    PeerCertExtractor.snapshot(sslSocket));
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    ("TLS negotiated: "
                                    + sslSocket.getSession().getProtocol() + " "
                                    + sslSocket.getSession().getCipherSuite())
                            .getBytes(StandardCharsets.UTF_8),
                    reportedAs);
        } catch (SSLException handshakeFailure) {
            // SSLHandshakeException is an IOException — without this catch an untrusted chain or a
            // hostname mismatch would be misreported as a generic IO_ERROR instead of TLS_FAILED.
            throw new SmtpException(
                    SmtpException.Kind.TLS_FAILED,
                    reportedAs,
                    "TLS handshake failed: " + handshakeFailure.getMessage(),
                    handshakeFailure);
        } catch (GeneralSecurityException securityFailure) {
            throw new SmtpException(
                    SmtpException.Kind.TLS_FAILED,
                    reportedAs,
                    "TLS handshake failed: " + securityFailure.getMessage(),
                    securityFailure);
        }
    }

    private String pickSniHost(SmtpConfig config) {
        var override = config.tls().sniHost();
        if (override != null && !override.isBlank()) {
            return override;
        }
        var hostOverride = config.tls().hostnameOverride();
        if (hostOverride != null && !hostOverride.isBlank()) {
            return hostOverride;
        }
        return config.host();
    }

    private record ExtensionNegotiation(
            boolean requiresSmtpUtf8,
            boolean declareBody8bit,
            OptionalLongHolder declaredSize,
            boolean useBdat,
            boolean usePrdr) {

        record OptionalLongHolder(boolean present, long value) {
            static OptionalLongHolder of(long value) {
                return new OptionalLongHolder(true, value);
            }

            static OptionalLongHolder empty() {
                return new OptionalLongHolder(false, 0L);
            }
        }
    }

    private void writeProxyHeaderIfConfigured(Connection connection, SmtpConfig config, SmtpTranscript transcript)
            throws IOException {
        if (!config.proxy().isEnabled()) {
            return;
        }
        var header =
                switch (config.proxy().version()) {
                    case V1 -> ProxyV1Writer.format(config.proxy());
                    case V2 -> ProxyV2Writer.format(config.proxy());
                    case NONE -> new byte[0];
                };
        if (header.length == 0) {
            return;
        }
        connection.output().write(header);
        connection.output().flush();
        var summary = "PROXY " + config.proxy().version() + " " + config.proxy().family() + " "
                + config.proxy().sourceAddress() + ":" + config.proxy().sourcePort() + " -> "
                + config.proxy().destAddress() + ":" + config.proxy().destPort();
        transcript.append(SmtpTranscript.Direction.INFO, summary.getBytes(StandardCharsets.UTF_8), Phase.CONNECT);
    }

    private void applyXclient(Connection connection, SmtpConfig config, SmtpTranscript transcript, SmtpSession session)
            throws IOException, SmtpException {
        if (!session.supports("XCLIENT")) {
            if (config.xclient().optional()) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        "XCLIENT requested but not advertised — skipping (optional)".getBytes(StandardCharsets.UTF_8),
                        session.currentPhase());
                return;
            }
            throw new SmtpException(
                    SmtpException.Kind.PROTOCOL_VIOLATION,
                    session.currentPhase(),
                    "XCLIENT requested but server does not advertise it");
        }
        var line = XclientCommandBuilder.build(config.xclient());
        var response = command(connection, transcript, session.currentPhase(), line);
        switch (response.code() / 100) {
            case 2 -> {
                // 220 = banner reissued; 250 = continue without restart. In both cases the spec
                // says we may re-EHLO to refresh capabilities. Re-EHLO unconditionally on 220.
                if (response.code() == 220) {
                    session.setGreeting(response.firstLine());
                    issueEhlo(connection, config, transcript, session, session.currentPhase());
                }
            }
            default ->
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION,
                        session.currentPhase(),
                        "XCLIENT rejected: " + response.code() + " " + response.firstLine());
        }
    }

    /**
     * Resolves the connection candidates. Without MX routing this is just the configured host.
     * With MX routing the candidates are all MX hosts of the <b>MAIL FROM</b> domain in preference
     * order (swaks {@code --copy-routing} semantics — recipients' domains are deliberately not
     * consulted; see {@code TransportConfig#useMxRouting()}), so the caller can fall back to the
     * next MX when one is unreachable (rfc5321 §5.1).
     */
    private List<String> resolveDestinationHosts(SmtpConfig config, SmtpEnvelope envelope, SmtpTranscript transcript)
            throws SmtpException {
        if (!config.transport().useMxRouting()) {
            return List.of(config.host());
        }
        var atIndex = envelope.mailFrom().indexOf('@');
        if (atIndex < 0 || atIndex == envelope.mailFrom().length() - 1) {
            throw new SmtpException(
                    SmtpException.Kind.CONNECT_FAILED,
                    Phase.CONNECT,
                    "MX routing requested but MAIL FROM has no domain: " + envelope.mailFrom());
        }
        var domain = envelope.mailFrom().substring(atIndex + 1);
        try {
            var mxHosts = new ArrayList<>(mxResolver.resolve(domain));
            if (mxHosts.size() == 1 && mxHosts.get(0).isEmpty()) {
                // "0 ." — null MX (rfc7505): the domain declares it accepts no mail at all.
                throw new SmtpException(
                        SmtpException.Kind.CONNECT_FAILED,
                        Phase.CONNECT,
                        "domain " + domain + " declines all mail (null MX, rfc7505)");
            }
            mxHosts.removeIf(String::isEmpty);
            if (mxHosts.isEmpty()) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        ("no MX records for " + domain + " — falling back to A/AAAA").getBytes(StandardCharsets.UTF_8),
                        Phase.CONNECT);
                return List.of(domain);
            }
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    ("MX routing: " + domain + " -> " + String.join(", ", mxHosts)).getBytes(StandardCharsets.UTF_8),
                    Phase.CONNECT);
            return List.copyOf(mxHosts);
        } catch (NamingException failure) {
            throw new SmtpException(
                    SmtpException.Kind.CONNECT_FAILED,
                    Phase.CONNECT,
                    "MX lookup failed for " + domain + ": " + failure.getMessage(),
                    failure);
        }
    }

    private ExtensionNegotiation negotiateExtensions(
            SmtpConfig config, SmtpEnvelope envelope, MessageSource source, SmtpSession session)
            throws SmtpException, IOException {
        var esmtp = config.esmtp();

        var requiresUtf8 = Smtputf8Detector.requiresSmtputf8(envelope);
        if (requiresUtf8 && esmtp.enforceSmtpUtf8() && !session.supports("SMTPUTF8")) {
            throw new SmtpException(
                    SmtpException.Kind.MAIL_REJECTED,
                    Phase.MAIL,
                    "envelope contains non-ASCII addresses but server does not advertise SMTPUTF8");
        }

        var requires8bit = false;
        if (esmtp.eightBitMime() != EsmtpConfig.EightBitMimePolicy.NEVER) {
            try (var stream = source.open()) {
                requires8bit = EightBitMimeDetector.containsEightBitBytes(stream);
            }
        }
        var declareBody8bit = false;
        if (requires8bit) {
            var advertised = session.supports("8BITMIME");
            switch (esmtp.eightBitMime()) {
                case REQUIRE_WHEN_NEEDED -> {
                    if (!advertised) {
                        throw new SmtpException(
                                SmtpException.Kind.MAIL_REJECTED,
                                Phase.MAIL,
                                "body contains 8-bit data but server does not advertise 8BITMIME");
                    }
                    declareBody8bit = true;
                }
                case DOWNGRADE_IF_UNADVERTISED -> declareBody8bit = advertised;
                case NEVER -> declareBody8bit = false;
            }
        }

        var useBdat = esmtp.useBdat() && session.supports("CHUNKING");
        var usePrdr = esmtp.usePrdr() && session.supports("PRDR");

        // A single pass over the wire-normalized body yields both the SIZE value and the longest line:
        // rfc1870 §6 — the declared SIZE must reflect the bytes actually transmitted (CRLF
        // normalization, and dot-stuffing for DATA but not BDAT, make an LF-only message larger on the
        // wire than on disk); rfc5321 §4.5.3.1.6 — a text line including its CRLF must not exceed 1000
        // octets (so 998 octets of content), or a strict server may reject or truncate it.
        var metrics = computeWireMetrics(source, !useBdat, config.normalizeLineEndings());
        if (metrics.maxLineOctets() > MAX_WIRE_LINE_CONTENT_OCTETS) {
            throw new SmtpException(
                    SmtpException.Kind.DATA_REJECTED,
                    Phase.DATA,
                    "message contains a line of " + metrics.maxLineOctets()
                            + " octets, exceeding the rfc5321 §4.5.3.1.6" + " limit of " + MAX_WIRE_LINE_CONTENT_OCTETS
                            + " (the message must be folded before sending)");
        }

        var declaredSize = ExtensionNegotiation.OptionalLongHolder.empty();
        if (esmtp.declareSizeOnMail() || (esmtp.honorSize() && session.supports("SIZE"))) {
            var wireSize = metrics.wireLength();
            declaredSize = ExtensionNegotiation.OptionalLongHolder.of(wireSize);
            if (esmtp.honorSize() && session.supports("SIZE")) {
                var advertised = SizePreflight.advertisedLimit(session.capabilityArguments("SIZE"));
                if (SizePreflight.exceedsLimit(wireSize, advertised)) {
                    throw new SmtpException(
                            SmtpException.Kind.MAIL_REJECTED,
                            Phase.MAIL,
                            "message size " + wireSize + " exceeds server SIZE limit " + advertised.getAsLong());
                }
            }
        }

        return new ExtensionNegotiation(requiresUtf8, declareBody8bit, declaredSize, useBdat, usePrdr);
    }

    /**
     * Computes the on-the-wire DATA length in bytes: the source streamed through the same
     * normalization (and, for DATA, dot-stuffing) the client applies in {@link #streamPayload} /
     * {@link #performBdat}, including the trailing CRLF appended when the body does not already end at
     * a CRLF boundary. Measurement uses the SAME {@code normalizeLineEndings} flag as the transmit
     * path so the declared SIZE (rfc1870 §6) equals the bytes actually transmitted. The terminating
     * {@code <CRLF>.<CRLF>} / {@code BDAT ... LAST} framing is protocol overhead, not message content,
     * so it is deliberately excluded.
     */
    private static WireMetrics computeWireMetrics(MessageSource source, boolean dotStuff, boolean normalizeLineEndings)
            throws IOException {
        var wireLength = 0L;
        var maxLineOctets = 0;
        var currentLineOctets = 0;
        try (var stream = source.open()) {
            var buffer = new byte[CHUNK_SIZE];
            var sawCr = false;
            var atLineStart = true;
            var endsWithCrlf = true;
            // Tracks whether the previously emitted byte was a CR, so a CR immediately followed by an
            // LF (the line terminator) is not counted as a content octet even across a chunk boundary.
            var previousByteWasCr = false;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                var normalized = normalize(buffer, read, sawCr, atLineStart, dotStuff, normalizeLineEndings);
                var bytes = normalized.bytes();
                wireLength += bytes.length;
                // Measure the longest content line (octets excluding the CRLF terminator). A CR that is
                // immediately followed by LF is the first half of a terminator and is not counted; a bare
                // CR (no following LF) in no-normalize mode counts as a content octet. currentLineOctets
                // carries across chunk boundaries.
                for (var octet : bytes) {
                    if (octet == '\n') {
                        // If this LF closes a CRLF, the CR was provisionally counted: back it out.
                        if (previousByteWasCr) {
                            currentLineOctets--;
                        }
                        maxLineOctets = Math.max(maxLineOctets, currentLineOctets);
                        currentLineOctets = 0;
                        previousByteWasCr = false;
                    } else {
                        currentLineOctets++;
                        previousByteWasCr = octet == '\r';
                    }
                }
                sawCr = normalized.endedWithCr();
                atLineStart = normalized.endsAtLineStart();
                endsWithCrlf = normalized.endsWithCrlf();
            }
            // The framing CRLF is appended by the transmit path when the body does not already end with
            // CRLF; mirror that here so the measured size matches the bytes actually sent.
            if (!endsWithCrlf) {
                wireLength += CRLF.length;
            }
            // The trailing line (if the body did not end at a line boundary) still counts toward the max.
            maxLineOctets = Math.max(maxLineOctets, currentLineOctets);
        }
        return new WireMetrics(wireLength, maxLineOctets);
    }

    /** The on-the-wire DATA length and the longest content line (octets excluding the CRLF terminator). */
    private record WireMetrics(long wireLength, int maxLineOctets) {}

    private String buildMailParameters(SmtpConfig config, SmtpSession session, ExtensionNegotiation negotiation) {
        var parameters = new StringBuilder();
        if (negotiation.declareBody8bit()) {
            parameters.append("BODY=8BITMIME ");
        }
        if (negotiation.requiresSmtpUtf8() && session.supports("SMTPUTF8")) {
            parameters.append("SMTPUTF8 ");
        }
        if (config.esmtp().declareSizeOnMail() && negotiation.declaredSize().present() && session.supports("SIZE")) {
            parameters
                    .append("SIZE=")
                    .append(negotiation.declaredSize().value())
                    .append(' ');
        }
        if (negotiation.usePrdr()) {
            parameters.append("PRDR ");
        }
        return parameters.toString().trim();
    }

    /**
     * Streams the message via CHUNKING (rfc3030): chunks of {@link #BDAT_CHUNK_SIZE} (no
     * dot-stuffing — BDAT frames by byte count, not by a dot terminator), each intermediate chunk
     * acknowledged with a 250, then a final {@code BDAT n LAST} whose verdict is read through
     * {@link #readDataVerdict} so PRDR per-recipient replies are honoured. LF→CRLF normalization is
     * applied only when {@code normalizeLineEndings} is true; when false the CR/LF bytes are framed
     * byte-for-byte. A trailing CRLF is appended when the transmitted stream does not already end
     * with one, so the framing matches what DATA would have produced.
     */
    private void performBdat(
            Connection connection,
            SmtpTranscript transcript,
            MessageSource source,
            CancellationToken cancel,
            SmtpSession session,
            ArrayList<SendResult.RecipientDisposition> dispositions,
            boolean prdrNegotiated,
            boolean normalizeLineEndings)
            throws IOException, SmtpException {
        var pending = new ByteArrayOutputStream(CHUNK_SIZE);
        var totalBytes = 0L;
        try (var stream = source.open()) {
            var buffer = new byte[CHUNK_SIZE];
            var sawCr = false;
            var lineStart = true;
            var endsWithCrlf = true;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                var normalized = normalize(buffer, read, sawCr, lineStart, false, normalizeLineEndings);
                pending.write(normalized.bytes());
                sawCr = normalized.endedWithCr();
                lineStart = normalized.endsAtLineStart();
                endsWithCrlf = normalized.endsWithCrlf();
                if (pending.size() >= BDAT_CHUNK_SIZE) {
                    totalBytes += sendBdatChunk(connection, transcript, pending.toByteArray(), false);
                    pending.reset();
                    var chunkAck = readResponse(connection, transcript, Phase.BDAT);
                    if (!chunkAck.isPositiveCompletion()) {
                        throw new SmtpException(
                                SmtpException.Kind.DATA_REJECTED,
                                Phase.BDAT,
                                "BDAT chunk rejected: " + chunkAck.code() + " " + chunkAck.firstLine());
                    }
                    cancel(cancel, session);
                }
            }
            // Match DATA's terminator framing: ensure the body ends on a CRLF boundary. In no-normalize
            // mode a body ending in a bare LF does not end with CRLF, so the CRLF is still appended; the
            // appended bytes are part of the chunk and so are counted by sendBdatChunk below — the
            // declared chunk size therefore stays in sync with the bytes actually written.
            if (!endsWithCrlf) {
                pending.write(CRLF);
            }
        }
        totalBytes += sendBdatChunk(connection, transcript, pending.toByteArray(), true);
        transcript.append(
                SmtpTranscript.Direction.INFO,
                ("BDAT payload: " + totalBytes + " bytes").getBytes(StandardCharsets.UTF_8),
                Phase.BDAT);
        cancel(cancel, session);
        readDataVerdict(connection, transcript, dispositions, prdrNegotiated, Phase.BDAT);
    }

    private int sendBdatChunk(Connection connection, SmtpTranscript transcript, byte[] chunk, boolean last)
            throws IOException {
        writeCommand(connection.output(), transcript, Phase.BDAT, "BDAT " + chunk.length + (last ? " LAST" : ""));
        connection.output().write(chunk);
        connection.output().flush();
        return chunk.length;
    }

    /**
     * Reads the server's verdict after the end of message data ({@code <CRLF>.<CRLF>} or the LAST
     * BDAT chunk). Without PRDR this is a single reply. When PRDR was negotiated, the server
     * either sends a single uniform reply (all recipients shared the same fate) or a {@code 353}
     * intermediate reply followed by one reply per recipient accepted at RCPT time and a closing
     * overall reply (draft-hall-prdr, as implemented by Exim). Per-recipient replies overwrite the
     * matching {@code dispositions} entries.
     */
    private void readDataVerdict(
            Connection connection,
            SmtpTranscript transcript,
            ArrayList<SendResult.RecipientDisposition> dispositions,
            boolean prdrNegotiated,
            Phase phase)
            throws IOException, SmtpException {
        var first = readResponse(connection, transcript, phase);
        if (prdrNegotiated && first.code() == 353) {
            for (var index = 0; index < dispositions.size(); index++) {
                var current = dispositions.get(index);
                if (!current.accepted()) {
                    continue;
                }
                var response = readResponse(connection, transcript, phase);
                dispositions.set(
                        index,
                        new SendResult.RecipientDisposition(
                                current.address(),
                                response.code(),
                                response.firstLine(),
                                response.isPositiveCompletion()));
            }
            var overall = readResponse(connection, transcript, phase);
            if (!overall.isPositiveCompletion()) {
                throw new SmtpException(
                        SmtpException.Kind.DATA_REJECTED,
                        phase,
                        "PRDR final response rejected: " + overall.code() + " " + overall.firstLine());
            }
            return;
        }
        if (!first.isPositiveCompletion()) {
            throw new SmtpException(
                    SmtpException.Kind.DATA_REJECTED,
                    phase,
                    "message data rejected: " + first.code() + " " + first.firstLine());
        }
    }

    private void performAuth(Connection connection, SmtpConfig config, SmtpTranscript transcript, SmtpSession session)
            throws IOException, SmtpException {
        var auth = config.auth();
        if (auth.isDisabled()) {
            return;
        }
        var selector = new AuthMechanismSelector(auth.authMap());
        var advertised = session.capabilityArguments("AUTH");
        var picked =
                selector.pick(auth.mechanism(), advertised, auth.credentials().kind());
        if (picked == null) {
            if (auth.optional() || auth.optionalStrict()) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        "no usable AUTH mechanism advertised — skipping authentication (optional)"
                                .getBytes(StandardCharsets.UTF_8),
                        session.currentPhase());
                return;
            }
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "no usable AUTH mechanism advertised (server offers: " + String.join(", ", advertised) + ")");
        }
        // Plaintext-auth refusal: bail BEFORE any AUTH byte leaves the socket.
        if (picked.isPlaintextOnTheWire() && !session.tlsActive() && !auth.allowPlaintextAuth()) {
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "refusing " + picked.wireName() + " over a non-TLS socket (set allowPlaintextAuth to override)");
        }
        session.enterPhase(Phase.AUTH);
        AuthClient client;
        try {
            client = AuthClients.create(picked, auth.credentials(), config.host());
        } catch (Exception failure) {
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "could not build " + picked.wireName() + " client: " + failure.getMessage(),
                    failure);
        }
        SmtpResponse response;
        try {
            var initial = client.initial();
            var authLine = "AUTH " + picked.wireName();
            if (initial != null) {
                // rfc4954 §4: a present-but-empty initial response is transmitted as "=".
                authLine +=
                        " " + (initial.length == 0 ? "=" : Base64.getEncoder().encodeToString(initial));
            }
            writeAuthCommand(connection.output(), transcript, authLine);
            response = readResponse(connection, transcript, Phase.AUTH);
            while (response.code() == 334) {
                byte[] challenge;
                try {
                    challenge = decodeChallenge(response.firstLine());
                } catch (SmtpException malformedChallenge) {
                    // rfc4954 §4: the client aborts an in-progress SASL exchange by sending a line
                    // holding a single "*". A 334 challenge that is not valid base64 cannot be
                    // answered, so cancel the exchange cleanly — letting the server reject the AUTH —
                    // rather than abandoning the handshake mid-flight.
                    cancelAuthExchange(connection, transcript);
                    throw malformedChallenge;
                }
                var reply = client.respond(challenge);
                var encoded = reply == null ? "" : Base64.getEncoder().encodeToString(reply);
                writeAuthCommand(connection.output(), transcript, encoded);
                response = readResponse(connection, transcript, Phase.AUTH);
            }
            if (response.code() == 235 && !client.isComplete()) {
                // rfc4954 §4 allows the final SASL additional data (e.g. the SCRAM server-final
                // message) to ride base64-encoded in the text of the 235 success reply.
                var additionalData = tryDecodeBase64(response.firstLine());
                if (additionalData != null) {
                    client.respond(additionalData);
                }
            }
        } catch (RuntimeException mechanismFailure) {
            // Mechanism implementations signal protocol problems (bad server signature, malformed
            // challenge, unexpected round) with unchecked exceptions — keep the SmtpException
            // contract for callers and carry the transcript along.
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    picked.wireName() + " authentication failed: " + mechanismFailure.getMessage(),
                    mechanismFailure);
        }
        if (response.code() != 235) {
            if (auth.optional() && !auth.optionalStrict()) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        ("AUTH rejected (" + response.code() + ") — continuing unauthenticated (optional)")
                                .getBytes(StandardCharsets.UTF_8),
                        Phase.AUTH);
                return;
            }
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "AUTH rejected: " + response.code() + " " + response.firstLine());
        }
        if (!client.isComplete()) {
            // A 235 is the server's authoritative "authentication successful" (rfc4954 §4). Some
            // servers complete SCRAM by sending a bare 235 carrying no server-final for the client to
            // consume, leaving it short of its own "complete" state — trust the 235 rather than fail a
            // login the server accepted, but record the anomaly. (When the server DOES send a
            // server-final, client.respond above still verifies its signature and throws on mismatch.)
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    ("AUTH succeeded (235) before " + picked.wireName()
                                    + " reached a complete state — accepting the server's authorization")
                            .getBytes(StandardCharsets.UTF_8),
                    Phase.AUTH);
        }
    }

    /**
     * rfc4954 §4: abort an in-flight SASL exchange by sending a line containing a single "*". The
     * server is required to reject the AUTH with a 5xx reply; we drain that reply best-effort and
     * ignore any error since the caller is already failing the exchange.
     */
    /**
     * Best-effort read of one already-pipelined reply that is being abandoned because an earlier
     * command in the batch failed. Any IO/protocol error is swallowed — the caller is already failing
     * the transaction and the connection is about to be closed.
     */
    private void drainResponse(Connection connection, SmtpTranscript transcript, Phase phase) {
        try {
            readResponse(connection, transcript, phase);
        } catch (IOException | SmtpException ignored) {
            // already failing — surface the original cause
        }
    }

    private void cancelAuthExchange(Connection connection, SmtpTranscript transcript) {
        try {
            writeAuthCommand(connection.output(), transcript, "*");
            readResponse(connection, transcript, Phase.AUTH);
        } catch (IOException | SmtpException ignored) {
            // best-effort cancel — the exchange is already being failed by the caller
        }
    }

    /**
     * Lenient base64 probe for additional data inside a 235 reply: servers differ on whether the
     * blob is the whole reply text or the last token after an enhanced status code.
     */
    private static byte[] tryDecodeBase64(String text) {
        var candidate = text.trim();
        try {
            return Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException ignored) {
            var lastSpace = candidate.lastIndexOf(' ');
            if (lastSpace < 0) {
                return null;
            }
            try {
                return Base64.getDecoder().decode(candidate.substring(lastSpace + 1));
            } catch (IllegalArgumentException alsoNotBase64) {
                return null;
            }
        }
    }

    private void writeAuthCommand(OutputStream output, SmtpTranscript transcript, String line) throws IOException {
        var bytes = (line + "\r\n").getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        output.flush();
        transcript.append(SmtpTranscript.Direction.CLIENT_AUTH, line.getBytes(StandardCharsets.UTF_8), Phase.AUTH);
    }

    private static byte[] decodeChallenge(String responseText) throws SmtpException {
        if (responseText.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(responseText);
        } catch (IllegalArgumentException failure) {
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "invalid base64 in 334 challenge: " + responseText,
                    failure);
        }
    }

    private void issueEhlo(
            Connection connection, SmtpConfig config, SmtpTranscript transcript, SmtpSession session, Phase phase)
            throws IOException, SmtpException {
        session.enterPhase(phase);
        var verb = config.protocol() == SmtpConfig.Protocol.SMTP ? "HELO" : "EHLO";
        var response = command(connection, transcript, phase, verb + " " + config.ehloHost());
        if ("EHLO".equals(verb) && response.isPermanentNegative()) {
            // rfc5321 §3.2: a pre-ESMTP server may reject EHLO outright — retry once with HELO.
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    ("EHLO rejected (" + response.code() + ") — falling back to HELO").getBytes(StandardCharsets.UTF_8),
                    phase);
            verb = "HELO";
            response = command(connection, transcript, phase, verb + " " + config.ehloHost());
        }
        if (!response.isPositiveCompletion()) {
            // A 4yz EHLO/HELO reply (e.g. 421) is transient and retryable (rfc5321 §4.2.1); only a
            // non-2xx, non-4xx reply is a true protocol violation.
            throw new SmtpException(
                    response.isTransientNegative()
                            ? SmtpException.Kind.TRANSIENT
                            : SmtpException.Kind.PROTOCOL_VIOLATION,
                    phase,
                    verb + " rejected: " + response.code() + " " + response.firstLine());
        }
        session.replaceCapabilities(parseCapabilities(response));
    }

    private SmtpException stop(SmtpConfig config, Connection connection, SmtpTranscript transcript, Phase stoppedAt)
            throws IOException {
        if (config.dropAfter()) {
            connection.close();
            return new SmtpException(SmtpException.Kind.DROPPED_AT_PHASE, stoppedAt, "dropped after " + stoppedAt);
        }
        try {
            writeCommand(connection.output(), transcript, Phase.QUIT, "QUIT");
        } catch (IOException ignored) {
            // server may already be gone; surface the stop reason instead of the IO race.
        }
        connection.close();
        return new SmtpException(SmtpException.Kind.STOPPED_AT_PHASE, stoppedAt, "stopped after " + stoppedAt);
    }

    /**
     * Streams the DATA payload onto the wire. LF→CRLF normalization is applied only when
     * {@code normalizeLineEndings} is true (rfc5321 §2.3.8); when false the CR/LF bytes go out
     * byte-for-byte. Dot-stuffing and the terminating {@code <CRLF>.<CRLF>} framing always apply.
     *
     * <p>The framing CRLF before the terminating dot (rfc5321 §4.1.1.4) is the one transform that
     * still applies when normalization is disabled: it is emitted whenever the transmitted stream
     * does not already end with a CRLF. In no-normalize mode a body ending in a bare {@code \n} does
     * NOT leave the wire positioned after a CRLF, so the framing CRLF is still written — otherwise the
     * server would never see {@code <CRLF>.<CRLF>} and would not recognize end-of-data.
     */
    private void streamPayload(
            OutputStream output,
            SmtpTranscript transcript,
            MessageSource source,
            CancellationToken cancel,
            SmtpSession session,
            boolean normalizeLineEndings)
            throws SmtpException, IOException {
        var bytesSent = 0L;
        try (var payload = source.open()) {
            var buffer = new byte[CHUNK_SIZE];
            var carrySawCr = false;
            var atLineStart = true;
            var endsWithCrlf = true;
            int read;
            while ((read = payload.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                var normalized = normalize(buffer, read, carrySawCr, atLineStart, true, normalizeLineEndings);
                output.write(normalized.bytes());
                bytesSent += normalized.bytes().length;
                carrySawCr = normalized.endedWithCr();
                atLineStart = normalized.endsAtLineStart();
                endsWithCrlf = normalized.endsWithCrlf();
                cancel(cancel, session);
            }
            // rfc5321 §4.1.1.4: the terminating dot must be preceded by a real CRLF. Emit the framing
            // CRLF whenever the transmitted stream does not already end with one — this is the only
            // transform that still runs when normalization is disabled (a body ending in a bare LF is
            // not positioned after a CRLF, so endsWithCrlf is false even though endsAtLineStart is true).
            if (!endsWithCrlf) {
                output.write(CRLF);
                bytesSent += CRLF.length;
            }
            output.flush();
        }
        transcript.append(
                SmtpTranscript.Direction.INFO,
                ("DATA payload: " + bytesSent + " bytes").getBytes(StandardCharsets.UTF_8),
                Phase.DATA);
    }

    static NormalizedChunk normalizeAndDotStuff(byte[] source, int length, boolean carrySawCr, boolean atLineStart) {
        return normalize(source, length, carrySawCr, atLineStart, true, true);
    }

    /**
     * Normalizes one input chunk for the DATA/BDAT stream.
     *
     * <p>When {@code normalizeLineEndings} is {@code true} (the default), bare LF and bare CR are
     * promoted to CRLF (rfc5321 §2.3.8) and a trailing CR is held pending across chunk boundaries.
     * When it is {@code false}, CR and LF bytes are emitted exactly as they appear in the source —
     * none are synthesized or dropped — so the body is transmitted byte-for-byte; no CR is held
     * pending in that mode.
     *
     * <p>Dot-stuffing (when {@code dotStuff}) is applied at every line start in both modes. A line
     * start is the position right after an emitted {@code \n}. The {@code <CRLF>.<CRLF>} terminator
     * framing is the responsibility of the caller and is the one transform that still applies when
     * normalization is disabled.
     */
    static NormalizedChunk normalize(
            byte[] source,
            int length,
            boolean carrySawCr,
            boolean atLineStart,
            boolean dotStuff,
            boolean normalizeLineEndings) {
        if (!normalizeLineEndings) {
            return emitVerbatim(source, length, atLineStart, dotStuff, carrySawCr);
        }
        // Worst case per input byte: resolve a pending bare CR as CRLF (2) plus the byte's own
        // expansion (LF→CRLF, or a stuffed leading dot). A carried-in pending CR can add a
        // leading CRLF. Three-times sizing with slack covers every combination without a resize.
        var buffer = new byte[length * 3 + 4];
        var pos = 0;
        // `sawCr` here means "a CR has been seen but not yet emitted": rfc5321 §2.3.8 terminates
        // lines only with CRLF, so a CR is held back until we know whether an LF follows (emit
        // CRLF once) or not (emit CRLF anyway, turning a bare CR into a proper line ending). A CR
        // carried across a chunk boundary is likewise still pending and unemitted.
        var sawCr = carrySawCr;
        var lineStart = atLineStart;
        for (var index = 0; index < length; index++) {
            var current = source[index];
            if (current == '\n') {
                // CRLF (pending CR + this LF) or a bare LF promoted to CRLF.
                buffer[pos++] = '\r';
                buffer[pos++] = '\n';
                sawCr = false;
                lineStart = true;
                continue;
            }
            if (sawCr) {
                // The pending CR was a bare CR (not part of a CRLF): flush it as CRLF before
                // processing the current byte. rfc5321 §2.3.8: bare CR is not a valid terminator.
                buffer[pos++] = '\r';
                buffer[pos++] = '\n';
                sawCr = false;
                lineStart = true;
            }
            if (current == '\r') {
                sawCr = true;
                lineStart = false;
                continue;
            }
            if (dotStuff && lineStart && current == '.') {
                buffer[pos++] = '.';
            }
            buffer[pos++] = current;
            lineStart = false;
        }
        var trimmed = new byte[pos];
        System.arraycopy(buffer, 0, trimmed, 0, pos);
        // A still-pending CR is reported via endedWithCr; endsAtLineStart is false so the caller's
        // end-of-stream flush emits the closing CRLF for a trailing bare CR. In this mode every
        // emitted line break is a CRLF, so ending at a line start is exactly ending with CRLF.
        var endsAtLineStart = sawCr ? false : lineStart;
        return new NormalizedChunk(trimmed, sawCr, endsAtLineStart, endsAtLineStart);
    }

    /**
     * Byte-for-byte emission used when line-ending normalization is disabled: CR and LF are written
     * exactly as found (never synthesized or dropped), dot-stuffing still applies at each line start,
     * and no CR is held pending across chunk boundaries.
     *
     * <p>{@code carryPrevByteWasCr} reports whether the previously emitted chunk ended with a CR, so a
     * {@code \n} at the very start of this chunk is recognized as completing a CRLF that straddles the
     * chunk boundary. The returned {@code endedWithCr} field carries the same "last emitted byte was a
     * CR" signal forward (it is NOT a held-pending CR — verbatim mode emits CR immediately).
     */
    private static NormalizedChunk emitVerbatim(
            byte[] source, int length, boolean atLineStart, boolean dotStuff, boolean carryPrevByteWasCr) {
        // Worst case is one stuffed dot per byte: at most one extra byte per input byte.
        var buffer = new byte[length * 2 + 2];
        var pos = 0;
        var lineStart = atLineStart;
        var prevByteWasCr = carryPrevByteWasCr;
        // Tracks whether the most recent line break emitted (an LF) was immediately preceded by a CR —
        // including a CR carried over from the previous chunk — so endsWithCrlf is correct even when a
        // CRLF is split across the chunk boundary.
        var endsWithCrlf = false;
        for (var index = 0; index < length; index++) {
            var current = source[index];
            if (current == '\n') {
                buffer[pos++] = '\n';
                lineStart = true;
                endsWithCrlf = prevByteWasCr;
                prevByteWasCr = false;
                continue;
            }
            if (dotStuff && lineStart && current == '.') {
                buffer[pos++] = '.';
            }
            buffer[pos++] = current;
            lineStart = false;
            prevByteWasCr = current == '\r';
            endsWithCrlf = false;
        }
        var trimmed = new byte[pos];
        System.arraycopy(buffer, 0, trimmed, 0, pos);
        // endedWithCr carries the "last emitted byte was a CR" signal (so a boundary-split CRLF is
        // detected by the next chunk); it is not a held-pending CR. endsWithCrlf is true only when the
        // transmitted stream genuinely ends with the \r\n pair. A body ending in a bare LF reports
        // endsAtLineStart=true but endsWithCrlf=false, so the caller still emits the framing CRLF.
        return new NormalizedChunk(trimmed, prevByteWasCr, lineStart, endsWithCrlf);
    }

    /**
     * Result of normalizing one input chunk for the DATA/BDAT stream.
     *
     * @param bytes the transmit-ready bytes for this chunk (already dot-stuffed when requested)
     * @param endedWithCr a CR was seen but not yet emitted (normalize mode only; always {@code false}
     *     when line-ending normalization is disabled, since CR is emitted immediately)
     * @param endsAtLineStart the stream position is at the start of a line, i.e. right after an
     *     emitted {@code \n} (drives leading-dot stuffing for the next chunk)
     * @param endsWithCrlf the transmitted stream so far ends with a {@code \r\n} pair specifically —
     *     used by {@code streamPayload} to decide whether the mandatory {@code <CRLF>.<CRLF>}
     *     terminator framing already has its leading CRLF. In no-normalize mode a body ending in a
     *     bare {@code \n} sets {@code endsAtLineStart} but NOT {@code endsWithCrlf}, so a framing
     *     CRLF is still emitted (rfc5321 §4.1.1.4).
     */
    record NormalizedChunk(byte[] bytes, boolean endedWithCr, boolean endsAtLineStart, boolean endsWithCrlf) {}

    private SmtpResponse command(Connection connection, SmtpTranscript transcript, Phase phase, String line)
            throws IOException, SmtpException {
        writeCommand(connection.output(), transcript, phase, line);
        return readResponse(connection, transcript, phase);
    }

    private void writeCommand(OutputStream output, SmtpTranscript transcript, Phase phase, String line)
            throws IOException {
        if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) {
            // Defense in depth against SMTP command injection: a command is a single CRLF-terminated
            // line (rfc5321 §4.1.1). Envelope and config inputs are validated upstream, so reaching
            // here with an embedded line break means one slipped through — refuse to write it rather
            // than smuggle additional commands onto the wire.
            throw new IOException("refusing to send an SMTP command with an embedded line break in phase " + phase);
        }
        var bytes = (line + "\r\n").getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        output.flush();
        transcript.append(SmtpTranscript.Direction.CLIENT, line.getBytes(StandardCharsets.UTF_8), phase);
    }

    private void writeLineRaw(
            OutputStream output,
            SmtpTranscript transcript,
            Phase phase,
            byte[] bytes,
            SmtpTranscript.Direction direction,
            String visible)
            throws IOException {
        output.write(bytes);
        output.flush();
        transcript.append(direction, visible.getBytes(StandardCharsets.UTF_8), phase);
    }

    private SmtpResponse readResponse(Connection connection, SmtpTranscript transcript, Phase phase)
            throws IOException, SmtpException {
        var reader = connection.reader();
        var lines = new ArrayList<String>();
        int code = -1;
        while (true) {
            if (lines.size() >= MAX_REPLY_LINES) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION, phase, "reply exceeds " + MAX_REPLY_LINES + " lines");
            }
            var raw = readReplyLine(reader, phase);
            if (raw == null) {
                throw new SmtpException(
                        SmtpException.Kind.IO_ERROR, phase, "server closed connection while reading response");
            }
            transcript.append(SmtpTranscript.Direction.SERVER, raw.getBytes(StandardCharsets.UTF_8), phase);
            if (raw.length() < 3) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION, phase, "short reply line: '" + raw + "'");
            }
            int lineCode;
            try {
                lineCode = Integer.parseInt(raw.substring(0, 3));
            } catch (NumberFormatException failure) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION, phase, "non-numeric reply code: '" + raw + "'", failure);
            }
            // rfc5321 §4.2: a reply code is exactly three digits whose leading digit is 2–5 (1yz is
            // reserved and never sent by a server in this client's transaction model). parseInt also
            // accepts a leading sign (e.g. "-50") and any 600–999 value, so range-check the parsed
            // code rather than silently accepting an impossible status.
            if (lineCode < 200 || lineCode > 599) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION,
                        phase,
                        "reply code out of range (2xx–5xx): '" + raw + "'");
            }
            if (code == -1) {
                code = lineCode;
            } else if (lineCode != code) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION,
                        phase,
                        "multi-line reply code mismatch: " + code + " vs " + lineCode);
            }
            var separator = raw.length() > 3 ? raw.charAt(3) : ' ';
            var text = raw.length() > 4 ? raw.substring(4) : "";
            lines.add(text);
            if (separator == ' ') {
                return new SmtpResponse(code, lines);
            }
            if (separator != '-') {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION, phase, "invalid reply separator: '" + raw + "'");
            }
        }
    }

    /**
     * Reads one CRLF-terminated reply line with a hard length cap (defense against a server that
     * never sends a newline). Returns null on a clean EOF before any byte of the line.
     */
    private static String readReplyLine(BufferedReader reader, Phase phase) throws IOException, SmtpException {
        var builder = new StringBuilder(96);
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') {
                var length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            builder.append((char) next);
            if (builder.length() > MAX_REPLY_LINE_CHARS) {
                throw new SmtpException(
                        SmtpException.Kind.PROTOCOL_VIOLATION,
                        phase,
                        "reply line exceeds " + MAX_REPLY_LINE_CHARS + " characters");
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private static Map<String, List<String>> parseCapabilities(SmtpResponse response) {
        return EhloResponseParser.parse(response);
    }

    private boolean shouldStop(SmtpConfig config, Phase phase) {
        return config.stopAfter() == phase;
    }

    private void cancel(CancellationToken cancel, SmtpSession session) throws SmtpException {
        if (cancel.isCancelled()) {
            throw new SmtpException(SmtpException.Kind.CANCELLED, session.currentPhase(), "cancelled by caller");
        }
    }

    /** Mutable holder so STARTTLS can swap the underlying socket without losing the call stack. */
    static final class Connection implements AutoCloseable {

        // volatile: the cancellation watcher reads this from another thread to close the socket,
        // and runTransaction may swap it during a STARTTLS upgrade.
        private volatile Socket socket;
        private OutputStream output;
        private BufferedReader reader;

        void initialize(Socket fresh) throws IOException {
            socket = fresh;
            output = fresh.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
        }

        void upgrade(SSLSocket upgraded) throws IOException {
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

        @Override
        public void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Bridges the polled {@link CancellationToken} to the blocking socket. The token is only
     * consulted between phases, so a read parked on the socket's {@code SO_TIMEOUT} (up to the full
     * configured timeout, default 60s) would otherwise ignore a cancel request until it returns.
     * This daemon watcher polls the token on a short interval and, on the first observed cancel,
     * closes the socket — unblocking the in-flight read/write, which then surfaces as a CANCELLED
     * result. For {@link CancellationToken#NEVER} no thread is started.
     */
    private static final class CancellationWatch implements AutoCloseable {

        private static final long POLL_INTERVAL_MILLIS = 100L;

        private final Thread thread;
        private volatile boolean stopped;

        CancellationWatch(Connection connection, CancellationToken cancel) {
            if (cancel == CancellationToken.NEVER) {
                this.thread = null;
                return;
            }
            this.thread = new Thread(() -> watch(connection, cancel), "mailkit-smtp-cancel-watch");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void watch(Connection connection, CancellationToken cancel) {
            while (!stopped) {
                if (cancel.isCancelled()) {
                    closeQuietly(connection);
                    return;
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void closeQuietly(Connection connection) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // already torn down — nothing to do
            }
        }

        @Override
        public void close() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
