package com.github.ttereshchenko.mailkit.conversion;

import java.io.IOException;

/**
 * Domain exception for message-conversion failures. Wrapping POI/parser-specific exceptions in this
 * single type gives the conversion API a clean boundary: callers (and any future standalone-library
 * extraction) catch one type instead of leaking {@code ChunkNotFoundException}, POI's unchecked
 * {@code RecordFormatException}, or raw stream {@code IOException}s. It extends {@link IOException}
 * so existing {@code catch (IOException)} sites keep working.
 */
public class ConversionException extends IOException {
    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
