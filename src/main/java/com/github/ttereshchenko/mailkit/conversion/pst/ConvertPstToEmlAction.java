package com.github.ttereshchenko.mailkit.conversion.pst;

import com.github.ttereshchenko.mailkit.conversion.ConversionConsoleService;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Thin IntelliJ action that gathers options from {@link PstConversionDialog} and delegates the
 * actual work to the pure {@link PstToEmlConverter}, reporting progress/errors through the
 * PST/OST console tab and a balloon notification.
 */
public final class ConvertPstToEmlAction extends AnAction {

    private static final String NOTIFICATION_GROUP_ID = "MailKit";

    public ConvertPstToEmlAction() {
        super("Convert to EML", "Convert the selected PST/OST archive to EML files", null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        var visible = file != null
                && !file.isDirectory()
                && ("pst".equalsIgnoreCase(file.getExtension()) || "ost".equalsIgnoreCase(file.getExtension()));
        event.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var source = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || source == null) {
            return;
        }

        var dialog = new PstConversionDialog(project, source);
        if (!dialog.showAndGet()) {
            return;
        }

        var options = new PstToEmlConverter.Options(
                dialog.getDuplicateHandling(),
                dialog.getMessageCountLimit(),
                dialog.useOriginalSmtpHeaders(),
                dialog.skipEmptyFolders(),
                dialog.getAddressPreference(),
                dialog.recoverDeletedItems(),
                dialog.scanOrphans(),
                dialog.getMaxNodeSize(),
                dialog.exportNonMailItems(),
                dialog.verifyCrc());

        runConversion(project, source, dialog.getTargetDirectory(), options);
    }

    private static void runConversion(
            Project project, VirtualFile source, Path targetDir, PstToEmlConverter.Options options) {
        var console = ConversionConsoleService.getInstance(project);
        console.clear(ConversionConsoleService.Tab.PST);
        console.activateToolWindow(ConversionConsoleService.Tab.PST);
        console.info(ConversionConsoleService.Tab.PST, "Starting conversion of " + source.getName() + "...");
        ConversionLog log = console.asLog(ConversionConsoleService.Tab.PST);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Converting PST/OST to EML", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try (var pstFile = new PstFile(source.toNioPath(), options.maxNodeSize(), options.verifyCrc())) {
                    var stats = PstToEmlConverter.convert(pstFile, targetDir, options, indicator, log);
                    indicator.setIndeterminate(false);
                    reportSuccess(project, source.getName(), stats, log);
                } catch (ProcessCanceledException canceled) {
                    log.info("Conversion canceled.");
                    throw canceled;
                } catch (Exception failure) {
                    var description = PstToEmlConverter.describeFailure(failure);
                    log.error("Failure: " + description);
                    notifyError(project, source.getName(), description);
                } finally {
                    VfsUtil.markDirtyAndRefresh(true, true, true, targetDir.toFile());
                }
            }
        });
    }

    private static void reportSuccess(
            Project project, String sourceName, PstToEmlConverter.Stats stats, ConversionLog log) {
        var failedMessages = stats.failedMessages();
        var failedFolders = stats.failedFolders();
        var recovered = stats.recoveredDeleted() + stats.recoveredOrphans();
        var summary = "Converted " + stats.converted() + " messages"
                + (recovered > 0 ? " (incl. " + recovered + " recovered)" : "")
                + (failedMessages > 0 ? ", failed " + failedMessages : "")
                + (failedFolders > 0 ? ", failed " + failedFolders + " folders" : "");
        log.info(summary + " from " + sourceName + ".");

        var type = (failedMessages > 0 || failedFolders > 0) ? NotificationType.WARNING : NotificationType.INFORMATION;
        ApplicationManager.getApplication()
                .invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(summary + " from " + sourceName + " into folders.", type)
                        .notify(project));
    }

    private static void notifyError(Project project, String sourceName, String message) {
        ApplicationManager.getApplication()
                .invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification("Could not convert " + sourceName + ": " + message, NotificationType.ERROR)
                        .notify(project));
    }
}
