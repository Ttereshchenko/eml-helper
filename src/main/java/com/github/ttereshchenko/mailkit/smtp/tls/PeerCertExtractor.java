package com.github.ttereshchenko.mailkit.smtp.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

/**
 * Captures the server certificate chain post-handshake into a {@link PeerCertSnapshot} that can
 * be carried in the {@code SendResult} long after the socket has closed.
 */
public final class PeerCertExtractor {

    private static final String CERT_BEGIN = "-----BEGIN CERTIFICATE-----\r\n";
    private static final String CERT_END = "-----END CERTIFICATE-----\r\n";

    private PeerCertExtractor() {}

    public static PeerCertSnapshot snapshot(SSLSocket socket) {
        try {
            var session = socket.getSession();
            var peers = session.getPeerCertificates();
            if (peers.length == 0) {
                return PeerCertSnapshot.empty();
            }
            var leaf = (X509Certificate) peers[0];
            var subject = leaf.getSubjectX500Principal().getName();
            var issuer = leaf.getIssuerX500Principal().getName();
            var sans = collectSans(leaf);
            var fingerprint = sha256Fingerprint(leaf);
            var pem = buildChainPem(peers);
            return new PeerCertSnapshot(subject, issuer, sans, fingerprint, pem);
        } catch (SSLPeerUnverifiedException ignored) {
            return PeerCertSnapshot.empty();
        }
    }

    private static java.util.List<String> collectSans(X509Certificate certificate) {
        var sans = new ArrayList<String>();
        try {
            var entries = certificate.getSubjectAlternativeNames();
            if (entries == null) {
                return sans;
            }
            for (var entry : entries) {
                if (entry.size() >= 2) {
                    sans.add(entry.get(0) + ":" + entry.get(1));
                }
            }
        } catch (CertificateParsingException ignored) {
            // unparseable SANs are not fatal — return what we have.
        }
        return sans;
    }

    private static String sha256Fingerprint(X509Certificate certificate) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().withUpperCase().formatHex(digest.digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException ignored) {
            return "";
        }
    }

    private static String buildChainPem(java.security.cert.Certificate[] chain) {
        var builder = new StringBuilder();
        for (var certificate : chain) {
            try {
                var encoded =
                        Base64.getMimeEncoder(64, new byte[] {'\r', '\n'}).encodeToString(certificate.getEncoded());
                builder.append(CERT_BEGIN).append(encoded).append("\r\n").append(CERT_END);
            } catch (CertificateEncodingException ignored) {
                // skip unencodable certs; common case is the JCE provider not supporting them.
            }
        }
        return builder.toString();
    }
}
