package com.github.ttereshchenko.mailkit.smtp.xclient;

import com.github.ttereshchenko.mailkit.smtp.Xtext;

/**
 * Serialises an {@link XclientConfig} into the wire command. Empty unset fields are dropped so
 * the line is short enough for parser-strict servers, and attribute values are xtext-encoded as
 * Postfix expects (XCLIENT_README), so spaces or control bytes cannot break the attribute list.
 * When {@code rawCommand} is set it is used verbatim, allowing callers to emit attributes the
 * standard formatter does not know about.
 */
public final class XclientCommandBuilder {

    private XclientCommandBuilder() {}

    public static String build(XclientConfig config) {
        if (config.rawCommand() != null) {
            return "XCLIENT " + config.rawCommand().trim();
        }
        var builder = new StringBuilder("XCLIENT");
        appendAttribute(builder, "NAME", config.name());
        appendAttribute(builder, "ADDR", config.addr());
        appendAttribute(builder, "PORT", config.port());
        appendAttribute(builder, "PROTO", config.proto());
        appendAttribute(builder, "HELO", config.helo());
        appendAttribute(builder, "LOGIN", config.login());
        appendAttribute(builder, "DESTADDR", config.destAddr());
        appendAttribute(builder, "DESTPORT", config.destPort());
        appendAttribute(builder, "REVERSE_NAME", config.reverseName());
        for (var entry : config.extra().entrySet()) {
            appendAttribute(builder, entry.getKey(), entry.getValue());
        }
        return builder.toString();
    }

    private static void appendAttribute(StringBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }
        var text = value.toString();
        if (text.isBlank()) {
            return;
        }
        builder.append(' ').append(key).append('=').append(Xtext.encode(text));
    }
}
