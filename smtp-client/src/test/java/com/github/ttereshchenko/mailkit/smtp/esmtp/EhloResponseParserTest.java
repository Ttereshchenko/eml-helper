package com.github.ttereshchenko.mailkit.smtp.esmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.SmtpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class EhloResponseParserTest {

    @Test
    void firstLineIsTreatedAsTheGreetingNotAsACapability() {
        var response = new SmtpResponse(250, List.of("smtp.example.com Hello", "STARTTLS", "PIPELINING"));
        var caps = EhloResponseParser.parse(response);
        assertTrue(caps.containsKey("STARTTLS"));
        assertTrue(caps.containsKey("PIPELINING"));
        assertEquals(2, caps.size());
    }

    @Test
    void multiArgumentLinesPreserveArgumentOrder() {
        var response = new SmtpResponse(250, List.of("hello", "AUTH PLAIN LOGIN XOAUTH2", "SIZE 52428800"));
        var caps = EhloResponseParser.parse(response);
        assertEquals(List.of("PLAIN", "LOGIN", "XOAUTH2"), caps.get("AUTH"));
        assertEquals(List.of("52428800"), caps.get("SIZE"));
    }

    @Test
    void keywordsAreUppercased() {
        var response = new SmtpResponse(250, List.of("hello", "starttls", "pipelining"));
        var caps = EhloResponseParser.parse(response);
        assertTrue(caps.containsKey("STARTTLS"));
        assertTrue(caps.containsKey("PIPELINING"));
    }

    @Test
    void legacyAuthEqualsAdvertisementIsFoldedIntoAuth() {
        // Pre-rfc2554 servers advertise "AUTH=PLAIN LOGIN" — clients must still see AUTH.
        var response = new SmtpResponse(250, List.of("hello", "AUTH=PLAIN LOGIN"));
        var caps = EhloResponseParser.parse(response);
        assertEquals(List.of("PLAIN", "LOGIN"), caps.get("AUTH"));
    }

    @Test
    void duplicateKeywordLinesMergeTheirArguments() {
        var response = new SmtpResponse(250, List.of("hello", "AUTH PLAIN", "AUTH=LOGIN CRAM-MD5"));
        var caps = EhloResponseParser.parse(response);
        assertEquals(List.of("PLAIN", "LOGIN", "CRAM-MD5"), caps.get("AUTH"));
    }
}
