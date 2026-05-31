package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hand-rolled RFC 5802 / RFC 7677 SCRAM client. Drives the four-message exchange and computes
 * client proof + verifies server signature. Parameterised by the underlying hash so both
 * SCRAM-SHA-1 and SCRAM-SHA-256 share the algorithm.
 */
public class ScramAuthClient implements AuthClient {

    enum Phase {
        CLIENT_FIRST,
        AWAITING_SERVER_FIRST,
        AWAITING_SERVER_FINAL,
        COMPLETE
    }

    /**
     * Upper bound on the server-supplied PBKDF2 iteration count. RFC 7677 mandates only a minimum
     * (4096); a hostile or buggy server could otherwise send {@code i=2000000000} and pin the send
     * thread in PBKDF2 (which is not cancellation-aware). One million keeps a worst-case derivation
     * well under a second while comfortably covering every legitimate server policy.
     */
    private static final int MAX_ITERATIONS = 1_000_000;

    private final AuthMechanism mechanism;
    private final AuthCredentials credentials;
    private final String hashAlgorithm;
    private final String hmacAlgorithm;
    private final String pbkdf2Algorithm;
    private final String clientNonce;
    private Phase phase = Phase.CLIENT_FIRST;
    private String clientFirstBare = "";
    private byte[] expectedServerSignature;

    protected ScramAuthClient(
            AuthMechanism mechanism,
            AuthCredentials credentials,
            String hashAlgorithm,
            String hmacAlgorithm,
            String pbkdf2Algorithm,
            String clientNonceOverride) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.hashAlgorithm = Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
        this.hmacAlgorithm = Objects.requireNonNull(hmacAlgorithm, "hmacAlgorithm");
        this.pbkdf2Algorithm = Objects.requireNonNull(pbkdf2Algorithm, "pbkdf2Algorithm");
        this.clientNonce = Objects.requireNonNullElseGet(clientNonceOverride, ScramAuthClient::generateNonce);
    }

    @Override
    public AuthMechanism mechanism() {
        return mechanism;
    }

    @Override
    public byte[] initial() {
        clientFirstBare = "n=" + saslNormalize(credentials.username()) + ",r=" + clientNonce;
        var clientFirstMessage = "n,," + clientFirstBare;
        phase = Phase.AWAITING_SERVER_FIRST;
        return clientFirstMessage.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] respond(byte[] challenge) {
        Objects.requireNonNull(challenge, "challenge");
        return switch (phase) {
            case AWAITING_SERVER_FIRST -> handleServerFirst(new String(challenge, StandardCharsets.UTF_8));
            case AWAITING_SERVER_FINAL -> handleServerFinal(new String(challenge, StandardCharsets.UTF_8));
            default -> throw new IllegalStateException("unexpected challenge in SCRAM phase " + phase);
        };
    }

    @Override
    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    private byte[] handleServerFirst(String message) {
        var attrs = parseAttributes(message);
        var combinedNonce = attrs.get("r");
        var saltBase64 = attrs.get("s");
        var iterCountText = attrs.get("i");
        if (combinedNonce == null || saltBase64 == null || iterCountText == null) {
            throw new IllegalStateException("malformed SCRAM server-first: " + message);
        }
        if (!combinedNonce.startsWith(clientNonce)) {
            throw new IllegalStateException("SCRAM server nonce does not echo client nonce");
        }
        var salt = Base64.getDecoder().decode(saltBase64);
        var iterations = parseIterationCount(iterCountText);

        var passwordChars = credentials.password().get();
        byte[] saltedPassword;
        byte[] clientKey;
        byte[] storedKey;
        byte[] clientSignature;
        byte[] clientProof;
        byte[] serverKey;
        byte[] serverSig;
        try {
            saltedPassword = pbkdf2(passwordChars, salt, iterations);
            clientKey = hmac(saltedPassword, "Client Key".getBytes(StandardCharsets.UTF_8));
            storedKey = hash(clientKey);
            var clientFinalWithoutProof = "c=biws,r=" + combinedNonce;
            var authMessage = clientFirstBare + "," + message + "," + clientFinalWithoutProof;
            clientSignature = hmac(storedKey, authMessage.getBytes(StandardCharsets.UTF_8));
            clientProof = xor(clientKey, clientSignature);
            serverKey = hmac(saltedPassword, "Server Key".getBytes(StandardCharsets.UTF_8));
            serverSig = hmac(serverKey, authMessage.getBytes(StandardCharsets.UTF_8));
            expectedServerSignature = serverSig;
            phase = Phase.AWAITING_SERVER_FINAL;
            var clientFinal =
                    clientFinalWithoutProof + ",p=" + Base64.getEncoder().encodeToString(clientProof);
            return clientFinal.getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    private byte[] handleServerFinal(String message) {
        var attrs = parseAttributes(message);
        var verifierBase64 = attrs.get("v");
        if (verifierBase64 == null) {
            var errorCode = attrs.get("e");
            throw new IllegalStateException(
                    "SCRAM server-final rejected: " + Objects.requireNonNullElse(errorCode, message));
        }
        var verifier = Base64.getDecoder().decode(verifierBase64);
        if (!MessageDigest.isEqual(verifier, expectedServerSignature)) {
            throw new IllegalStateException("SCRAM server signature did not verify");
        }
        phase = Phase.COMPLETE;
        return new byte[0];
    }

    private static Map<String, String> parseAttributes(String message) {
        var map = new HashMap<String, String>();
        for (var token : message.split(",")) {
            var equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            map.put(token.substring(0, equals), token.substring(equals + 1));
        }
        return map;
    }

    private static String saslNormalize(String username) {
        // SCRAM uses SASLprep — we approximate by escaping = and , per RFC 5802 §5.1.
        return username.replace("=", "=3D").replace(",", "=2C");
    }

    private byte[] hash(byte[] input) {
        try {
            return MessageDigest.getInstance(hashAlgorithm).digest(input);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("missing hash algorithm: " + hashAlgorithm, missing);
        }
    }

    private byte[] hmac(byte[] key, byte[] data) {
        try {
            var mac = Mac.getInstance(hmacAlgorithm);
            mac.init(new SecretKeySpec(key, hmacAlgorithm));
            return mac.doFinal(data);
        } catch (Exception failure) {
            throw new IllegalStateException("HMAC failed: " + failure.getMessage(), failure);
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            var factory = SecretKeyFactory.getInstance(pbkdf2Algorithm);
            int keyLengthBits = hashOutputLengthBits();
            var spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKey derived = factory.generateSecret(spec);
            return derived.getEncoded();
        } catch (Exception failure) {
            throw new IllegalStateException("PBKDF2 failed: " + failure.getMessage(), failure);
        }
    }

    private int hashOutputLengthBits() {
        try {
            return MessageDigest.getInstance(hashAlgorithm).getDigestLength() * 8;
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("missing hash algorithm: " + hashAlgorithm, missing);
        }
    }

    private static byte[] xor(byte[] left, byte[] right) {
        var output = new byte[left.length];
        for (var index = 0; index < left.length; index++) {
            output[index] = (byte) (left[index] ^ right[index]);
        }
        return output;
    }

    private static String generateNonce() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Parses and bounds the server-supplied SCRAM iteration count. Rejects a non-numeric, non-positive,
     * or above-{@link #MAX_ITERATIONS} value so a hostile server cannot drive PBKDF2 into a CPU-burn.
     */
    private static int parseIterationCount(String iterCountText) {
        int iterations;
        try {
            iterations = Integer.parseInt(iterCountText);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("malformed SCRAM iteration count: " + iterCountText, invalid);
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalStateException(
                    "SCRAM iteration count out of range (1.." + MAX_ITERATIONS + "): " + iterations);
        }
        return iterations;
    }
}
