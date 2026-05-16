package com.github.ttereshchenko.mailkit.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intellij.openapi.options.Configurable;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ColorSchemePageRefresherTest {

    @Test
    void notModifiedConfigurableIsBothResetAndDisposed() {
        var spy = new RecordingConfigurable(false);
        ColorSchemePageRefresher.refreshConfigurable(spy);
        assertEquals(1, spy.resetCalls);
        assertEquals(1, spy.disposeCalls);
    }

    @Test
    void modifiedConfigurableIsLeftUntouched() {
        var spy = new RecordingConfigurable(true);
        ColorSchemePageRefresher.refreshConfigurable(spy);
        assertEquals(0, spy.resetCalls);
        assertEquals(0, spy.disposeCalls);
    }

    @Test
    void nullConfigurableIsNoOp() {
        ColorSchemePageRefresher.refreshConfigurable(null);
    }

    private static final class RecordingConfigurable implements Configurable {
        private final boolean modified;
        int resetCalls;
        int disposeCalls;

        RecordingConfigurable(boolean modified) {
            this.modified = modified;
        }

        @Override
        public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
            return "Recording";
        }

        @Override
        public @Nullable JComponent createComponent() {
            return null;
        }

        @Override
        public boolean isModified() {
            return modified;
        }

        @Override
        public void apply() {}

        @Override
        public void reset() {
            resetCalls++;
        }

        @Override
        public void disposeUIResources() {
            disposeCalls++;
        }
    }
}
