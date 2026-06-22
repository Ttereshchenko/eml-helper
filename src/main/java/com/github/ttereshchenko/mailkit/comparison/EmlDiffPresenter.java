package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.EmlFileType;
import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the IntelliJ diff viewer to compare two EML files. Each side is rendered both as a decoded,
 * normalized canonical form (via {@link EmlNormalizer}) and as the raw message source; the viewer
 * shows the normalized form by default and a toolbar toggle flips to the raw source. Normalizing and
 * reading the files happens on a background thread; the viewer is shown on the EDT.
 */
public final class EmlDiffPresenter {

    private EmlDiffPresenter() {}

    public static void show(@NotNull Project project, @NotNull VirtualFile left, @NotNull VirtualFile right) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Comparing EML Files", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                var sides = ApplicationManager.getApplication().runReadAction((Computable<Sides>) () -> new Sides(
                        normalized(project, left), normalized(project, right), rawText(left), rawText(right)));
                ApplicationManager.getApplication()
                        .invokeLater(() -> display(project, left, right, sides), ModalityState.defaultModalityState());
            }
        });
    }

    private static void display(Project project, VirtualFile left, VirtualFile right, Sides sides) {
        var factory = DiffContentFactory.getInstance();
        var leftContent = factory.create(project, sides.leftNormalized(), EmlFileType.INSTANCE);
        var rightContent = factory.create(project, sides.rightNormalized(), EmlFileType.INSTANCE);
        var request =
                new SimpleDiffRequest("EML Comparison", leftContent, rightContent, left.getName(), right.getName());
        var toggle = new NormalizeToggleAction(leftContent.getDocument(), rightContent.getDocument(), sides);
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, List.<AnAction>of(toggle));
        DiffManager.getInstance().showDiff(project, request);
    }

    private static String normalized(Project project, VirtualFile file) {
        try {
            var psi = PsiManager.getInstance(project).findFile(file);
            if (psi instanceof EmlPsiFile eml) {
                return EmlNormalizer.normalize(eml);
            }
        } catch (RuntimeException ignored) {
            // Unparseable / oversized file: fall back to the raw text so the diff still opens.
        }
        return rawText(file);
    }

    private static String rawText(VirtualFile file) {
        return LoadTextUtil.loadText(file).toString();
    }

    private record Sides(String leftNormalized, String rightNormalized, String leftRaw, String rightRaw) {}

    /**
     * Toolbar toggle that swaps both diff documents between the normalized and the raw source text.
     * The diff contents are read-only for the viewer, so each side is briefly made writable while its
     * text is replaced; the viewer recomputes the diff when the documents change.
     */
    private static final class NormalizeToggleAction extends ToggleAction implements DumbAware {

        private final Document leftDocument;
        private final Document rightDocument;
        private final Sides sides;
        private boolean normalized = true;

        NormalizeToggleAction(Document leftDocument, Document rightDocument, Sides sides) {
            super(
                    "Show Decoded View",
                    "Toggle between the decoded/normalized view and the raw message source",
                    AllIcons.General.Filter);
            this.leftDocument = leftDocument;
            this.rightDocument = rightDocument;
            this.sides = sides;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent event) {
            return normalized;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent event, boolean state) {
            normalized = state;
            var leftText = state ? sides.leftNormalized() : sides.leftRaw();
            var rightText = state ? sides.rightNormalized() : sides.rightRaw();
            ApplicationManager.getApplication().runWriteAction(() -> {
                replaceText(leftDocument, leftText);
                replaceText(rightDocument, rightText);
            });
        }

        private static void replaceText(Document document, String text) {
            var readOnly = !document.isWritable();
            if (readOnly) {
                document.setReadOnly(false);
            }
            try {
                document.setText(text);
            } finally {
                if (readOnly) {
                    document.setReadOnly(true);
                }
            }
        }
    }
}
