package com.github.ttereshchenko.mailkit.smtp.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** RFC 4616: {@code [authzid]\0authcid\0passwd} — single-shot initial response. */
public final class PlainAuthClient implements AuthClient {

    private final AuthCredentials credentials;
    private boolean complete;

    public PlainAuthClient(AuthCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public AuthMechanism mechanism() {
        return AuthMechanism.PLAIN;
    }

    @Override
    public byte[] initial() {
        // RFC 4616 §2: the authzid, authcid and passwd SHALL be prepared with SASLprep before
        // transmission, so a non-ASCII credential matches what a SASLprep-applying server stored.
        // saslPrep is a no-op for ASCII, so existing ASCII credentials are byte-identical.
        var passwordChars = credentials.password().get();
        var preparedPassword =
                ScramAuthClient.saslPrep(new String(passwordChars)).toCharArray();
        Arrays.fill(passwordChars, '\0');
        var passwordBytes = toBytes(preparedPassword);
        Arrays.fill(preparedPassword, '\0');
        try (var buffer = new ByteArrayOutputStream()) {
            buffer.write(ScramAuthClient.saslPrep(credentials.authzId()).getBytes(StandardCharsets.UTF_8));
            buffer.write(0);
            buffer.write(ScramAuthClient.saslPrep(credentials.username()).getBytes(StandardCharsets.UTF_8));
            buffer.write(0);
            buffer.write(passwordBytes);
            complete = true;
            return buffer.toByteArray();
        } catch (IOException unreachable) {
            throw new IllegalStateException("ByteArrayOutputStream does not throw", unreachable);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    @Override
    public byte[] respond(byte[] challenge) {
        throw new IllegalStateException("PLAIN does not accept challenges");
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    private static byte[] toBytes(char[] chars) {
        var byteBuffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        var bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return bytes;
    }
}
