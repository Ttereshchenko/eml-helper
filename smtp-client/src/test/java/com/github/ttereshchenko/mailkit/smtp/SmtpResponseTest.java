package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SmtpResponseTest {

    @Test
    void classificationBoundaries() {
        assertTrue(new SmtpResponse(200, List.of()).isPositiveCompletion());
        assertTrue(new SmtpResponse(299, List.of()).isPositiveCompletion());
        assertFalse(new SmtpResponse(300, List.of()).isPositiveCompletion());
        assertTrue(new SmtpResponse(354, List.of()).isPositiveIntermediate());
        assertTrue(new SmtpResponse(421, List.of()).isTransientNegative());
        assertTrue(new SmtpResponse(550, List.of()).isPermanentNegative());
        assertFalse(new SmtpResponse(199, List.of()).isPositiveCompletion());
    }

    @Test
    void firstLineIsEmptyForAnEmptyReply() {
        assertEquals("", new SmtpResponse(250, List.of()).firstLine());
        assertEquals("OK", new SmtpResponse(250, List.of("OK", "more")).firstLine());
    }
}
