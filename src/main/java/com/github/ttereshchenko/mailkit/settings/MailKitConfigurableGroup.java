package com.github.ttereshchenko.mailkit.settings;

import com.intellij.openapi.options.Configurable;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class MailKitConfigurableGroup implements Configurable {
    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MailKit";
    }

    @Override
    public @Nullable JComponent createComponent() {
        return null; // A null component makes this a pure folder/group
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {}
}
