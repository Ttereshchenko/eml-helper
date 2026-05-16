package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.icons.MailkitIcons;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import java.awt.event.MouseEvent;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SaveAttachmentLineMarkerProvider extends LineMarkerProviderDescriptor {

    @Override
    public @Nullable @NlsContexts.Label String getName() {
        return "MailKit attachment";
    }

    @Override
    public @Nullable Icon getIcon() {
        return MailkitIcons.SAVE_ATTACHMENT;
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!EmlHeaderSettings.getInstance().isShowAttachmentActions()) {
            return null;
        }
        if (!(element instanceof LeafPsiElement leaf)) {
            return null;
        }
        if (leaf.getElementType() != EmlTokenTypes.BOUNDARY_START) {
            return null;
        }
        var parent = leaf.getParent();
        if (!(parent instanceof EmlMimePart part)) {
            return null;
        }
        var detected = AttachmentDetector.detect(part);
        if (detected.isEmpty()) {
            return null;
        }
        var info = detected.get();
        return new LineMarkerInfo<>(
                leaf,
                leaf.getTextRange(),
                MailkitIcons.SAVE_ATTACHMENT,
                ignored -> "Save attachment: " + info.filename(),
                (event, anchor) -> showPopup(event, anchor, info),
                GutterIconRenderer.Alignment.LEFT,
                () -> "MailKit attachment");
    }

    private static void showPopup(MouseEvent event, PsiElement anchor, AttachmentPartInfo info) {
        var project = anchor.getProject();
        var actionGroup = new DefaultActionGroup();
        actionGroup.add(new InvokeAction(
                "Save Attachment As…",
                MailkitIcons.SAVE_ATTACHMENT,
                () -> SaveAttachmentAction.runSave(project, info)));
        actionGroup.add(new InvokeAction(
                "Open with System App", null, () -> OpenAttachmentWithSystemAppAction.runOpen(project, info)));
        var popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                        info.filename(),
                        actionGroup,
                        DataContext.EMPTY_CONTEXT,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true,
                        ActionPlaces.EDITOR_GUTTER_POPUP);
        if (event != null && event.getComponent() != null) {
            popup.show(new com.intellij.ui.awt.RelativePoint(event));
        } else {
            popup.showInFocusCenter();
        }
    }

    private static final class InvokeAction extends AnAction {
        private final Runnable runnable;

        InvokeAction(String text, Icon icon, Runnable runnable) {
            super(text, null, icon);
            this.runnable = runnable;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            runnable.run();
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            Presentation presentation = event.getPresentation();
            presentation.setEnabledAndVisible(true);
        }
    }
}
