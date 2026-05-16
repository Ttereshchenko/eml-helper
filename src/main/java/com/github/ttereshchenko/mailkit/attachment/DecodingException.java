package com.github.ttereshchenko.mailkit.attachment;

public final class DecodingException extends Exception {
    public DecodingException(String message) {
        super(message);
    }

    public DecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
