package com.github.ttereshchenko.mailkit.settings;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class EditableComboHeaderNamePrompter implements HeaderNamePrompter {

    @Override
    public @Nullable String prompt(Component parent, List<String> suggestions) {
        var dialog = new HeaderInputDialog(parent, suggestions);
        if (!dialog.showAndGet()) {
            return null;
        }
        var entered = dialog.getEnteredName();
        return entered.isBlank() ? null : entered.trim();
    }

    private static final class HeaderInputDialog extends DialogWrapper {
        private final ComboBox<String> combo;

        HeaderInputDialog(@NotNull Component parent, List<String> suggestions) {
            super(parent, true);
            setTitle("Add Header");
            combo = new ComboBox<>(suggestions.toArray(String[]::new));
            combo.setEditable(true);
            combo.setSelectedItem("");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            var panel = new JPanel(new BorderLayout(0, 6));
            panel.add(new JLabel("Enter header name (e.g., Content-Type):"), BorderLayout.NORTH);
            panel.add(combo, BorderLayout.CENTER);
            return panel;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return combo;
        }

        String getEnteredName() {
            var item = combo.getEditor().getItem();
            return item == null ? "" : item.toString();
        }
    }
}
