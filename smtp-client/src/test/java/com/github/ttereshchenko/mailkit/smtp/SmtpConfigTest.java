package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmtpConfigTest {

    @Test
    void ehloHostWithLineBreakIsRejectedAtConstruction() {
        // Without this, a hostile ehloHost would only be caught by the write-time guard and
        // surface as a confusing IO_ERROR mid-transaction.
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> SmtpConfig.defaults("localhost").withEhloHost("box\r\nMAIL FROM:<x@y>"));
        assertTrue(failure.getMessage().contains("ehloHost"), failure.getMessage());
    }

    @Test
    void hostWithLineBreakIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> SmtpConfig.defaults("host\r\n.example"));
    }

    @Test
    void ipLiteralsAreBracketedForEhlo() {
        // rfc5321 §4.1.3: an IP used as the EHLO identity must be an address literal.
        assertEquals("[192.0.2.1]", SmtpConfig.bracketIfAddressLiteral("192.0.2.1"));
        assertEquals("[IPv6:2001:db8::1]", SmtpConfig.bracketIfAddressLiteral("2001:db8::1"));
        assertEquals("workstation.example.com", SmtpConfig.bracketIfAddressLiteral("workstation.example.com"));
        assertEquals("buildbox", SmtpConfig.bracketIfAddressLiteral("buildbox"));
    }

    @Test
    void withersPreserveEveryOtherField() {
        var config = SmtpConfig.defaults("smtp.example.com")
                .withHost("relay.example.com")
                .withPort(2525)
                .withEhloHost("client.example.com");
        assertEquals("relay.example.com", config.host());
        assertEquals(2525, config.port());
        assertEquals("client.example.com", config.ehloHost());
        assertEquals(SmtpConfig.Protocol.ESMTP, config.protocol());
    }
}
