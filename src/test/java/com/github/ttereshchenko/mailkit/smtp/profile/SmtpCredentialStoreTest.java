package com.github.ttereshchenko.mailkit.smtp.profile;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.UUID;

/**
 * Round-trips passwords + TLS passphrases through the in-memory {@code PasswordSafe} that
 * {@link BasePlatformTestCase} provides via the IntelliJ test framework.
 */
public class SmtpCredentialStoreTest extends BasePlatformTestCase {

    public void testPasswordRoundTrip() {
        var profileId = UUID.randomUUID().toString();
        var store = new SmtpCredentialStore();

        store.setPassword(profileId, "s3cret-pw");

        var supplied = store.passwordSupplier(profileId).get();
        assertEquals("s3cret-pw", new String(supplied));
    }

    public void testTlsKeyPassphraseRoundTrip() {
        var profileId = UUID.randomUUID().toString();
        var store = new SmtpCredentialStore();

        store.setTlsKeyPassphrase(profileId, "tls-pass");

        var supplied = store.tlsKeyPassphraseSupplier(profileId).get();
        assertEquals("tls-pass", new String(supplied));
    }

    public void testForgetAllClearsBothEntries() {
        var profileId = UUID.randomUUID().toString();
        var store = new SmtpCredentialStore();
        store.setPassword(profileId, "pw");
        store.setTlsKeyPassphrase(profileId, "tls");

        store.forgetAll(profileId);

        assertEquals(0, store.passwordSupplier(profileId).get().length);
        assertEquals(0, store.tlsKeyPassphraseSupplier(profileId).get().length);
    }

    public void testSettingEmptyPasswordClearsTheEntry() {
        var profileId = UUID.randomUUID().toString();
        var store = new SmtpCredentialStore();
        store.setPassword(profileId, "first");

        store.setPassword(profileId, "");

        assertEquals(0, store.passwordSupplier(profileId).get().length);
    }
}
