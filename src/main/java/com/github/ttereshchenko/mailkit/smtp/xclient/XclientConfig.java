package com.github.ttereshchenko.mailkit.smtp.xclient;

import java.util.Map;

/**
 * Postfix XCLIENT attribute set (https://www.postfix.org/XCLIENT_README.html). Sent after the
 * first EHLO when the server advertises {@code XCLIENT}, optionally before STARTTLS for setups
 * that authorise an upstream relay's identity before TLS is established.
 *
 * <p>{@code rawCommand} is an escape hatch — when set, the formatter emits it verbatim after
 * "XCLIENT " and ignores all other fields. Use for vendor extensions Postfix has not formalised.
 */
public record XclientConfig(
        String addr,
        String name,
        Integer port,
        String proto,
        String helo,
        String login,
        String destAddr,
        Integer destPort,
        String reverseName,
        Map<String, String> extra,
        String rawCommand,
        boolean beforeStartTls,
        boolean optional) {

    public XclientConfig {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static XclientConfig disabled() {
        return new XclientConfig(null, null, null, null, null, null, null, null, null, Map.of(), null, false, true);
    }

    public boolean isEnabled() {
        return rawCommand != null
                || addr != null
                || name != null
                || port != null
                || proto != null
                || helo != null
                || login != null
                || destAddr != null
                || destPort != null
                || reverseName != null
                || !extra.isEmpty();
    }

    public XclientConfig withAddr(String value) {
        return new XclientConfig(
                value,
                name,
                port,
                proto,
                helo,
                login,
                destAddr,
                destPort,
                reverseName,
                extra,
                rawCommand,
                beforeStartTls,
                optional);
    }

    public XclientConfig withName(String value) {
        return new XclientConfig(
                addr,
                value,
                port,
                proto,
                helo,
                login,
                destAddr,
                destPort,
                reverseName,
                extra,
                rawCommand,
                beforeStartTls,
                optional);
    }

    public XclientConfig withBeforeStartTls(boolean value) {
        return new XclientConfig(
                addr,
                name,
                port,
                proto,
                helo,
                login,
                destAddr,
                destPort,
                reverseName,
                extra,
                rawCommand,
                value,
                optional);
    }
}
