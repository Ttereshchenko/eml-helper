package com.github.ttereshchenko.mailkit.smtp.fake;

import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Loads the bundled localhost self-signed keystore at {@code /smtp/test-cert.p12} for both
 * server and client sides of unit tests. The keystore password is the well-known dummy
 * {@code changeit}; the cert is valid for {@code DNS:localhost,IP:127.0.0.1}.
 */
public final class TestTlsResources {

    private static final String KEYSTORE_RESOURCE = "/smtp/test-cert.p12";
    private static final char[] PASSWORD = "changeit".toCharArray();

    private TestTlsResources() {}

    public static SSLContext serverContext() throws GeneralSecurityException, IOException {
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var stream = openKeystore()) {
            keyStore.load(stream, PASSWORD);
        }
        var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    public static TlsConfig clientStartTlsConfig() {
        // Tests speak to localhost over a self-signed cert — allow it explicitly per-send. Pin
        // protocols so JSSE defaults shifted by other tests in the same JVM cannot make us flaky.
        return TlsConfig.starttlsRequired()
                .withAllowSelfSigned(true)
                .withProtocols(java.util.List.of("TLSv1.2", "TLSv1.3"));
    }

    public static TlsConfig clientTlsOnConnectConfig() {
        return TlsConfig.tlsOnConnect()
                .withAllowSelfSigned(true)
                .withProtocols(java.util.List.of("TLSv1.2", "TLSv1.3"));
    }

    public static TrustManager[] trustAllManagers() {
        return new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    // intentional: tests
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    // intentional: tests
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }
        };
    }

    private static InputStream openKeystore() throws IOException {
        var stream = TestTlsResources.class.getResourceAsStream(KEYSTORE_RESOURCE);
        if (stream == null) {
            throw new IOException("missing test keystore: " + KEYSTORE_RESOURCE);
        }
        return stream;
    }
}
