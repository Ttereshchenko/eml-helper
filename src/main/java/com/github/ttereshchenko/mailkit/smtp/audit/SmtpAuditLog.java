package com.github.ttereshchenko.mailkit.smtp.audit;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Per-project, on-disk SMTP send log. Stored at {@code <project>/.idea/mailkit/smtp-log.json}
 * with hand-rolled JSON to avoid adding a parser dependency. Trim happens on every append so a
 * runaway send loop cannot grow the file unbounded.
 */
@Service(Service.Level.PROJECT)
public final class SmtpAuditLog {

    private static final Logger LOG = Logger.getInstance(SmtpAuditLog.class);
    private static final String DEFAULT_RELATIVE_PATH = ".idea/mailkit/smtp-log.json";
    private static final int DEFAULT_RETENTION = 100;

    private final Project project;
    private final int retention;

    public SmtpAuditLog(Project project) {
        this(project, DEFAULT_RETENTION);
    }

    SmtpAuditLog(Project project, int retention) {
        this.project = Objects.requireNonNull(project, "project");
        this.retention = Math.max(1, retention);
    }

    public static SmtpAuditLog getInstance(Project project) {
        return project.getService(SmtpAuditLog.class);
    }

    public synchronized List<SmtpAuditEntry> readAll() {
        var path = logPath();
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        try {
            var content = Files.readString(path, StandardCharsets.UTF_8);
            return SmtpAuditJson.readAll(content);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Could not read SMTP audit log at " + path, failure);
            return List.of();
        }
    }

    public synchronized void append(SmtpAuditEntry entry) {
        var path = logPath();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            var entries = new ArrayList<>(readAll());
            entries.add(entry);
            if (entries.size() > retention) {
                entries = new ArrayList<>(entries.subList(entries.size() - retention, entries.size()));
            }
            Files.writeString(path, SmtpAuditJson.writeAll(entries), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOG.warn("Could not append SMTP audit entry to " + path, failure);
        }
    }

    public synchronized void clear() {
        var path = logPath();
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            LOG.warn("Could not delete SMTP audit log at " + path, failure);
        }
    }

    Path logPath() {
        var basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }
        return Path.of(basePath, DEFAULT_RELATIVE_PATH);
    }

    int retention() {
        return retention;
    }

    /** Convenience helper for callers that have unmodifiable views in hand. */
    public static List<SmtpAuditEntry> snapshot(List<SmtpAuditEntry> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
