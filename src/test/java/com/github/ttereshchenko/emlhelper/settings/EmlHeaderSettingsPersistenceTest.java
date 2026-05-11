package com.github.ttereshchenko.emlhelper.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.ArrayList;
import java.util.List;
import org.jdom.Element;

public class EmlHeaderSettingsPersistenceTest extends BasePlatformTestCase {

    public void testCopyBeanRoundTripPreservesAllFields() {
        var source = new EmlHeaderSettings.State();
        source.highlightingEnabled = false;
        source.highlightedHeaders = new ArrayList<>(List.of("From", "X-Custom"));
        source.nameOnlyHeaders = new ArrayList<>(List.of("From"));

        var target = new EmlHeaderSettings.State();
        XmlSerializerUtil.copyBean(source, target);

        assertFalse(target.highlightingEnabled);
        assertEquals(List.of("From", "X-Custom"), target.highlightedHeaders);
        assertEquals(List.of("From"), target.nameOnlyHeaders);
    }

    public void testXmlSerializeDeserializeRoundTrip() {
        var source = new EmlHeaderSettings.State();
        source.highlightingEnabled = false;
        source.highlightedHeaders = new ArrayList<>(List.of("From", "Subject", "X-Tracking"));
        source.nameOnlyHeaders = new ArrayList<>(List.of("Subject"));

        Element serialized = XmlSerializer.serialize(source);
        var restored = XmlSerializer.deserialize(serialized, EmlHeaderSettings.State.class);

        assertFalse(restored.highlightingEnabled);
        assertEquals(source.highlightedHeaders, restored.highlightedHeaders);
        assertEquals(source.nameOnlyHeaders, restored.nameOnlyHeaders);
    }

    public void testLoadStateRebuildsLookupCache() {
        var settings = new EmlHeaderSettings();
        var state = new EmlHeaderSettings.State();
        state.highlightingEnabled = true;
        state.highlightedHeaders = new ArrayList<>(List.of("X-Custom"));
        state.nameOnlyHeaders = new ArrayList<>(List.of("X-Custom"));

        settings.loadState(state);

        // Case-insensitive lookup must hit the freshly-rebuilt set.
        assertTrue(settings.isHighlighted("x-CUSTOM"));
        assertTrue(settings.isNameOnly("X-custom"));
        // Defaults removed by loadState must no longer match.
        assertFalse(settings.isHighlighted("From"));
        assertFalse(settings.isNameOnly("From"));
    }

    public void testApplicationServiceReturnsSingleton() {
        var first = EmlHeaderSettings.getInstance();
        var second = EmlHeaderSettings.getInstance();
        assertSame(first, second);
    }
}
