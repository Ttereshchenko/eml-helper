package com.github.ttereshchenko.emlhelper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmlHeaderSettingsStateTest {

    private EmlHeaderSettings settings;

    @BeforeEach
    void setUp() {
        settings = new EmlHeaderSettings();
    }

    // ===== Positive Tests: MyState defaults =====

    @Test
    void testDefaultHighlightingEnabled() {
        assertTrue(settings.isHighlightingEnabled());
    }

    @Test
    void testDefaultHighlightedHeaders() {
        List<String> headers = settings.getHighlightedHeaders();
        assertEquals(6, headers.size());
        assertTrue(headers.contains("From"));
        assertTrue(headers.contains("To"));
        assertTrue(headers.contains("Subject"));
        assertTrue(headers.contains("Date"));
        assertTrue(headers.contains("Cc"));
        assertTrue(headers.contains("Bcc"));
    }

    @Test
    void testDefaultNameOnlyHeaders() {
        List<String> headers = settings.getNameOnlyHeaders();
        assertEquals(6, headers.size());
        assertTrue(headers.contains("From"));
        assertTrue(headers.contains("To"));
        assertTrue(headers.contains("Subject"));
        assertTrue(headers.contains("Date"));
        assertTrue(headers.contains("Cc"));
        assertTrue(headers.contains("Bcc"));
    }

    // ===== Positive Tests: Logic methods =====

    @Test
    void testIsHighlightedCaseInsensitive() {
        assertTrue(settings.isHighlighted("from"));
        assertTrue(settings.isHighlighted("FROM"));
        assertTrue(settings.isHighlighted("fRoM"));
        assertTrue(settings.isHighlighted("From"));
    }

    @Test
    void testIsNameOnlyCaseInsensitive() {
        assertTrue(settings.isNameOnly("from"));
        assertTrue(settings.isNameOnly("FROM"));
        assertTrue(settings.isNameOnly("fRoM"));
        assertTrue(settings.isNameOnly("From"));
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
    void testLoadState() {
        EmlHeaderSettings.MyState newState = new EmlHeaderSettings.MyState();
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

        EmlHeaderSettings.MyState state = settings.getState();
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
