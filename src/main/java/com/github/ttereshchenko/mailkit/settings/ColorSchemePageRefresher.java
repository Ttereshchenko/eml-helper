package com.github.ttereshchenko.mailkit.settings;

import com.intellij.ide.DataManager;
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
        var colorAndFont = settings.find(COLOR_AND_FONT_OPTIONS_ID);
        if (colorAndFont != null && !colorAndFont.isModified()) {
            colorAndFont.reset();
        }
    };

    void refresh(JComponent rootComponent);
}
