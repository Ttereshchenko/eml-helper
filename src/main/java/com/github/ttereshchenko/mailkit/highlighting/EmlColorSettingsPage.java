package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlColorSettingsPage implements ColorSettingsPage {

    private static final Set<String> PREDEFINED_UPPER = Set.of("FROM", "TO", "SUBJECT", "DATE", "CC", "BCC");

    private static final String DEMO_HEADERS = """
            <from>From: sender@example.com</from>
            <to>To: recipient@example.com,</to>
            <to> another@example.com</to>
            <subject>Subject: Test email</subject>
            <date>Date: Tue, 17 Mar 2026 10:00:00 +0000</date>
            <cc>Cc: someone@example.com</cc>
            <bcc>Bcc: hidden@example.com</bcc>
            """;

    private static final String DEMO_BODY = """
            Content-Type: multipart/mixed; boundary="abc123"

            Hello, this is the body.

            --abc123
            Content-Type: text/plain

            Plain text part.
            --abc123--
            """;

    @Override
    public @Nullable Icon getIcon() {
        return null;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new EmlSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        var builder = new StringBuilder(DEMO_HEADERS);
        var seen = new HashSet<String>();
        for (String header : EmlHeaderSettings.getInstance().getHighlightedHeaders()) {
            var upper = header.toUpperCase(Locale.ROOT);
            if (PREDEFINED_UPPER.contains(upper) || !seen.add(upper)) {
                continue;
            }
            var tag = header.toLowerCase(Locale.ROOT);
            builder.append('<')
                    .append(tag)
                    .append('>')
                    .append(header)
                    .append(": sample value")
                    .append("</")
                    .append(tag)
                    .append(">\n");
        }
        builder.append(DEMO_BODY);
        return builder.toString();
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        var map = new HashMap<String, TextAttributesKey>();
        map.put("from", EmlHeaderTextAttributeKeys.HEADER_FROM);
        map.put("to", EmlHeaderTextAttributeKeys.HEADER_TO);
        map.put("subject", EmlHeaderTextAttributeKeys.HEADER_SUBJECT);
        map.put("date", EmlHeaderTextAttributeKeys.HEADER_DATE);
        map.put("cc", EmlHeaderTextAttributeKeys.HEADER_CC);
        map.put("bcc", EmlHeaderTextAttributeKeys.HEADER_BCC);

        // Add dynamic entries for user-configured headers
        for (String header : EmlHeaderSettings.getInstance().getHighlightedHeaders()) {
            String tag = header.toLowerCase(Locale.ROOT);
            if (!map.containsKey(tag)) {
                map.put(tag, EmlHeaderTextAttributeKeys.getKey(header));
            }
        }
        return map;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        var descriptors = new ArrayList<AttributesDescriptor>();
        descriptors.add(new AttributesDescriptor("Boundary", EmlSyntaxHighlighter.BOUNDARY_KEY));
        descriptors.add(new AttributesDescriptor("Headers//From", EmlHeaderTextAttributeKeys.HEADER_FROM));
        descriptors.add(new AttributesDescriptor("Headers//To", EmlHeaderTextAttributeKeys.HEADER_TO));
        descriptors.add(new AttributesDescriptor("Headers//Subject", EmlHeaderTextAttributeKeys.HEADER_SUBJECT));
        descriptors.add(new AttributesDescriptor("Headers//Date", EmlHeaderTextAttributeKeys.HEADER_DATE));
        descriptors.add(new AttributesDescriptor("Headers//Cc", EmlHeaderTextAttributeKeys.HEADER_CC));
        descriptors.add(new AttributesDescriptor("Headers//Bcc", EmlHeaderTextAttributeKeys.HEADER_BCC));

        for (String header : EmlHeaderSettings.getInstance().getHighlightedHeaders()) {
            if (PREDEFINED_UPPER.contains(header.toUpperCase(Locale.ROOT))) {
                continue;
            }
            descriptors.add(new AttributesDescriptor("Headers//" + header, EmlHeaderTextAttributeKeys.getKey(header)));
        }
        return descriptors.toArray(AttributesDescriptor[]::new);
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "MailKit";
    }
}
