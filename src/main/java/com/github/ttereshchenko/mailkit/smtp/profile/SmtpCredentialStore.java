package com.github.ttereshchenko.mailkit.smtp.profile;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thin wrapper around {@link PasswordSafe} so the rest of the plugin never touches the
 * credential-store API directly. Two distinct keys live per profile:
 *
 * <ul>
 *   <li>{@code SMTP AUTH password} — the SASL password / bearer token.</li>
 *   <li>{@code mTLS key passphrase} — the encrypted-PEM passphrase, when applicable.</li>
 * </ul>
 *
 * <p>The service name is the well-known {@code "MailKit SMTP"} prefix; user-key is the profile
 * id (or id + suffix for sub-keys) so credentials cleanly cascade with profile delete.
 */
public final class SmtpCredentialStore {

    private static final String SERVICE_NAME = "MailKit SMTP";
    private static final String TLS_PASSPHRASE_SUFFIX = ":tls-key";

    private final PasswordSafe passwordSafe;

    public SmtpCredentialStore() {
        this(PasswordSafe.getInstance());
    }

    /** Test seam — wire in an in-memory {@code PasswordSafe} fixture. */
    public SmtpCredentialStore(PasswordSafe passwordSafe) {
        this.passwordSafe = Objects.requireNonNull(passwordSafe, "passwordSafe");
    }

    public void setPassword(String profileId, String password) {
        var attributes = passwordAttributes(profileId);
        if (password == null || password.isEmpty()) {
            passwordSafe.set(attributes, null);
            return;
        }
        passwordSafe.set(attributes, new Credentials("", password));
    }

    public Supplier<char[]> passwordSupplier(String profileId) {
        var attributes = passwordAttributes(profileId);
        return () -> {
            var credentials = passwordSafe.get(attributes);
            if (credentials == null || credentials.getPassword() == null) {
                return new char[0];
            }
            return credentials.getPassword().toCharArray();
        };
    }

    public void setTlsKeyPassphrase(String profileId, String passphrase) {
        var attributes = tlsAttributes(profileId);
        if (passphrase == null || passphrase.isEmpty()) {
            passwordSafe.set(attributes, null);
            return;
        }
        passwordSafe.set(attributes, new Credentials("", passphrase));
    }

    public Supplier<char[]> tlsKeyPassphraseSupplier(String profileId) {
        var attributes = tlsAttributes(profileId);
        return () -> {
            var credentials = passwordSafe.get(attributes);
            if (credentials == null || credentials.getPassword() == null) {
                return new char[0];
            }
            return credentials.getPassword().toCharArray();
        };
    }

    public void forgetAll(String profileId) {
        passwordSafe.set(passwordAttributes(profileId), null);
        passwordSafe.set(tlsAttributes(profileId), null);
    }

    private static CredentialAttributes passwordAttributes(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return new CredentialAttributes(SERVICE_NAME, profileId);
    }

    private static CredentialAttributes tlsAttributes(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return new CredentialAttributes(SERVICE_NAME, profileId + TLS_PASSPHRASE_SUFFIX);
    }
}
