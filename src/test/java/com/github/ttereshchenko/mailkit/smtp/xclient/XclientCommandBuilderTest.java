package com.github.ttereshchenko.mailkit.smtp.xclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class XclientCommandBuilderTest {

    @Test
    void emitsOnlyTheAttributesThatAreSet() {
        var config = XclientConfig.disabled().withAddr("198.51.100.7").withName("upstream.example.com");

        var line = XclientCommandBuilder.build(config);

        assertEquals("XCLIENT NAME=upstream.example.com ADDR=198.51.100.7", line);
    }

    @Test
    void rawCommandIsEmittedVerbatimAfterTheXclientKeyword() {
        var config = new XclientConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                "ADDR=10.0.0.1 EXTRA=opaque",
                false,
                false);

        var line = XclientCommandBuilder.build(config);

        assertEquals("XCLIENT ADDR=10.0.0.1 EXTRA=opaque", line);
    }

    @Test
    void allKnownAttributesAreEmittedInTheExpectedOrder() {
        var config = new XclientConfig(
                "10.0.0.1",
                "name.example.com",
                12345,
                "ESMTP",
                "ehlo.example.com",
                "alice",
                "10.0.0.2",
                25,
                "rev.example.com",
                java.util.Map.of("CUSTOM", "value"),
                null,
                false,
                false);

        var line = XclientCommandBuilder.build(config);

        assertEquals(
                "XCLIENT NAME=name.example.com ADDR=10.0.0.1 PORT=12345 PROTO=ESMTP HELO=ehlo.example.com "
                        + "LOGIN=alice DESTADDR=10.0.0.2 DESTPORT=25 REVERSE_NAME=rev.example.com CUSTOM=value",
                line);
    }
}
