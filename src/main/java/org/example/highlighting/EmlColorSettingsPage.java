package org.example.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.example.settings.EmlHeaderSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EmlColorSettingsPage implements ColorSettingsPage {
    private static final String DEMO_TEXT = """
            <from>From: sender@example.com</from>
            <to>To: recipient@example.com,</to>
            <to> another@example.com</to>
            <subject>Subject: Test email</subject>
            <date>Date: Tue, 17 Mar 2026 10:00:00 +0000</date>
            <cc>Cc: someone@example.com</cc>
            <bcc>Bcc: hidden@example.com</bcc>
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
        return DEMO_TEXT;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        Map<String, TextAttributesKey> map = new HashMap<>();
        map.put("from", EmlHeaderTextAttributeKeys.HEADER_FROM);
        map.put("to", EmlHeaderTextAttributeKeys.HEADER_TO);
        map.put("subject", EmlHeaderTextAttributeKeys.HEADER_SUBJECT);
        map.put("date", EmlHeaderTextAttributeKeys.HEADER_DATE);
        map.put("cc", EmlHeaderTextAttributeKeys.HEADER_CC);
        map.put("bcc", EmlHeaderTextAttributeKeys.HEADER_BCC);

        // Add dynamic entries for user-configured headers
        for (String header : EmlHeaderSettings.getInstance().getHighlightedHeaders()) {
            String tag = header.toLowerCase();
            if (!map.containsKey(tag)) {
                map.put(tag, EmlHeaderTextAttributeKeys.getKey(header));
            }
        }
        return map;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        List<AttributesDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new AttributesDescriptor("Header", EmlSyntaxHighlighter.HEADER_KEY));
        descriptors.add(new AttributesDescriptor("Boundary", EmlSyntaxHighlighter.BOUNDARY_KEY));
        descriptors.add(new AttributesDescriptor("Header//From", EmlHeaderTextAttributeKeys.HEADER_FROM));
        descriptors.add(new AttributesDescriptor("Header//To", EmlHeaderTextAttributeKeys.HEADER_TO));
        descriptors.add(new AttributesDescriptor("Header//Subject", EmlHeaderTextAttributeKeys.HEADER_SUBJECT));
        descriptors.add(new AttributesDescriptor("Header//Date", EmlHeaderTextAttributeKeys.HEADER_DATE));
        descriptors.add(new AttributesDescriptor("Header//Cc", EmlHeaderTextAttributeKeys.HEADER_CC));
        descriptors.add(new AttributesDescriptor("Header//Bcc", EmlHeaderTextAttributeKeys.HEADER_BCC));

        // Add descriptors for user-configured headers beyond the defaults
        for (String header : EmlHeaderSettings.getInstance().getHighlightedHeaders()) {
            String upper = header.toUpperCase();
            if (!upper.equals("FROM") && !upper.equals("TO") && !upper.equals("SUBJECT")
                    && !upper.equals("DATE") && !upper.equals("CC") && !upper.equals("BCC")) {
                descriptors.add(new AttributesDescriptor(
                        "Header//" + header, EmlHeaderTextAttributeKeys.getKey(header)));
            }
        }
        return descriptors.toArray(AttributesDescriptor[]::new);
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "EML";
    }
}
