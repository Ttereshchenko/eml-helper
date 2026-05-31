package com.github.ttereshchenko.mailkit.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.awt.Container;
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
        var found = findCheckBoxByLabel(root, "Enable highlighting");
        if (found == null) {
            throw new AssertionError("expected an 'Enable highlighting' JCheckBox in the configurable root");
        }
        return found;
    }

    private JCheckBox findCheckBoxByLabel(Container container, String label) {
        for (var child : container.getComponents()) {
            if (child instanceof JCheckBox box && label.equals(box.getText())) {
                return box;
            }
            if (child instanceof Container nested) {
                var hit = findCheckBoxByLabel(nested, label);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    public void testDisplayNameIsGeneral() {
        assertEquals("General", configurable.getDisplayName());
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
        var withSpy = new EmlHeaderSettingsConfigurable(reasons::add, component -> {});
        try {
            withSpy.createComponent();
            withSpy.apply();
            assertEquals(List.of("EML settings changed"), reasons);
        } finally {
            withSpy.disposeUIResources();
        }
    }

    public void testApplyInvokesColorSchemeRefresher() throws Exception {
        var refreshedComponents = new java.util.ArrayList<javax.swing.JComponent>();
        ColorSchemePageRefresher refresher = component -> refreshedComponents.add(component);
        var withSpy = new EmlHeaderSettingsConfigurable(reason -> {}, refresher);
        try {
            var root = withSpy.createComponent();
            withSpy.apply();
            assertEquals(1, refreshedComponents.size());
            assertSame(root, refreshedComponents.get(0));
        } finally {
            withSpy.disposeUIResources();
        }
    }

    public void testAddHeaderAcceptsCustomHeaderName() throws Exception {
        var stubPrompter = new RecordingPrompter("CUSTOM-HEADER");
        var withStub = new EmlHeaderSettingsConfigurable(DaemonRestarter.DEFAULT, stubPrompter);
        try {
            withStub.createComponent();
            withStub.addHeader();
            withStub.apply();
            assertTrue(
                    "expected CUSTOM-HEADER to be persisted, got "
                            + EmlHeaderSettings.getInstance().getHighlightedHeaders(),
                    EmlHeaderSettings.getInstance().getHighlightedHeaders().contains("CUSTOM-HEADER"));
            assertEquals(1, stubPrompter.calls);
        } finally {
            withStub.disposeUIResources();
        }
    }

    public void testAddHeaderPassesSuggestionsListToPrompter() {
        var stubPrompter = new RecordingPrompter(null);
        var withStub = new EmlHeaderSettingsConfigurable(DaemonRestarter.DEFAULT, stubPrompter);
        try {
            withStub.createComponent();
            withStub.addHeader();
            assertNotNull(stubPrompter.lastSuggestions);
            assertTrue("suggestions should include From", stubPrompter.lastSuggestions.contains("From"));
            assertTrue(
                    "suggestions should include Content-Type", stubPrompter.lastSuggestions.contains("Content-Type"));
        } finally {
            withStub.disposeUIResources();
        }
    }

    public void testHeaderNameCellIsEditable() {
        configurable.createComponent();
        var model = configurable.getTableModel();
        assertTrue("Header Name column should be editable", model.isCellEditable(0, 0));
    }

    public void testRenamingHeaderUpdatesEntry() throws Exception {
        configurable.createComponent();
        var model = configurable.getTableModel();
        model.setValueAt("Renamed", 0, 0);
        configurable.apply();
        var persisted = EmlHeaderSettings.getInstance().getHighlightedHeaders();
        assertEquals(List.of("Renamed", "To"), persisted);
    }

    public void testRenameToBlankIsIgnored() {
        configurable.createComponent();
        var model = configurable.getTableModel();
        var before = model.getValueAt(0, 0);
        model.setValueAt("   ", 0, 0);
        assertEquals(before, model.getValueAt(0, 0));
    }

    public void testAddHeaderIgnoresNullFromPrompter() {
        var stubPrompter = new RecordingPrompter(null);
        var withStub = new EmlHeaderSettingsConfigurable(DaemonRestarter.DEFAULT, stubPrompter);
        try {
            withStub.createComponent();
            var before = List.copyOf(EmlHeaderSettings.getInstance().getHighlightedHeaders());
            withStub.addHeader();
            assertEquals(before, List.copyOf(EmlHeaderSettings.getInstance().getHighlightedHeaders()));
        } finally {
            withStub.disposeUIResources();
        }
    }

    private static final class RecordingPrompter implements HeaderNamePrompter {
        private final String response;
        int calls;
        List<String> lastSuggestions;

        RecordingPrompter(String response) {
            this.response = response;
        }

        @Override
        public String prompt(java.awt.Component parent, List<String> suggestions) {
            calls++;
            lastSuggestions = suggestions;
            return response;
        }
    }
}
