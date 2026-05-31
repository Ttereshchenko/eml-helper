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
import javax.net.ssl.SSLSocket;

/**
 * Drives a single SMTP / ESMTP transaction. Owns no state between calls; each {@link #send} runs
 * against a fresh socket. Phase 2 adds STARTTLS and TLS-on-connect on top of the Phase 1 wire
 * client without changing the entry-point signature.
 */
public final class SmtpClient {

    private static final byte[] DOT_CRLF = {'.', '\r', '\n'};
    private static final byte[] CRLF = {'\r', '\n'};
    private static final int CHUNK_SIZE = 8192;

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
            throw smtpFailure.withTranscript(transcript);
        } catch (SocketTimeoutException timeout) {
            if (cancel.isCancelled()) {
                throw cancelled(session, transcript);
            }
            throw new SmtpException(
                            SmtpException.Kind.TIMEOUT,
                            session.currentPhase(),
                            "timeout in " + session.currentPhase(),
                            timeout)
                    .withTranscript(transcript);
        } catch (IOException ioFailure) {
            // A socket closed by the cancellation watcher unblocks the in-flight read here; surface
            // it as a clean cancellation rather than a generic I/O error.
            if (cancel.isCancelled()) {
                throw cancelled(session, transcript);
            }
            throw new SmtpException(
                            SmtpException.Kind.IO_ERROR, session.currentPhase(), ioFailure.getMessage(), ioFailure)
                    .withTranscript(transcript);
        }
    }

    private static SmtpException cancelled(SmtpSession session, SmtpTranscript transcript) {
        return new SmtpException(SmtpException.Kind.CANCELLED, session.currentPhase(), "cancelled by caller")
                .withTranscript(transcript);
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
        var destinationHost = resolveDestinationHost(config, envelope, transcript);
        try {
            var fresh =
                    new TcpConnector().connect(destinationHost, config.port(), config.timeout(), config.transport());
            connection.initialize(fresh);
        } catch (IOException failure) {
            throw new SmtpException(
                    SmtpException.Kind.CONNECT_FAILED,
                    Phase.CONNECT,
                    "could not connect to " + destinationHost + ":" + config.port() + " — " + failure.getMessage(),
                    failure);
        }

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
            throw new SmtpException(
                    SmtpException.Kind.PROTOCOL_VIOLATION,
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
                try {
                    readResponse(connection, transcript, Phase.DATA);
                } catch (SmtpException ignored) {
                    // already failing — surface the original cause
                }
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

            streamPayload(connection.output(), transcript, source, cancel, session);

            session.enterPhase(Phase.DOT);
            writeLineRaw(connection.output(), transcript, Phase.DOT, DOT_CRLF, SmtpTranscript.Direction.CLIENT, ".");
            if (negotiation.usePrdr()) {
                readPrdrPerRecipientResponses(connection, transcript, dispositions);
            } else {
                var dotResponse = readResponse(connection, transcript, Phase.DOT);
                if (!dotResponse.isPositiveCompletion()) {
                    throw new SmtpException(
                            SmtpException.Kind.DATA_REJECTED,
                            Phase.DOT,
                            "DATA terminator rejected: " + dotResponse.code() + " " + dotResponse.firstLine());
                }
            }
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
                performBdat(connection, transcript, source, cancel, session);
                if (shouldStop(config, Phase.BDAT)) {
                    throw stop(config, connection, transcript, Phase.BDAT);
                }
                if (negotiation.usePrdr()) {
                    readPrdrPerRecipientResponses(connection, transcript, dispositions);
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

                streamPayload(connection.output(), transcript, source, cancel, session);

                session.enterPhase(Phase.DOT);
                writeLineRaw(
                        connection.output(), transcript, Phase.DOT, DOT_CRLF, SmtpTranscript.Direction.CLIENT, ".");
                if (negotiation.usePrdr()) {
                    readPrdrPerRecipientResponses(connection, transcript, dispositions);
                } else {
                    var dotResponse = readResponse(connection, transcript, Phase.DOT);
                    if (!dotResponse.isPositiveCompletion()) {
                        throw new SmtpException(
                                SmtpException.Kind.DATA_REJECTED,
                                Phase.DOT,
                                "DATA terminator rejected: " + dotResponse.code() + " " + dotResponse.firstLine());
                    }
                }
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

    private String resolveDestinationHost(SmtpConfig config, SmtpEnvelope envelope, SmtpTranscript transcript)
            throws SmtpException {
        if (!config.transport().useMxRouting()) {
            return config.host();
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
            var mxHosts = new MxResolver().resolve(domain);
            if (mxHosts.isEmpty()) {
                transcript.append(
                        SmtpTranscript.Direction.INFO,
                        ("no MX records for " + domain + " — falling back to A/AAAA").getBytes(StandardCharsets.UTF_8),
                        Phase.CONNECT);
                return domain;
            }
            var chosen = mxHosts.get(0);
            transcript.append(
                    SmtpTranscript.Direction.INFO,
                    ("MX routing: " + domain + " -> " + chosen).getBytes(StandardCharsets.UTF_8),
                    Phase.CONNECT);
            return chosen;
        } catch (javax.naming.NamingException failure) {
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

        var declaredSize = ExtensionNegotiation.OptionalLongHolder.empty();
        var messageSize = source.size();
        if (messageSize.isPresent()) {
            declaredSize = ExtensionNegotiation.OptionalLongHolder.of(messageSize.getAsLong());
            if (esmtp.honorSize() && session.supports("SIZE")) {
                var advertised = SizePreflight.advertisedLimit(session.capabilityArguments("SIZE"));
                if (SizePreflight.exceedsLimit(messageSize.getAsLong(), advertised)) {
                    throw new SmtpException(
                            SmtpException.Kind.MAIL_REJECTED,
                            Phase.MAIL,
                            "message size " + messageSize.getAsLong() + " exceeds server SIZE limit "
                                    + advertised.getAsLong());
                }
            }
        }

        var useBdat = esmtp.useBdat() && session.supports("CHUNKING");
        var usePrdr = esmtp.usePrdr() && session.supports("PRDR");
        return new ExtensionNegotiation(requiresUtf8, declareBody8bit, declaredSize, useBdat, usePrdr);
    }

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

    private void performBdat(
            Connection connection,
            SmtpTranscript transcript,
            MessageSource source,
            CancellationToken cancel,
            SmtpSession session)
            throws IOException, SmtpException {
        // Phase-3 BDAT implementation: read the message into memory once, normalize, send as a
        // single chunk with LAST. Streaming chunked BDAT is a follow-up — single-chunk wins on
        // simplicity and matches the typical .eml send pattern (a single message per connection).
        var payload = readAndNormalize(source);
        writeCommand(connection.output(), transcript, Phase.BDAT, "BDAT " + payload.length + " LAST");
        connection.output().write(payload);
        connection.output().flush();
        transcript.append(
                SmtpTranscript.Direction.INFO,
                ("BDAT payload: " + payload.length + " bytes").getBytes(StandardCharsets.UTF_8),
                Phase.BDAT);
        cancel(cancel, session);
        var bdatResponse = readResponse(connection, transcript, Phase.BDAT);
        if (!bdatResponse.isPositiveCompletion()) {
            throw new SmtpException(
                    SmtpException.Kind.DATA_REJECTED,
                    Phase.BDAT,
                    "BDAT rejected: " + bdatResponse.code() + " " + bdatResponse.firstLine());
        }
    }

    /** Reads the entire payload into a CRLF-normalized buffer. Used by BDAT — no dot-stuffing. */
    private byte[] readAndNormalize(MessageSource source) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        try (var stream = source.open()) {
            var buffer = new byte[CHUNK_SIZE];
            var sawCr = false;
            var lineStart = true;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                var normalized = normalize(buffer, read, sawCr, lineStart, false);
                output.write(normalized.bytes());
                sawCr = normalized.endedWithCr();
                lineStart = normalized.endsAtLineStart();
            }
            if (!lineStart) {
                output.write(CRLF);
            }
        }
        return output.toByteArray();
    }

    private void readPrdrPerRecipientResponses(
            Connection connection, SmtpTranscript transcript, ArrayList<SendResult.RecipientDisposition> dispositions)
            throws IOException, SmtpException {
        // PRDR sends N per-recipient responses (one per recipient that was accepted at RCPT time)
        // followed by a final overall response. We replace each accepted recipient's disposition
        // with the per-recipient verdict; the final response is the overall DATA verdict.
        for (var index = 0; index < dispositions.size(); index++) {
            var current = dispositions.get(index);
            if (!current.accepted()) {
                continue;
            }
            var response = readResponse(connection, transcript, Phase.DOT);
            var perRecipientAccepted = response.isPositiveCompletion();
            dispositions.set(
                    index,
                    new SendResult.RecipientDisposition(
                            current.address(), response.code(), response.firstLine(), perRecipientAccepted));
        }
        var finalResponse = readResponse(connection, transcript, Phase.DOT);
        if (!finalResponse.isPositiveCompletion()) {
            throw new SmtpException(
                    SmtpException.Kind.DATA_REJECTED,
                    Phase.DOT,
                    "PRDR final response rejected: " + finalResponse.code() + " " + finalResponse.firstLine());
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
        var picked = selector.pick(auth.mechanism(), advertised);
        if (picked == null) {
            if (auth.optional()) {
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
        var initial = client.initial();
        var authLine = "AUTH " + picked.wireName();
        if (initial != null) {
            authLine += " " + Base64.getEncoder().encodeToString(initial);
        }
        writeAuthCommand(connection.output(), transcript, authLine);
        var response = readResponse(connection, transcript, Phase.AUTH);
        while (response.code() == 334) {
            var challenge = decodeChallenge(response.firstLine());
            var reply = client.respond(challenge);
            var encoded = reply == null ? "" : Base64.getEncoder().encodeToString(reply);
            writeAuthCommand(connection.output(), transcript, encoded);
            response = readResponse(connection, transcript, Phase.AUTH);
        }
        if (response.code() != 235) {
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "AUTH rejected: " + response.code() + " " + response.firstLine());
        }
        if (!client.isComplete()) {
            throw new SmtpException(
                    SmtpException.Kind.AUTH_FAILED,
                    Phase.AUTH,
                    "AUTH ended with 235 but " + picked.wireName() + " client is not in a complete state");
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
        if (!response.isPositiveCompletion()) {
            throw new SmtpException(
                    SmtpException.Kind.PROTOCOL_VIOLATION,
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

    private void streamPayload(
            OutputStream output,
            SmtpTranscript transcript,
            MessageSource source,
            CancellationToken cancel,
            SmtpSession session)
            throws SmtpException, IOException {
        var bytesSent = 0L;
        try (var payload = source.open()) {
            var buffer = new byte[CHUNK_SIZE];
            var carrySawCr = false;
            var atLineStart = true;
            int read;
            while ((read = payload.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                var normalized = normalizeAndDotStuff(buffer, read, carrySawCr, atLineStart);
                output.write(normalized.bytes());
                bytesSent += normalized.bytes().length;
                carrySawCr = normalized.endedWithCr();
                atLineStart = normalized.endsAtLineStart();
                cancel(cancel, session);
            }
            if (!atLineStart) {
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
        return normalize(source, length, carrySawCr, atLineStart, true);
    }

    static NormalizedChunk normalize(
            byte[] source, int length, boolean carrySawCr, boolean atLineStart, boolean dotStuff) {
        var buffer = new byte[length * 2 + 2];
        var pos = 0;
        var sawCr = carrySawCr;
        var lineStart = atLineStart;
        for (var index = 0; index < length; index++) {
            var current = source[index];
            if (current == '\r') {
                buffer[pos++] = '\r';
                sawCr = true;
                lineStart = false;
                continue;
            }
            if (current == '\n') {
                if (!sawCr) {
                    buffer[pos++] = '\r';
                }
                buffer[pos++] = '\n';
                sawCr = false;
                lineStart = true;
                continue;
            }
            if (dotStuff && lineStart && current == '.') {
                buffer[pos++] = '.';
            }
            buffer[pos++] = current;
            sawCr = false;
            lineStart = false;
        }
        var trimmed = new byte[pos];
        System.arraycopy(buffer, 0, trimmed, 0, pos);
        return new NormalizedChunk(trimmed, sawCr, lineStart);
    }

    record NormalizedChunk(byte[] bytes, boolean endedWithCr, boolean endsAtLineStart) {}

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
            var raw = reader.readLine();
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
