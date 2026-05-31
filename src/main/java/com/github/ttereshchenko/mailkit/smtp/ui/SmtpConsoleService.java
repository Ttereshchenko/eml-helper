package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.SmtpTranscript;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Project-level service that owns the {@code ConsoleView} rendered inside the "MailKit SMTP"
 * tool window. The send pipeline hands a {@link SmtpTranscript.Listener} from
 * {@link #liveTranscriptListener(String)} to the wire client so every byte streams into the
 * console as it goes on the wire — color-coded by direction, with AUTH lines redacted by default.
 */
@Service(Service.Level.PROJECT)
public final class SmtpConsoleService {

    public static final String TOOL_WINDOW_ID = "MailKit SMTP";

    private static final String REDACTED = "<auth credentials scrubbed>";

    private final Project project;
    private volatile ConsoleView consoleView;
    private volatile boolean revealAuth;

    public SmtpConsoleService(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public static SmtpConsoleService getInstance(Project project) {
        return project.getService(SmtpConsoleService.class);
    }

    /**
     * Activates the tool window, ensures a {@link ConsoleView} exists in it, and returns a
     * transcript listener that streams entries to that console. The returned listener clears
     * the console before the first entry so repeat sends do not pile up.
     */
    public SmtpTranscript.Listener liveTranscriptListener(String header) {
        var console = ensureConsole();
        activateToolWindow();
        console.clear();
        if (header != null && !header.isBlank()) {
            console.print("# " + header + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        }
        var startNanos = System.nanoTime();
        return entry -> renderEntry(console, entry, startNanos);
    }

    public void setRevealAuth(boolean reveal) {
        this.revealAuth = reveal;
    }

    public ConsoleView getOrCreateConsole() {
        return ensureConsole();
    }

    private synchronized ConsoleView ensureConsole() {
        if (consoleView == null) {
            consoleView = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(project)
                    .getConsole();
        }
        return consoleView;
    }

    private void activateToolWindow() {
        var app = ApplicationManager.getApplication();
        Runnable show = () -> {
            var manager = ToolWindowManager.getInstance(project);
            var window = manager.getToolWindow(TOOL_WINDOW_ID);
            if (window != null) {
                window.show(null);
            }
        };
        if (app.isDispatchThread()) {
            show.run();
        } else {
            app.invokeLater(show, ModalityState.nonModal(), project.getDisposed());
        }
    }

    private void renderEntry(ConsoleView console, SmtpTranscript.Entry entry, long startNanos) {
        var elapsedMs = (entry.nanoTimestamp() - startNanos) / 1_000_000;
        var prefix = "[+" + elapsedMs + "ms] " + entry.phase() + " ";
        var bytesText = (entry.direction() == SmtpTranscript.Direction.CLIENT_AUTH && !revealAuth)
                ? REDACTED
                : new String(entry.bytes(), StandardCharsets.UTF_8);
        var line = prefix
                + switch (entry.direction()) {
                    case CLIENT, CLIENT_AUTH -> "C: ";
                    case SERVER -> "S: ";
                    case INFO -> "# ";
                }
                + bytesText
                + "\n";
        var contentType =
                switch (entry.direction()) {
                    case CLIENT -> ConsoleViewContentType.NORMAL_OUTPUT;
                    case CLIENT_AUTH -> ConsoleViewContentType.LOG_WARNING_OUTPUT;
                    case SERVER -> ConsoleViewContentType.LOG_INFO_OUTPUT;
                    case INFO -> ConsoleViewContentType.SYSTEM_OUTPUT;
                };
        console.print(line, contentType);
    }
}
