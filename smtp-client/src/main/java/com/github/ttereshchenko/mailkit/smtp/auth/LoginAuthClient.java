package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Microsoft's pre-SASL LOGIN mechanism: two challenge/response rounds (username, then password). */
public final class LoginAuthClient implements AuthClient {

    private final AuthCredentials credentials;
    private int round;

    public LoginAuthClient(AuthCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public AuthMechanism mechanism() {
        return AuthMechanism.LOGIN;
    }

    @Override
    public byte[] initial() {
        return null;
    }

    @Override
    public byte[] respond(byte[] challenge) {
        Objects.requireNonNull(challenge, "challenge");
        round++;
        // Prepare the username/password with SASLprep for parity with how a server normalizes a
        // non-ASCII credential; saslPrep is a no-op for ASCII so ASCII credentials are byte-identical.
        return switch (round) {
            case 1 -> ScramAuthClient.saslPrep(credentials.username()).getBytes(StandardCharsets.UTF_8);
            case 2 -> {
                var chars = credentials.password().get();
                var prepared = ScramAuthClient.saslPrep(new String(chars)).toCharArray();
                Arrays.fill(chars, '\0');
                var bytes = toBytes(prepared);
                Arrays.fill(prepared, '\0');
                yield bytes;
            }
            default -> throw new IllegalStateException("unexpected LOGIN round: " + round);
        };
    }

    @Override
    public boolean isComplete() {
        return round >= 2;
    }

    private static byte[] toBytes(char[] chars) {
        var byteBuffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        var bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return bytes;
    }
}
