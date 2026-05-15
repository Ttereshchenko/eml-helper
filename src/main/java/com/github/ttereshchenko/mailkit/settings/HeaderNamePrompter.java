package com.github.ttereshchenko.mailkit.settings;

import java.awt.Component;
import java.util.List;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
interface HeaderNamePrompter {

    HeaderNamePrompter DEFAULT = new EditableComboHeaderNamePrompter();

    @Nullable
    String prompt(Component parent, List<String> suggestions);
}
