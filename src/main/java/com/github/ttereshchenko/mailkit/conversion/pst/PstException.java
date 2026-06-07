package com.github.ttereshchenko.mailkit.conversion.pst;

import java.io.IOException;

public class PstException extends IOException {
    public PstException(String message) {
        super(message);
    }

    public PstException(String message, Throwable cause) {
        super(message, cause);
    }
}
