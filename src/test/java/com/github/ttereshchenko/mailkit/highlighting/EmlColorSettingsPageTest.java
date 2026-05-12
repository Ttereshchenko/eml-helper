package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;
import java.util.List;

public class EmlColorSettingsPageTest extends BasePlatformTestCase {

    private EmlColorSettingsPage page;
    private List<String> originalHighlighted;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        page = new EmlColorSettingsPage();
        originalHighlighted = List.copyOf(EmlHeaderSettings.getInstance().getHighlightedHeaders());
    }

    @Override
    protected void tearDown() throws Exception {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(originalHighlighted);
        super.tearDown();
    }

    public void testDisplayNameIsEml() {
        assertEquals("EML", page.getDisplayName());
    }

    public void testIconIsNull() {
        assertNull(page.getIcon());
    }

    public void testHighlighterIsEmlSyntaxHighlighter() {
        assertInstanceOf(page.getHighlighter(), EmlSyntaxHighlighter.class);
    }

    public void testDemoTextContainsHeaderAndBoundaryMarkup() {
        var demoText = page.getDemoText();
        assertFalse(demoText.isBlank());
        assertTrue(demoText.contains("<from>"));
        assertTrue(demoText.contains("<subject>"));
        assertTrue(demoText.contains("boundary"));
    }

    public void testTagMapContainsAllPredefinedHeaders() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
        var map = page.getAdditionalHighlightingTagToDescriptorMap();
        assertNotNull(map);
        assertSame(EmlHeaderTextAttributeKeys.HEADER_FROM, map.get("from"));
        assertSame(EmlHeaderTextAttributeKeys.HEADER_TO, map.get("to"));
        assertSame(EmlHeaderTextAttributeKeys.HEADER_SUBJECT, map.get("subject"));
        assertSame(EmlHeaderTextAttributeKeys.HEADER_DATE, map.get("date"));
        assertSame(EmlHeaderTextAttributeKeys.HEADER_CC, map.get("cc"));
        assertSame(EmlHeaderTextAttributeKeys.HEADER_BCC, map.get("bcc"));
    }

    public void testTagMapIncludesCustomHeaders() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "X-Tracking-Id"));
        var map = page.getAdditionalHighlightingTagToDescriptorMap();
        assertNotNull(map);
        assertNotNull(map.get("x-tracking-id"));
    }

    public void testAttributeDescriptorsIncludePredefinedAndCustom() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "X-Custom"));
        var descriptors = page.getAttributeDescriptors();
        var names = Arrays.stream(descriptors)
                .map(descriptor -> descriptor.getDisplayName())
                .toList();
        assertContainsElements(
                names, "Header", "Boundary", "Header//From", "Header//Custom".replace("Custom", "X-Custom"));
    }

    public void testAttributeDescriptorsDoNotDuplicatePredefinedAsCustom() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To", "Subject"));
        var descriptors = page.getAttributeDescriptors();
        var customLikeFrom = Arrays.stream(descriptors)
                .filter(descriptor -> descriptor.getDisplayName().equals("Header//From"))
                .count();
        assertEquals(1, customLikeFrom);
    }

    public void testColorDescriptorsIsEmptyArray() {
        assertSame(ColorDescriptor.EMPTY_ARRAY, page.getColorDescriptors());
    }
}
