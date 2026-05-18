package com.github.ttereshchenko.mailkit.smtp.audit;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.time.Instant;
import java.util.List;

public class SmtpAuditLogTest extends BasePlatformTestCase {

    public void testAppendThenReadRoundsTripsTheEntry() {
        var log = SmtpAuditLog.getInstance(getProject());
        log.clear();
        var entry = makeEntry("Mailpit-dev", 587, true);

        log.append(entry);

        var loaded = log.readAll();
        assertEquals(1, loaded.size());
        assertEquals("Mailpit-dev", loaded.get(0).profileName());
        assertEquals(587, loaded.get(0).port());
    }

    public void testRetentionDropsOldestEntriesOnAppend() {
        var log = new SmtpAuditLog(getProject(), 2);
        log.clear();
        log.append(makeEntry("first", 1, true));
        log.append(makeEntry("second", 2, true));
        log.append(makeEntry("third", 3, false));

        var loaded = log.readAll();
        assertEquals(2, loaded.size());
        assertEquals("second", loaded.get(0).profileName());
        assertEquals("third", loaded.get(1).profileName());
    }

    public void testClearWipesTheFile() {
        var log = SmtpAuditLog.getInstance(getProject());
        log.append(makeEntry("doomed", 1, true));

        log.clear();

        assertEquals(0, log.readAll().size());
    }

    private SmtpAuditEntry makeEntry(String name, int port, boolean success) {
        return new SmtpAuditEntry(
                Instant.now(),
                name,
                "smtp.example.com",
                port,
                "",
                "",
                "PLAIN",
                "from@example.com",
                List.of(new SmtpAuditEntry.Recipient("to@example.com", 250, "OK", true)),
                1024L,
                12L,
                "",
                false,
                success,
                "",
                "",
                "");
    }
}
