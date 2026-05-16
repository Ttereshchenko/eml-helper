package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.icons.MailkitIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import java.nio.file.Files;
import org.jetbrains.annotations.NotNull;

public final class SaveAttachmentAction extends AnAction {

    public SaveAttachmentAction() {
        super("Save Attachment As…", "Save the decoded MIME attachment to disk", MailkitIcons.SAVE_ATTACHMENT);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return AttachmentActionSupport.updateThread();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var info = AttachmentActionSupport.resolve(event);
        event.getPresentation().setEnabledAndVisible(info.isPresent());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var info = AttachmentActionSupport.resolve(event).orElse(null);
        if (info == null) {
            return;
        }
        runSave(project, info);
    }

    static void runSave(Project project, AttachmentPartInfo info) {
        byte[] decoded;
        try {
            decoded = AttachmentDecoder.decode(info.rawBody(), info.encoding());
        } catch (DecodingException failure) {
            AttachmentActionSupport.notifyError(project, "Could not decode attachment: " + failure.getMessage());
            return;
        }

        var descriptor = new FileSaverDescriptor("Save Attachment As", "Choose a destination for the attachment");
        var dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        var wrapper = dialog.save((com.intellij.openapi.vfs.VirtualFile) null, info.filename());
        if (wrapper == null) {
            return;
        }
        var destination = wrapper.getFile();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Saving attachment", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    Files.write(destination.toPath(), decoded);
                    AttachmentActionSupport.notifySuccess(project, destination, decoded.length);
                } catch (java.io.IOException failure) {
                    AttachmentActionSupport.notifyError(project, "Could not write attachment: " + failure.getMessage());
                }
            }
        });
    }
}
