package com.github.ttereshchenko.mailkit.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;
import javax.swing.JCheckBox;

public class EmlHeaderSettingsConfigurableTest extends BasePlatformTestCase {

    private EmlHeaderSettingsConfigurable configurable;
    private boolean originalEnabled;
    private List<String> originalHighlighted;
    private List<String> originalNameOnly;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var settings = EmlHeaderSettings.getInstance();
        originalEnabled = settings.isHighlightingEnabled();
        originalHighlighted = List.copyOf(settings.getHighlightedHeaders());
        originalNameOnly = List.copyOf(settings.getNameOnlyHeaders());
        settings.setHighlightingEnabled(true);
        settings.setHighlightedHeaders(List.of("From", "To"));
        settings.setNameOnlyHeaders(List.of("From"));
        configurable = new EmlHeaderSettingsConfigurable();
    }

    @Override
    protected void tearDown() throws Exception {
        var settings = EmlHeaderSettings.getInstance();
        settings.setHighlightingEnabled(originalEnabled);
        settings.setHighlightedHeaders(originalHighlighted);
        settings.setNameOnlyHeaders(originalNameOnly);
        configurable.disposeUIResources();
        super.tearDown();
    }

    private JCheckBox findCheckBox() {
        var root = configurable.createComponent();
        assertNotNull(root);
        for (var child : root.getComponents()) {
            if (child instanceof JCheckBox box) {
                return box;
            }
        }
        throw new AssertionError("expected a JCheckBox in the configurable root component");
    }

    public void testDisplayNameIsMailKit() {
        assertEquals("MailKit", configurable.getDisplayName());
    }

    public void testCreateComponentReturnsNonNullRoot() {
        var component = configurable.createComponent();
        assertNotNull(component);
    }

    public void testFreshUiIsNotModified() {
        configurable.createComponent();
        assertFalse(configurable.isModified());
    }

    public void testTogglingCheckboxFlipsModified() {
        var checkbox = findCheckBox();
        assertFalse(configurable.isModified());
        checkbox.setSelected(!checkbox.isSelected());
        assertTrue(configurable.isModified());
    }

    public void testApplyPersistsCheckboxState() throws Exception {
        var checkbox = findCheckBox();
        checkbox.setSelected(false);
        configurable.apply();
        assertFalse(EmlHeaderSettings.getInstance().isHighlightingEnabled());
    }

    public void testResetRestoresCheckboxFromSettings() {
        var checkbox = findCheckBox();
        checkbox.setSelected(false);
        configurable.reset();
        assertTrue(checkbox.isSelected());
        assertFalse(configurable.isModified());
    }

    public void testApplyTriggersSettingsListUpdate() throws Exception {
        configurable.createComponent();
        // Mutate settings underneath and re-apply via the UI's current view; should overwrite.
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To", "X-Extra"));
        configurable.apply();
        // After apply, persisted list matches the UI view, not the underlying mutation.
        assertEquals(List.of("From", "To"), EmlHeaderSettings.getInstance().getHighlightedHeaders());
    }

    public void testApplyInvokesDaemonRestarter() throws Exception {
        var reasons = new java.util.ArrayList<String>();
        var withSpy = new EmlHeaderSettingsConfigurable(reasons::add);
        try {
            withSpy.createComponent();
            withSpy.apply();
            assertEquals(List.of("EML settings changed"), reasons);
        } finally {
            withSpy.disposeUIResources();
        }
    }
}
