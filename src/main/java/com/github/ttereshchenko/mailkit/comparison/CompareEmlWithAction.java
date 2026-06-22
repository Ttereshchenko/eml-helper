package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compares a single EML file against a second one picked from a file chooser. Available on a single
 * EML file in the Project view and on the open EML editor, so it covers both "compare this file
 * with…" entry points. Two-file selection is handled by {@link CompareEmlAction} instead.
 */
public final class CompareEmlWithAction extends AnAction {

    public CompareEmlWithAction() {
        super("Compare EML With…", "Compare this EML message against another EML file", null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var source = resolveSource(event);
        if (source == null) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        // In the editor toolbar (the floating context bar), honor the General-settings toggle, mirroring
        // the "Send EML…" button. Project-view / context-menu invocations stay available regardless.
        var place = event.getPlace();
        var isToolbar =
                event.isFromActionToolbar() || "EditorContextBarMenu".equals(place) || "ContextToolbar".equals(place);
        if (isToolbar && !EmlHeaderSettings.getInstance().isShowCompareEditorToolbarButton()) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var source = resolveSource(event);
        if (project == null || source == null) {
            return;
        }
        var descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Compare EML With")
                .withDescription("Select the EML file to compare against " + source.getName())
                .withFileFilter(candidate -> "eml".equalsIgnoreCase(candidate.getExtension()));
        var chosen = FileChooser.chooseFile(descriptor, project, source);
        if (chosen == null || chosen.equals(source)) {
            return;
        }
        EmlDiffPresenter.show(project, source, chosen);
    }

    private static @Nullable VirtualFile resolveSource(@NotNull AnActionEvent event) {
        var selection = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selection != null && selection.length > 1) {
            // A multi-file selection belongs to CompareEmlAction; keep this action single-source.
            return null;
        }
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        return CompareEmlAction.isEml(file) ? file : null;
    }
}
