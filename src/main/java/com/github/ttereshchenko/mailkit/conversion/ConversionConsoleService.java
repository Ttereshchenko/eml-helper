package com.github.ttereshchenko.mailkit.conversion;

import com.github.ttereshchenko.mailkit.smtp.ui.SmtpConsoleService;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class ConversionConsoleService {

    public enum Tab {
        MSG("MSG Conversion"),
        PST("PST/OST Conversion");

        public final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Project project;
    private final ConcurrentHashMap<Tab, ConsoleView> consoles = new ConcurrentHashMap<>();

    public ConversionConsoleService(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    public static ConversionConsoleService getInstance(Project project) {
        return project.getService(ConversionConsoleService.class);
    }

    public ConsoleView getOrCreateConsole(Tab tab) {
        return consoles.computeIfAbsent(
                tab,
                key -> TextConsoleBuilderFactory.getInstance()
                        .createBuilder(project)
                        .getConsole());
    }

    public void activateToolWindow(Tab tab) {
        var app = ApplicationManager.getApplication();
        Runnable show = () -> {
            var manager = ToolWindowManager.getInstance(project);
            var window = manager.getToolWindow(SmtpConsoleService.TOOL_WINDOW_ID);
            if (window != null) {
                window.show(() -> {
                    var contentManager = window.getContentManager();
                    for (var content : contentManager.getContents()) {
                        if (tab.title.equals(content.getDisplayName())) {
                            contentManager.setSelectedContent(content);
                            break;
                        }
                    }
                });
            }
        };
        if (app.isDispatchThread()) {
            show.run();
        } else {
            app.invokeLater(show, ModalityState.nonModal(), project.getDisposed());
        }
    }

    public void clear(Tab tab) {
        getOrCreateConsole(tab).clear();
    }

    public void print(Tab tab, String text, ConsoleViewContentType type) {
        var console = getOrCreateConsole(tab);
        var time = LocalDateTime.now().format(FORMATTER);
        console.print("[" + time + "] " + text + "\n", type);
    }

    public void info(Tab tab, String text) {
        print(tab, "[INFO] " + text, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    public void error(Tab tab, String text) {
        print(tab, "[ERROR] " + text, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /** Returns a {@link ConversionLog} that writes to the given tab of this console. */
    public ConversionLog asLog(Tab tab) {
        return new ConversionLog() {
            @Override
            public void info(String message) {
                ConversionConsoleService.this.info(tab, message);
            }

            @Override
            public void error(String message) {
                ConversionConsoleService.this.error(tab, message);
            }
        };
    }
}
