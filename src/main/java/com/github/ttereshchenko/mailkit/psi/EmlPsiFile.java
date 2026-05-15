package com.github.ttereshchenko.mailkit.psi;

import com.github.ttereshchenko.mailkit.EmlFileType;
import com.github.ttereshchenko.mailkit.EmlLanguage;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlPsiFile extends PsiFileBase {

    public EmlPsiFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, EmlLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return EmlFileType.INSTANCE;
    }

    public @Nullable EmlHeaderBlock getHeaderBlock() {
        return PsiTreeUtil.getChildOfType(this, EmlHeaderBlock.class);
    }
}
