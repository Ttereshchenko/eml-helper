package com.github.ttereshchenko.mailkit.comparison;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-view action that compares the two selected {@code .eml} files in the diff viewer. Enabled
 * only when exactly two EML files are selected.
 */
public final class CompareEmlAction extends AnAction {

    public CompareEmlAction() {
        super("Compare EML Files", "Compare the two selected EML messages side by side", null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        event.getPresentation()
                .setEnabledAndVisible(files != null && files.length == 2 && isEml(files[0]) && isEml(files[1]));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (project == null || files == null || files.length != 2 || !isEml(files[0]) || !isEml(files[1])) {
            return;
        }
        EmlDiffPresenter.show(project, files[0], files[1]);
    }

    static boolean isEml(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && "eml".equalsIgnoreCase(file.getExtension());
    }
}
