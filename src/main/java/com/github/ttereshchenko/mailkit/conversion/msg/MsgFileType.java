package com.github.ttereshchenko.mailkit.conversion.msg;

import com.intellij.openapi.fileTypes.UserBinaryFileType;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/**
 * Binary file type for Outlook {@code .msg} files. Registers the MSG icon and name so the files are
 * recognizable in the Project view; the actual conversion lives in {@link ConvertMsgToEmlAction}.
 */
public final class MsgFileType extends UserBinaryFileType {

    public static final MsgFileType INSTANCE = new MsgFileType();

    private static final Icon ICON = IconLoader.getIcon("/icons/msg.svg", MsgFileType.class);

    private MsgFileType() {
        setName("MSG");
        setDescription("Outlook MSG email message file");
    }

    @Override
    public @NotNull Icon getIcon() {
        return ICON;
    }
}
