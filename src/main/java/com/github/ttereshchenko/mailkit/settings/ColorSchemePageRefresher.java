package com.github.ttereshchenko.mailkit.settings;

import com.intellij.ide.DataManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ex.Settings;
import javax.swing.JComponent;

@FunctionalInterface
interface ColorSchemePageRefresher {

    String COLOR_AND_FONT_OPTIONS_ID = "reference.settingsdialog.IDE.editor.colors";

    ColorSchemePageRefresher DEFAULT = component -> {
        if (component == null) {
            return;
        }
        var settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(component));
        if (settings == null) {
            return;
        }
        refreshConfigurable(settings.find(COLOR_AND_FONT_OPTIONS_ID));
        refreshConfigurable(settings.find("reference.settingsdialog.IDE.editor.colors.MailKit"));
    };

    // Force the Color & Fonts page to rebuild its UI on next navigation so the demo preview
    // editor re-reads getDemoText() and picks up newly added custom headers. reset() alone
    // refreshes descriptors but keeps the cached preview editor (issue #40).
    static void refreshConfigurable(Configurable colorAndFont) {
        if (colorAndFont != null && !colorAndFont.isModified()) {
            colorAndFont.reset();
            colorAndFont.disposeUIResources();
        }
    }

    void refresh(JComponent rootComponent);
}
