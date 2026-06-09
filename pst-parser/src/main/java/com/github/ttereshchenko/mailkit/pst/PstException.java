package com.github.ttereshchenko.mailkit.pst;

import java.io.IOException;

/**
 * Signals a malformed or unreadable PST/OST structure. Extends {@link IOException} so callers can
 * handle parser and I/O failures uniformly.
 */
public class PstException extends IOException {
    public PstException(String message) {
        super(message);
    }

    public PstException(String message, Throwable cause) {
        super(message, cause);
    }
}
