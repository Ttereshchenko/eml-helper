package com.github.ttereshchenko.mailkit.smtp.esmtp;

import com.github.ttereshchenko.mailkit.smtp.SmtpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a multi-line EHLO response into a {@code keyword -> arguments} map. The first line of an
 * EHLO response is the server's greeting (e.g. {@code "smtp.example.com Hello"}) — it is dropped.
 * Keywords are upper-cased so callers can interrogate them without worrying about case.
 *
 * <p>Examples of advertisements this parser handles:
 *
 * <pre>
 *   250-smtp.example.com Hello
 *   250-AUTH PLAIN LOGIN XOAUTH2
 *   250-SIZE 52428800
 *   250-STARTTLS
 *   250-PIPELINING
 *   250-8BITMIME
 *   250-SMTPUTF8
 *   250-CHUNKING
 *   250 PRDR
 * </pre>
 */
public final class EhloResponseParser {

    private EhloResponseParser() {}

    public static Map<String, List<String>> parse(SmtpResponse response) {
        var result = new LinkedHashMap<String, List<String>>();
        var iterator = response.lines().listIterator();
        if (iterator.hasNext()) {
            iterator.next();
        }
        while (iterator.hasNext()) {
            var line = iterator.next().trim();
            if (line.isEmpty()) {
                continue;
            }
            var parts = line.split("\\s+");
            var keyword = parts[0].toUpperCase(Locale.ROOT);
            var arguments = new ArrayList<String>(parts.length - 1);
            for (var index = 1; index < parts.length; index++) {
                arguments.add(parts[index]);
            }
            result.put(keyword, arguments);
        }
        return result;
    }
}
