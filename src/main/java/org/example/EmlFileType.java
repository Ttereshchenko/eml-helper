package org.example;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.swing.Icon;

public final class EmlFileType extends LanguageFileType {
    public static final EmlFileType INSTANCE = new EmlFileType();

    private EmlFileType() {
        super(EmlLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() { return "EML"; }

    @Override
    public @NotNull String getDescription() { return "EML email message file"; }

    @Override
    public @NotNull String getDefaultExtension() { return "eml"; }

    @Override
    public @Nullable Icon getIcon() { return null; }
}
