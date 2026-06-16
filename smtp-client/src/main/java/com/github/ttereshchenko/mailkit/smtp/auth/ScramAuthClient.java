package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
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
        // rfc5802 §5.1: the username is SASLprep-normalized, then the gs2 '='/',' escaping is
        // applied to the result.
        clientFirstBare = "n=" + escapeUsername(saslPrep(credentials.username())) + ",r=" + clientNonce;
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
        // rfc5802 §5.1 / rfc4013: the salted password is derived from the SASLprep-normalized
        // password, not the raw bytes. For an ASCII password this is the identical char[].
        var preparedPassword = saslPrep(new String(passwordChars)).toCharArray();
        byte[] saltedPassword;
        byte[] clientKey;
        byte[] storedKey;
        byte[] clientSignature;
        byte[] clientProof;
        byte[] serverKey;
        byte[] serverSig;
        try {
            saltedPassword = pbkdf2(preparedPassword, salt, iterations);
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
            Arrays.fill(preparedPassword, '\0');
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

    /**
     * Applies the SASLprep stringprep profile (rfc4013) that SCRAM requires for both the username
     * and the password (rfc5802 §5.1 "the characters ... are normalized using SASLprep"; rfc5802 §3
     * "Note that ... the client ... applies SASLprep"). rfc4013 layers on the stringprep framework
     * (rfc3454):
     *
     * <ul>
     *   <li><b>Mapping</b> (rfc4013 §2.1): the rfc3454 Table C.1.2 "non-ASCII space" characters are
     *       mapped to a regular SPACE (U+0020), and the rfc3454 Table B.1 "commonly mapped to
     *       nothing" characters (soft hyphen U+00AD, zero-width and other format controls) are
     *       deleted.
     *   <li><b>Normalization</b> (rfc4013 §2.2): the result is put into Unicode Normalization Form
     *       KC (NFKC).
     * </ul>
     *
     * <p>Mapping precedes normalization, per the stringprep ordering in rfc3454 §7. We do not
     * implement the prohibition (rfc4013 §2.3) or bidi (§2.4) checks: their only effect on a valid
     * credential is to reject it, so omitting them never changes the bytes computed for a credential
     * the server would accept — and rejecting locally would just turn a server-side failure into a
     * client-side one. A pure-ASCII string is unaffected by every step here, so existing ASCII
     * credentials remain byte-identical.
     *
     * <p>This is SEPARATE from SCRAM's own {@code =}/{@code ,} escaping of the username in the gs2
     * header (rfc5802 §5.1), which {@link #escapeUsername(String)} applies afterwards.
     */
    static String saslPrep(String value) {
        if (value.isEmpty() || isAscii(value)) {
            return value;
        }
        var mapped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (isMappedToSpace(codePoint)) {
                mapped.append(' ');
            } else if (isMappedToNothing(codePoint)) {
                // dropped
            } else {
                mapped.appendCodePoint(codePoint);
            }
        });
        return Normalizer.normalize(mapped, Normalizer.Form.NFKC);
    }

    private static boolean isAscii(String value) {
        for (var index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** rfc3454 Table C.1.2: characters treated as a SPACE by SASLprep's mapping step. */
    private static boolean isMappedToSpace(int codePoint) {
        return switch (codePoint) {
            case 0x00A0,
                    0x1680,
                    0x2000,
                    0x2001,
                    0x2002,
                    0x2003,
                    0x2004,
                    0x2005,
                    0x2006,
                    0x2007,
                    0x2008,
                    0x2009,
                    0x200A,
                    0x200B,
                    0x202F,
                    0x205F,
                    0x3000 -> true;
            default -> false;
        };
    }

    /** rfc3454 Table B.1: characters deleted by SASLprep's mapping step (mapped to nothing). */
    private static boolean isMappedToNothing(int codePoint) {
        return switch (codePoint) {
            case 0x00AD,
                    0x034F,
                    0x1806,
                    0x180B,
                    0x180C,
                    0x180D,
                    0x200C,
                    0x200D,
                    0x2060,
                    0xFE00,
                    0xFE01,
                    0xFE02,
                    0xFE03,
                    0xFE04,
                    0xFE05,
                    0xFE06,
                    0xFE07,
                    0xFE08,
                    0xFE09,
                    0xFE0A,
                    0xFE0B,
                    0xFE0C,
                    0xFE0D,
                    0xFE0E,
                    0xFE0F,
                    0xFEFF -> true;
            default -> false;
        };
    }

    /** SCRAM gs2 username escaping (rfc5802 §5.1) — distinct from {@link #saslPrep(String)}. */
    private static String escapeUsername(String username) {
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
