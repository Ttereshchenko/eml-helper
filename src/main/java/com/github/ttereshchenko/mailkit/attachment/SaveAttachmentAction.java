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
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        var descriptor = new FileSaverDescriptor("Save Attachment As", "Choose a destination for the attachment");
        var dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);
        var wrapper = dialog.save((VirtualFile) null, info.filename());
        if (wrapper == null) {
            return;
        }
        var destination = wrapper.getFile();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Saving attachment", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                // Stream-decode straight to the chosen file so the full decoded payload is never held
                // in memory (a large base64 attachment otherwise pins ~its whole size as a byte[]).
                var target = destination.toPath();
                try (var out = Files.newOutputStream(target)) {
                    AttachmentDecoder.decodeTo(info.rawBody(), info.encoding(), out);
                } catch (DecodingException failure) {
                    deleteQuietly(target);
                    AttachmentActionSupport.notifyError(
                            project, "Could not decode attachment: " + failure.getMessage());
                    return;
                } catch (IOException failure) {
                    deleteQuietly(target);
                    AttachmentActionSupport.notifyError(project, "Could not write attachment: " + failure.getMessage());
                    return;
                }
                AttachmentActionSupport.notifySuccess(project, destination, destination.length());
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of a partial file left by a failed decode/write
        }
    }
}
