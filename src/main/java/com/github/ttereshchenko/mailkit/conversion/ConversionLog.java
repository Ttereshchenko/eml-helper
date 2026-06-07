package com.github.ttereshchenko.mailkit.conversion;

/**
 * Minimal logging sink for conversion progress/errors, decoupled from the IntelliJ UI.
 *
 * <p>Production code passes an adapter backed by {@link ConversionConsoleService}
 * (see {@link ConversionConsoleService#asLog}); tests pass {@link #NOOP}. This lets the
 * pure conversion logic stay free of {@code if (console != null)} guards and of a direct
 * dependency on the console service.
 */
public interface ConversionLog {

    void info(String message);

    void error(String message);

    /** A sink that discards everything — used by tests and as a safe default. */
    ConversionLog NOOP = new ConversionLog() {
        @Override
        public void info(String message) {}

        @Override
        public void error(String message) {}
    };
}
