package com.github.ttereshchenko.mailkit.smtp.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Emits the v1 ASCII PROXY line, e.g. {@code PROXY TCP4 1.2.3.4 5.6.7.8 12345 25\r\n}. The format
 * is defined in the HAProxy PROXY protocol spec, §2.1.
 */
public final class ProxyV1Writer {

    private ProxyV1Writer() {}

    public static byte[] format(ProxyConfig config) {
        var family =
                switch (config.family()) {
                    case TCP4 -> "TCP4";
                    case TCP6 -> "TCP6";
                };
        var line = "PROXY " + family + " "
                + config.sourceAddress() + " " + config.destAddress() + " "
                + config.sourcePort() + " " + config.destPort() + "\r\n";
        return line.getBytes(StandardCharsets.US_ASCII);
    }
}
