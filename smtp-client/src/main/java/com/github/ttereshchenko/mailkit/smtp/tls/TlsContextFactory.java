package com.github.ttereshchenko.mailkit.smtp.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds JDK-native {@link SSLContext} and {@link SSLParameters} from a {@link TlsConfig}.
 *
 * <p>Trust resolution: if {@link TlsConfig#verifyCa()} is false (or the per-send
 * {@link TlsConfig#allowSelfSigned()} is set), any certificate the peer presents is accepted without
 * chain validation — {@code allowSelfSigned} is per-send only and intentionally NOT persisted,
 * whereas {@code verifyCa} is the persisted "Verify CA chain" profile knob. Otherwise, if a CA
 * bundle path is set, only the certs in that bundle (PEM file or directory of PEM files) are trusted;
 * failing that, the JDK default trust store applies.
 *
 * <p>Hostname identification is an independent control governed solely by
 * {@link TlsConfig#verifyHostname()}: relaxing chain trust does not switch off the hostname check, so
 * accepting a self-signed certificate cannot silently turn into a full MITM bypass.
 *
 * <p>Key resolution: if client cert / key paths are set, they are loaded fresh per send into a
 * transient in-memory keystore and wired into a {@link KeyManagerFactory}. Plugin state never
 * contains the key bytes.
 */
public final class TlsContextFactory {

    private TlsContextFactory() {}

    public record BuiltContext(SSLContext sslContext, SSLParameters parameters) {}

    public static BuiltContext build(TlsConfig config) throws GeneralSecurityException, IOException {
        var trustManagers = resolveTrustManagers(config);
        var keyManagers = resolveKeyManagers(config);
        var sslContext = SSLContext.getInstance(pickContextProtocol(config));
        sslContext.init(keyManagers == null ? null : keyManagers, trustManagers, null);

        var parameters = sslContext.getDefaultSSLParameters();
        if (!config.protocols().isEmpty()) {
            parameters.setProtocols(config.protocols().toArray(new String[0]));
        }
        if (!config.cipherSuites().isEmpty()) {
            parameters.setCipherSuites(config.cipherSuites().toArray(new String[0]));
        }
        // Hostname identification is governed solely by verifyHostname. Relaxing chain trust
        // (allowSelfSigned / verifyCa==false) deliberately does NOT also switch off the hostname
        // check — those are independent controls. A caller that genuinely wants both must clear
        // verifyHostname too, rather than getting a silent hostname bypass as a side effect of
        // accepting a self-signed certificate.
        if (config.verifyHostname()) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        } else {
            parameters.setEndpointIdentificationAlgorithm(null);
        }
        return new BuiltContext(sslContext, parameters);
    }

    private static String pickContextProtocol(TlsConfig config) {
        for (var protocol : config.protocols()) {
            if ("TLSv1.3".equals(protocol)) {
                return "TLSv1.3";
            }
        }
        return "TLS";
    }

    static TrustManager[] resolveTrustManagers(TlsConfig config) throws GeneralSecurityException, IOException {
        // A per-send allowSelfSigned, or an unchecked "Verify CA chain" (verifyCa == false), both mean
        // the same thing for chain trust: do not validate the server certificate against any trust store.
        // Neither touches hostname identification — that is governed independently by verifyHostname in
        // build(). Either way, install a trust-everything manager instead of the JDK default / CA bundle.
        if (config.allowSelfSigned() || !config.verifyCa()) {
            return new TrustManager[] {new TrustEverythingManager()};
        }
        if (config.caBundlePath() != null) {
            return new TrustManager[] {buildBundleTrustManager(config.caBundlePath())};
        }
        return null;
    }

    private static X509TrustManager buildBundleTrustManager(Path caBundlePath)
            throws GeneralSecurityException, IOException {
        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        var certs = loadCertificates(caBundlePath);
        var index = 0;
        for (var cert : certs) {
            keyStore.setCertificateEntry("mailkit-ca-" + index, cert);
            index++;
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("no X509TrustManager produced for CA bundle: " + caBundlePath);
    }

    private static javax.net.ssl.KeyManager[] resolveKeyManagers(TlsConfig config)
            throws GeneralSecurityException, IOException {
        if (config.clientCertPath() == null && config.clientKeyPath() == null) {
            return null;
        }
        if (config.clientCertPath() == null || config.clientKeyPath() == null) {
            throw new GeneralSecurityException("mTLS requires both client cert and client key paths");
        }
        var certChain = loadCertificates(config.clientCertPath());
        if (config.clientChainPath() != null) {
            certChain.addAll(loadCertificates(config.clientChainPath()));
        }
        var privateKey = loadPrivateKey(config.clientKeyPath());

        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("mailkit-mtls", privateKey, new char[0], certChain.toArray(new X509Certificate[0]));
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, new char[0]);
        return factory.getKeyManagers();
    }

    private static List<X509Certificate> loadCertificates(Path path) throws IOException, GeneralSecurityException {
        var factory = CertificateFactory.getInstance("X.509");
        var paths = new ArrayList<Path>();
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.filter(Files::isRegularFile).forEach(paths::add);
            }
        } else {
            paths.add(path);
        }
        var loaded = new ArrayList<X509Certificate>();
        for (var entry : paths) {
            var pem = Files.readString(entry, StandardCharsets.US_ASCII);
            var blocks = pem.split("-----END CERTIFICATE-----");
            for (var block : blocks) {
                var marker = block.indexOf("-----BEGIN CERTIFICATE-----");
                if (marker < 0) {
                    continue;
                }
                var base64 = block.substring(marker + "-----BEGIN CERTIFICATE-----".length())
                        .replaceAll("\\s+", "");
                var bytes = Base64.getDecoder().decode(base64);
                loaded.add((X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(bytes)));
            }
        }
        if (loaded.isEmpty()) {
            throw new GeneralSecurityException("no certificates found at: " + path);
        }
        return loaded;
    }

    private static java.security.PrivateKey loadPrivateKey(Path path) throws IOException, GeneralSecurityException {
        var pem = Files.readString(path, StandardCharsets.US_ASCII);
        if (pem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
            throw new GeneralSecurityException("encrypted PKCS#8 keys are not supported — decrypt first"
                    + " (openssl pkcs8 -topk8 -nocrypt): " + path);
        }
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----") || pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new GeneralSecurityException("PKCS#1/SEC1 PEM keys are not supported — convert to PKCS#8"
                    + " (openssl pkcs8 -topk8 -nocrypt): " + path);
        }
        var startMarker = "-----BEGIN PRIVATE KEY-----";
        var endMarker = "-----END PRIVATE KEY-----";
        var startIndex = pem.indexOf(startMarker);
        var endIndex = pem.indexOf(endMarker);
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new GeneralSecurityException("unsupported private key format (PKCS#8 PEM expected) at: " + path);
        }
        var base64 = pem.substring(startIndex + startMarker.length(), endIndex).replaceAll("\\s+", "");
        var bytes = Base64.getDecoder().decode(base64);
        var keySpec = new PKCS8EncodedKeySpec(bytes);
        // PKCS#8 does not say which algorithm the key is for — try the families used for client
        // certificates in the wild.
        InvalidKeySpecException lastRejection = null;
        for (var algorithm : List.of("RSA", "EC", "EdDSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (InvalidKeySpecException rejection) {
                lastRejection = rejection;
            } catch (NoSuchAlgorithmException ignored) {
                // provider without this family — try the next one
            }
        }
        throw new GeneralSecurityException(
                "unsupported PKCS#8 key algorithm (tried RSA, EC, EdDSA) at: " + path, lastRejection);
    }

    static final class TrustEverythingManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // intentional: chain trust relaxed (allowSelfSigned / verifyCa==false); hostname still checked
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // intentional: chain trust relaxed (allowSelfSigned / verifyCa==false); hostname still checked
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
