package com.github.ttereshchenko.mailkit.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.project.ProjectManager;

@FunctionalInterface
interface DaemonRestarter {

    DaemonRestarter DEFAULT = reason -> {
        for (var project : ProjectManager.getInstance().getOpenProjects()) {
            DaemonCodeAnalyzer.getInstance(project).restart(reason);
        }
    };

    void restart(String reason);
}
