package com.github.ttereshchenko.mailkit.conversion;

import java.util.regex.Pattern;

/**
 * Rewrites the in-document charset declaration of an HTML body that is being re-encoded as UTF-8.
 *
 * <p>The MSG/PST converters decode an HTML body from its original codepage (e.g. windows-1251) and
 * emit it as {@code text/html; charset=UTF-8}. A surviving {@code <meta charset="windows-1251">} or
 * {@code <meta http-equiv="Content-Type" content="text/html; charset=windows-1251">} then
 * contradicts the MIME header, and clients that honor the in-document declaration (HTML5 §4.2.5
 * lets it override) mojibake the part. Both declaration forms are rewritten to {@code UTF-8}.
 */
public final class HtmlMetaCharset {

    // Keys on the charset= attribute/parameter: group 1 is everything up to the value, group 2 the
    // charset token itself (replaced with UTF-8).
    private static final Pattern META_CHARSET =
            Pattern.compile("(?i)(<meta\\s[^>]*?charset\\s*=\\s*[\"']?)([^\"'\\s/>]+)");

    private HtmlMetaCharset() {}

    /**
     * Replaces the charset token of every {@code <meta ... charset=...>} declaration with
     * {@code UTF-8}. Covers both the HTML5 {@code <meta charset=...>} form and the legacy
     * {@code <meta http-equiv="Content-Type" content="...; charset=...">} form (the pattern keys on
     * the {@code charset=} attribute/parameter, which both share). HTML without a declaration is
     * returned unchanged.
     */
    public static String rewriteToUtf8(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        var matcher = META_CHARSET.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        return matcher.replaceAll("$1UTF-8");
    }
}
