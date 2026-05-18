package com.github.ttereshchenko.mailkit.smtp.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmtpAuditJsonTest {

    @Test
    void roundTripPreservesEveryField() {
        var entry = new SmtpAuditEntry(
                Instant.parse("2026-05-18T12:34:56Z"),
                "Mailpit-dev",
                "smtp.example.com",
                587,
                "TLSv1.3",
                "TLS_AES_256_GCM_SHA384",
                "PLAIN",
                "sender@example.com",
                List.of(
                        new SmtpAuditEntry.Recipient("to@example.com", 250, "OK", true),
                        new SmtpAuditEntry.Recipient("bad@example.com", 550, "user unknown", false)),
                4096L,
                123L,
                "AUTH",
                false,
                true,
                "",
                "",
                "");

        var json = SmtpAuditJson.writeAll(List.of(entry));
        var restored = SmtpAuditJson.readAll(json);

        assertEquals(1, restored.size());
        var roundTripped = restored.get(0);
        assertEquals(entry.timestamp(), roundTripped.timestamp());
        assertEquals(entry.profileName(), roundTripped.profileName());
        assertEquals(entry.host(), roundTripped.host());
        assertEquals(entry.port(), roundTripped.port());
        assertEquals(entry.tlsProtocol(), roundTripped.tlsProtocol());
        assertEquals(entry.authMechanism(), roundTripped.authMechanism());
        assertEquals(entry.envelopeFrom(), roundTripped.envelopeFrom());
        assertEquals(2, roundTripped.recipients().size());
        assertEquals("to@example.com", roundTripped.recipients().get(0).address());
        assertTrue(roundTripped.recipients().get(0).accepted());
        assertEquals(550, roundTripped.recipients().get(1).code());
        assertEquals(entry.sourceBytes(), roundTripped.sourceBytes());
        assertEquals(entry.durationMillis(), roundTripped.durationMillis());
        assertEquals(entry.stopAfterPhase(), roundTripped.stopAfterPhase());
        assertEquals(entry.dropAfter(), roundTripped.dropAfter());
        assertTrue(roundTripped.success());
    }

    @Test
    void failureFieldsRoundTrip() {
        var entry = new SmtpAuditEntry(
                Instant.parse("2026-05-18T12:34:56Z"),
                "Mailpit-dev",
                "smtp.example.com",
                587,
                "",
                "",
                "(none)",
                "sender@example.com",
                List.of(),
                0L,
                42L,
                "",
                false,
                false,
                "AUTH_FAILED",
                "AUTH",
                "refusing PLAIN over a non-TLS socket");

        var restored =
                SmtpAuditJson.readAll(SmtpAuditJson.writeAll(List.of(entry))).get(0);
        assertFalse(restored.success());
        assertEquals("AUTH_FAILED", restored.errorKind());
        assertEquals("AUTH", restored.errorPhase());
        assertEquals("refusing PLAIN over a non-TLS socket", restored.errorMessage());
    }

    @Test
    void emptyArrayRoundTrips() {
        var restored = SmtpAuditJson.readAll("[]");
        assertEquals(0, restored.size());
        assertEquals("[]", SmtpAuditJson.writeAll(List.of()));
    }

    @Test
    void controlCharactersAreEscaped() {
        var entry = new SmtpAuditEntry(
                Instant.parse("2026-05-18T12:34:56Z"),
                "with \"quotes\" and \\backslash",
                "host",
                25,
                "",
                "",
                "",
                "from@example.com",
                List.of(),
                0L,
                0L,
                "",
                false,
                true,
                "",
                "",
                "newline\nhere");
        var json = SmtpAuditJson.writeAll(List.of(entry));
        var restored = SmtpAuditJson.readAll(json).get(0);
        assertEquals(entry.profileName(), restored.profileName());
        assertEquals("newline\nhere", restored.errorMessage());
    }
}
