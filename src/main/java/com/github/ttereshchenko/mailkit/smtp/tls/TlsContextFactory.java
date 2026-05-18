package com.github.ttereshchenko.mailkit.smtp.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
 * <p>Trust resolution: if a CA bundle path is set, only the certs in that bundle (PEM file or
 * directory of PEM files) are trusted. If {@link TlsConfig#allowSelfSigned()} is true any cert
 * presented by the peer is accepted — this flag is per-send only and intentionally NOT persisted.
 * Otherwise the JDK default trust store applies.
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
        if (config.verifyHostname() && !config.allowSelfSigned()) {
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

    private static TrustManager[] resolveTrustManagers(TlsConfig config) throws GeneralSecurityException, IOException {
        if (config.allowSelfSigned()) {
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
        var startMarker = "-----BEGIN PRIVATE KEY-----";
        var endMarker = "-----END PRIVATE KEY-----";
        var startIndex = pem.indexOf(startMarker);
        var endIndex = pem.indexOf(endMarker);
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new GeneralSecurityException("unsupported private key format (PKCS#8 PEM expected) at: " + path);
        }
        var base64 = pem.substring(startIndex + startMarker.length(), endIndex).replaceAll("\\s+", "");
        var bytes = Base64.getDecoder().decode(base64);
        var keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    static final class TrustEverythingManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // intentional: per-send allow-self-signed
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // intentional: per-send allow-self-signed
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
