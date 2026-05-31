package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.icons.MailkitIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

public final class OpenAttachmentWithSystemAppAction extends AnAction {

    private static final String STAGING_PARENT = "mailkit-attachments";

    public OpenAttachmentWithSystemAppAction() {
        super(
                "Open Attachment with System App",
                "Write the decoded MIME attachment to a temp file and open it with the OS handler",
                MailkitIcons.SAVE_ATTACHMENT);
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
        runOpen(project, info);
    }

    static void runOpen(Project project, AttachmentPartInfo info) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Opening attachment", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                File destination;
                try {
                    var parent = Path.of(PathManager.getTempPath(), STAGING_PARENT);
                    Files.createDirectories(parent);
                    pruneStaleSiblings(parent);
                    var directory = Files.createTempDirectory(parent, "open-");
                    Disposer.register(ApplicationManager.getApplication(), () -> FileUtil.delete(directory.toFile()));
                    destination = directory.resolve(info.filename()).toFile();
                } catch (IOException failure) {
                    AttachmentActionSupport.notifyError(project, "Could not stage attachment: " + failure.getMessage());
                    return;
                }
                // Stream-decode straight to the temp file so the full decoded payload is never held in
                // memory; the staging directory is Disposer-registered, so a partial file is cleaned up.
                try (var out = Files.newOutputStream(destination.toPath())) {
                    AttachmentDecoder.decodeTo(info.rawBody(), info.encoding(), out);
                } catch (DecodingException failure) {
                    AttachmentActionSupport.notifyError(
                            project, "Could not decode attachment: " + failure.getMessage());
                    return;
                } catch (IOException failure) {
                    AttachmentActionSupport.notifyError(project, "Could not stage attachment: " + failure.getMessage());
                    return;
                }
                openWithSystem(project, destination);
            }
        });
    }

    private static void pruneStaleSiblings(Path parent) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (var entry : entries) {
                FileUtil.delete(entry.toFile());
            }
        } catch (IOException ignored) {
            // best-effort: leftovers from a crashed prior session are non-fatal
        }
    }

    private static void openWithSystem(Project project, File destination) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!confirmOpenIfDangerous(project, destination)) {
                return;
            }
            if (Desktop.isDesktopSupported()) {
                var desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    try {
                        desktop.open(destination);
                        return;
                    } catch (IOException failure) {
                        AttachmentActionSupport.notifyError(
                                project, "Could not open attachment: " + failure.getMessage());
                        return;
                    }
                }
            }
            RevealFileAction.openFile(destination);
        });
    }

    /**
     * For an attachment whose type the OS might execute or open as active content, asks the user to
     * confirm before handing the (untrusted) decoded bytes to the system handler. Returns
     * {@code true} when the open should proceed. Must be called on the EDT.
     */
    private static boolean confirmOpenIfDangerous(Project project, File destination) {
        if (!DangerousAttachmentExtensions.isDangerous(destination.getName())) {
            return true;
        }
        var answer = Messages.showYesNoDialog(
                project,
                "\"" + destination.getName()
                        + "\" came from an email and its type can run code or open active content when handed to the"
                        + " system application.\n\nOpen it anyway?",
                "Potentially Unsafe Attachment",
                "Open Anyway",
                "Cancel",
                Messages.getWarningIcon());
        return answer == Messages.YES;
    }
}
