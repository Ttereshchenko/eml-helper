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

    public void testDisplayNameIsMailKit() {
        assertEquals("MailKit", page.getDisplayName());
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
        assertContainsElements(names, "Boundary", "Headers//From", "Headers//Custom".replace("Custom", "X-Custom"));
    }

    public void testAttributeDescriptorsDoNotDuplicatePredefinedAsCustom() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To", "Subject"));
        var descriptors = page.getAttributeDescriptors();
        var customLikeFrom = Arrays.stream(descriptors)
                .filter(descriptor -> descriptor.getDisplayName().equals("Headers//From"))
                .count();
        assertEquals(1, customLikeFrom);
    }

    public void testColorDescriptorsIsEmptyArray() {
        assertSame(ColorDescriptor.EMPTY_ARRAY, page.getColorDescriptors());
    }

    public void testDemoTextIncludesCustomHeaderSampleLine() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "X-Custom"));
        var demo = page.getDemoText();
        assertTrue("expected <x-custom> tag in demo:\n" + demo, demo.contains("<x-custom>"));
        assertTrue("expected </x-custom> closing tag in demo:\n" + demo, demo.contains("</x-custom>"));
        assertTrue("expected sample line to mention X-Custom:\n" + demo, demo.contains("X-Custom:"));
    }

    public void testDemoTextOmitsPredefinedAsCustom() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To", "Subject", "Date", "Cc", "Bcc"));
        var demo = page.getDemoText();
        assertEquals(1, countOccurrences(demo, "<from>"));
        assertEquals(1, countOccurrences(demo, "<subject>"));
    }

    public void testDemoTextDeduplicatesCustomHeaders() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("X-Custom", "x-custom"));
        var demo = page.getDemoText();
        assertEquals(1, countOccurrences(demo, "<x-custom>"));
    }

    public void testDemoTextOmitsCustomLinesWhenNoneConfigured() {
        EmlHeaderSettings.getInstance().setHighlightedHeaders(List.of("From", "To"));
        var demo = page.getDemoText();
        assertFalse("no <x-...> tags expected when no custom headers configured:\n" + demo, demo.contains("<x-"));
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        var index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
