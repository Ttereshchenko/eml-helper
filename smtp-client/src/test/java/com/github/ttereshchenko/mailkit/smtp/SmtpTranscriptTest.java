package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SmtpTranscriptTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void renderRedactsAuthLinesByDefaultAndRevealsOnDemand() {
        var transcript = new SmtpTranscript();
        transcript.append(SmtpTranscript.Direction.CLIENT, bytes("EHLO box"), Phase.FIRST_HELO);
        transcript.append(SmtpTranscript.Direction.CLIENT_AUTH, bytes("AUTH PLAIN c2VjcmV0"), Phase.AUTH);
        transcript.append(SmtpTranscript.Direction.SERVER, bytes("235 ok"), Phase.AUTH);
        transcript.append(SmtpTranscript.Direction.INFO, bytes("note"), Phase.AUTH);

        var redacted = transcript.render(false);
        assertTrue(redacted.contains("<auth credentials scrubbed>"), redacted);
        assertFalse(redacted.contains("c2VjcmV0"), redacted);
        assertTrue(redacted.contains("C: EHLO box"), redacted);
        assertTrue(redacted.contains("S: 235 ok"), redacted);
        assertTrue(redacted.contains("# note"), redacted);

        var revealed = transcript.render(true);
        assertTrue(revealed.contains("AUTH PLAIN c2VjcmV0"), revealed);
    }

    @Test
    void listenerReceivesEveryEntryInOrder() {
        var seen = new ArrayList<String>();
        var transcript = new SmtpTranscript(entry -> seen.add(new String(entry.bytes(), StandardCharsets.UTF_8)));
        transcript.append(SmtpTranscript.Direction.CLIENT, bytes("one"), Phase.CONNECT);
        transcript.append(SmtpTranscript.Direction.SERVER, bytes("two"), Phase.BANNER);

        assertEquals(java.util.List.of("one", "two"), seen);
        assertEquals(2, transcript.entries().size());
    }

    @Test
    void entryBytesAreDefensivelyCopied() {
        var transcript = new SmtpTranscript();
        var original = bytes("payload");
        transcript.append(SmtpTranscript.Direction.CLIENT, original, Phase.DATA);
        original[0] = 'X';

        var stored = transcript.entries().get(0).bytes();
        assertEquals('p', (char) stored[0], "stored entry must be unaffected by caller mutation");
        stored[0] = 'Y';
        assertEquals('p', (char) transcript.entries().get(0).bytes()[0], "accessor must hand out copies");
    }
}
