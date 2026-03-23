package com.github.ttereshchenko.emlhelper;

import com.intellij.lang.Language;

public final class EmlLanguage extends Language {
    public static final EmlLanguage INSTANCE = new EmlLanguage();

    private EmlLanguage() {
        super("EML");
    }
}
