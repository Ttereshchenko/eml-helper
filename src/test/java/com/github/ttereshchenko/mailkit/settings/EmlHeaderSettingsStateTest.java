package com.github.ttereshchenko.mailkit.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmlHeaderSettingsStateTest {

    private EmlHeaderSettings settings;

    @BeforeEach
    void setUp() {
        settings = new EmlHeaderSettings();
    }

    // ===== Positive Tests: State defaults =====

    @Test
    void testDefaultHighlightingEnabled() {
        assertTrue(settings.isHighlightingEnabled());
    }

    @Test
    void testDefaultHighlightedHeadersHasSixEntries() {
        assertEquals(6, settings.getHighlightedHeaders().size());
    }

    @Test
    void testDefaultNameOnlyHeadersHasSixEntries() {
        assertEquals(6, settings.getNameOnlyHeaders().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "To", "Subject", "Date", "Cc", "Bcc"})
    void defaultHighlightedHeadersContainsEachStandardHeader(String header) {
        assertTrue(settings.getHighlightedHeaders().contains(header));
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "To", "Subject", "Date", "Cc", "Bcc"})
    void defaultNameOnlyHeadersContainsEachStandardHeader(String header) {
        assertTrue(settings.getNameOnlyHeaders().contains(header));
    }

    // ===== Positive Tests: Logic methods =====

    @ParameterizedTest
    @ValueSource(strings = {"from", "FROM", "fRoM", "From"})
    void isHighlightedIsCaseInsensitive(String variant) {
        assertTrue(settings.isHighlighted(variant));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "FROM", "fRoM", "From"})
    void isNameOnlyIsCaseInsensitive(String variant) {
        assertTrue(settings.isNameOnly(variant));
    }

    @Test
    void testSetHighlightedHeaders() {
        settings.setHighlightedHeaders(List.of("From", "X-Custom"));
        assertTrue(settings.isHighlighted("From"));
        assertTrue(settings.isHighlighted("X-Custom"));
        assertFalse(settings.isHighlighted("To"));
        assertFalse(settings.isHighlighted("Subject"));
    }

    @Test
    void testSetNameOnlyHeaders() {
        settings.setNameOnlyHeaders(List.of("From"));
        assertTrue(settings.isNameOnly("From"));
        assertFalse(settings.isNameOnly("To"));
        assertFalse(settings.isNameOnly("Subject"));
    }

    @Test
    void testSetHighlightingEnabled() {
        settings.setHighlightingEnabled(false);
        assertFalse(settings.isHighlightingEnabled());
        settings.setHighlightingEnabled(true);
        assertTrue(settings.isHighlightingEnabled());
    }

    @Test
    void testDefaultShowCompareEditorToolbarButton() {
        assertTrue(settings.isShowCompareEditorToolbarButton());
    }

    @Test
    void testSetShowCompareEditorToolbarButton() {
        settings.setShowCompareEditorToolbarButton(false);
        assertFalse(settings.isShowCompareEditorToolbarButton());
        settings.setShowCompareEditorToolbarButton(true);
        assertTrue(settings.isShowCompareEditorToolbarButton());
    }

    @Test
    void testLoadState() {
        EmlHeaderSettings.State newState = new EmlHeaderSettings.State();
        newState.highlightingEnabled = false;
        newState.highlightedHeaders = new ArrayList<>(List.of("X-Test"));
        newState.nameOnlyHeaders = new ArrayList<>(List.of("X-Test"));

        settings.loadState(newState);

        assertFalse(settings.isHighlightingEnabled());
        assertEquals(1, settings.getHighlightedHeaders().size());
        assertTrue(settings.isHighlighted("X-Test"));
        assertFalse(settings.isHighlighted("From"));
    }

    @Test
    void testGetStateReturnsCurrentState() {
        settings.setHighlightingEnabled(false);
        settings.setHighlightedHeaders(List.of("X-Only"));

        EmlHeaderSettings.State state = settings.getState();
        assertFalse(state.highlightingEnabled);
        assertEquals(List.of("X-Only"), state.highlightedHeaders);
    }

    // ===== Negative Tests =====

    @Test
    void testIsHighlightedUnknownHeader() {
        assertFalse(settings.isHighlighted("X-Unknown"));
    }

    @Test
    void testIsNameOnlyUnknownHeader() {
        assertFalse(settings.isNameOnly("X-Unknown"));
    }

    @Test
    void testSetHighlightedHeadersDefensiveCopy() {
        ArrayList<String> original = new ArrayList<>(List.of("From", "To"));
        settings.setHighlightedHeaders(original);

        // Mutate the original list
        original.add("X-Injected");

        // Settings should not be affected
        assertFalse(settings.isHighlighted("X-Injected"));
        assertEquals(2, settings.getHighlightedHeaders().size());
    }
}
