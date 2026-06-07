package com.github.ttereshchenko.mailkit.conversion.pst;

import com.intellij.openapi.fileTypes.UserBinaryFileType;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public final class PstFileType extends UserBinaryFileType {

    public static final PstFileType INSTANCE = new PstFileType();

    private static final Icon ICON = IconLoader.getIcon("/icons/pst.svg", PstFileType.class);

    private PstFileType() {
        setName("PST");
        setDescription("Outlook PST data file");
    }

    @Override
    public @NotNull Icon getIcon() {
        return ICON;
    }
}
