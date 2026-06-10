package com.github.ttereshchenko.mailkit.smtp.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File-based trust and client-key loading: CA bundles, PKCS#8 key families, and format errors. */
class TlsContextFactoryKeyLoadingTest {

    @TempDir
    Path tempDir;

    private Path writeTemp(String fileName, String content) throws Exception {
        var path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }

    private static String pkcs8Pem(byte[] der) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void caBundleFileYieldsTrustManagerWithThoseIssuers() throws Exception {
        var bundle = writeTemp("bundle.pem", TestTlsResources.serverCertPem());
        var managers = TlsContextFactory.resolveTrustManagers(
                TlsConfig.starttlsRequired().withCaBundle(bundle));

        assertNotNull(managers);
        var x509 = assertInstanceOf(X509TrustManager.class, managers[0]);
        assertEquals(1, x509.getAcceptedIssuers().length, "bundle cert must become a trusted issuer");
    }

    @Test
    void caBundleDirectoryLoadsEveryPemInside() throws Exception {
        var directory = tempDir.resolve("ca-dir");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("one.pem"), TestTlsResources.serverCertPem());
        Files.writeString(directory.resolve("two.pem"), TestTlsResources.serverCertPem());

        var managers = TlsContextFactory.resolveTrustManagers(
                TlsConfig.starttlsRequired().withCaBundle(directory));

        assertNotNull(managers);
        assertInstanceOf(X509TrustManager.class, managers[0]);
    }

    @Test
    void emptyCaBundleIsAClearError() throws Exception {
        var bundle = writeTemp("empty.pem", "no certificates here");
        var failure = assertThrows(
                GeneralSecurityException.class,
                () -> TlsContextFactory.resolveTrustManagers(
                        TlsConfig.starttlsRequired().withCaBundle(bundle)));
        assertTrue(failure.getMessage().contains("no certificates"), failure.getMessage());
    }

    @Test
    void rsaClientCertAndKeyBuildAContext() throws Exception {
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());
        var key = writeTemp("client.key", TestTlsResources.serverKeyPem());

        var built = TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, key, null));

        assertNotNull(built.sslContext());
    }

    @Test
    void ecPkcs8KeyIsAccepted() throws Exception {
        // Regression for F11: the loader was hardcoded to KeyFactory("RSA") and rejected EC keys.
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        var ecKey = generator.generateKeyPair().getPrivate();
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());
        var key = writeTemp("client-ec.key", pkcs8Pem(ecKey.getEncoded()));

        var built = TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, key, null));

        assertNotNull(built.sslContext());
    }

    @Test
    void encryptedPkcs8KeyGivesActionableError() throws Exception {
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());
        var key = writeTemp(
                "enc.key", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");

        var failure = assertThrows(
                GeneralSecurityException.class,
                () -> TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, key, null)));
        assertTrue(failure.getMessage().contains("encrypted"), failure.getMessage());
    }

    @Test
    void pkcs1KeyGivesActionableError() throws Exception {
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());
        var key = writeTemp("pkcs1.key", "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        var failure = assertThrows(
                GeneralSecurityException.class,
                () -> TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, key, null)));
        assertTrue(failure.getMessage().contains("PKCS#1"), failure.getMessage());
    }

    @Test
    void garbagePkcs8KeyReportsTriedAlgorithms() throws Exception {
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());
        var garbage = Base64.getEncoder().encodeToString("definitely not a key".getBytes());
        var key = writeTemp("junk.key", "-----BEGIN PRIVATE KEY-----\n" + garbage + "\n-----END PRIVATE KEY-----\n");

        var failure = assertThrows(
                GeneralSecurityException.class,
                () -> TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, key, null)));
        assertTrue(failure.getMessage().contains("RSA, EC, EdDSA"), failure.getMessage());
    }

    @Test
    void certWithoutKeyIsAClearError() throws Exception {
        var cert = writeTemp("client.pem", TestTlsResources.serverCertPem());

        var failure = assertThrows(
                GeneralSecurityException.class,
                () -> TlsContextFactory.build(TlsConfig.starttlsRequired().withClientCertificate(cert, null, null)));
        assertTrue(failure.getMessage().contains("both"), failure.getMessage());
    }
}
