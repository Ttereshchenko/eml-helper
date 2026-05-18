package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.HashMap;
import java.util.Objects;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

/**
 * Wraps {@link Sasl#createSaslClient} for the mechanisms that ship with the JDK and have a stable
 * implementation there: CRAM-MD5 and DIGEST-MD5. EXTERNAL / PLAIN / LOGIN are hand-rolled because
 * the JDK's SASL providers for them are inconsistent across vendors.
 */
public final class SaslAuthClient implements AuthClient {

    private final AuthMechanism mechanism;
    private final AuthCredentials credentials;
    private final SaslClient delegate;

    public SaslAuthClient(AuthMechanism mechanism, AuthCredentials credentials, String serverName)
            throws SaslException {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        var properties = new HashMap<String, Object>();
        properties.put(Sasl.QOP, "auth");
        this.delegate = Sasl.createSaslClient(
                new String[] {mechanism.wireName()},
                credentials.authzId().isBlank() ? null : credentials.authzId(),
                "smtp",
                Objects.requireNonNullElse(serverName, "localhost"),
                properties,
                new Handler(credentials));
        if (this.delegate == null) {
            throw new SaslException("no SASL provider available for " + mechanism.wireName());
        }
    }

    @Override
    public AuthMechanism mechanism() {
        return mechanism;
    }

    @Override
    public byte[] initial() {
        try {
            if (delegate.hasInitialResponse()) {
                return delegate.evaluateChallenge(new byte[0]);
            }
            return null;
        } catch (SaslException failure) {
            throw new IllegalStateException("SASL initial-response failed: " + failure.getMessage(), failure);
        }
    }

    @Override
    public byte[] respond(byte[] challenge) {
        try {
            return delegate.evaluateChallenge(challenge == null ? new byte[0] : challenge);
        } catch (SaslException failure) {
            throw new IllegalStateException("SASL challenge response failed: " + failure.getMessage(), failure);
        }
    }

    @Override
    public boolean isComplete() {
        return delegate.isComplete();
    }

    public AuthCredentials credentials() {
        return credentials;
    }

    private static final class Handler implements CallbackHandler {

        private final AuthCredentials credentials;

        Handler(AuthCredentials credentials) {
            this.credentials = credentials;
        }

        @Override
        public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
            for (var callback : callbacks) {
                switch (callback) {
                    case NameCallback name -> name.setName(credentials.username());
                    case PasswordCallback pass ->
                        pass.setPassword(credentials.password().get());
                    case RealmCallback realm -> {
                        var requested = credentials.authExtra().get("REALM");
                        realm.setText(requested == null ? realm.getDefaultText() : requested);
                    }
                    default -> throw new UnsupportedCallbackException(callback);
                }
            }
        }
    }
}
