package com.github.ttereshchenko.mailkit.smtp.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProxyV1WriterTest {

    @Test
    void v1Tcp4LineMatchesHaproxySpec() {
        var config = ProxyConfig.v1Tcp4("198.51.100.7", 56324, "203.0.113.5", 25);
        var bytes = ProxyV1Writer.format(config);

        assertArrayEquals(
                "PROXY TCP4 198.51.100.7 203.0.113.5 56324 25\r\n".getBytes(StandardCharsets.US_ASCII), bytes);
    }

    @Test
    void v1Tcp6LineUsesTcp6Token() {
        var config = new ProxyConfig(
                ProxyConfig.Version.V1,
                ProxyConfig.Command.PROXY,
                ProxyConfig.Family.TCP6,
                "2001:db8::1",
                4444,
                "2001:db8::2",
                25);
        var bytes = ProxyV1Writer.format(config);

        assertArrayEquals("PROXY TCP6 2001:db8::1 2001:db8::2 4444 25\r\n".getBytes(StandardCharsets.US_ASCII), bytes);
    }
}
