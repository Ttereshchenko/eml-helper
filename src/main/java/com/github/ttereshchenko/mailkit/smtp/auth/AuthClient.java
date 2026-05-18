package com.github.ttereshchenko.mailkit.smtp.auth;

/**
 * Drives one side of a SASL conversation. Implementations are <b>not</b> reusable across sends —
 * each connection allocates a fresh instance so state (server challenges, derived secrets,
 * iterators) cannot leak between transactions.
 *
 * <p>Lifecycle: caller invokes {@link #initial()} once; if it returns non-null bytes the client
 * provided an initial response (sent alongside {@code AUTH <MECH>}). The caller then loops:
 * read challenge from server, hand to {@link #respond}, send the returned bytes, until
 * {@link #isComplete()} reports true.
 */
public interface AuthClient {

    AuthMechanism mechanism();

    /** First client message, or null if the mechanism is challenge-first (LOGIN). */
    byte[] initial();

    /** Response to a server challenge. Implementations should not mutate the input. */
    byte[] respond(byte[] challenge);

    /** True once the client believes the conversation has reached its final exchange. */
    boolean isComplete();
}
