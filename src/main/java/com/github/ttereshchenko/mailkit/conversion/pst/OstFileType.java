package com.github.ttereshchenko.mailkit.conversion.pst;

import com.intellij.openapi.fileTypes.UserBinaryFileType;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public final class OstFileType extends UserBinaryFileType {

    public static final OstFileType INSTANCE = new OstFileType();

    private static final Icon ICON = IconLoader.getIcon("/icons/ost.svg", OstFileType.class);

    private OstFileType() {
        setName("OST");
        setDescription("Outlook OST offline data file");
    }

    @Override
    public @NotNull Icon getIcon() {
        return ICON;
    }
}
