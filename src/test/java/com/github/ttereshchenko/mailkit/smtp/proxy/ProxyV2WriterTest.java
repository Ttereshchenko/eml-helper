package com.github.ttereshchenko.mailkit.smtp.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ProxyV2WriterTest {

    @Test
    void v2Tcp4HeaderBytesMatchHaproxySpec() {
        var config = ProxyConfig.v2Tcp4("198.51.100.7", 56324, "203.0.113.5", 25);
        var bytes = ProxyV2Writer.format(config);

        // Layout (HAProxy §2.2):
        //  12 bytes signature: 0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A
        //   1 byte  version|command: 0x21 (v2=0x20, PROXY=0x01)
        //   1 byte  family|protocol: 0x11 (AF_INET|STREAM)
        //   2 bytes payload length: 0x000C (12 bytes — 4+4+2+2)
        //   4 bytes source IP:  198.51.100.7  -> C6 33 64 07
        //   4 bytes dest IP:    203.0.113.5   -> CB 00 71 05
        //   2 bytes source port: 56324 -> 0xDC 0x04
        //   2 bytes dest port:   25    -> 0x00 0x19
        var expectedHex = "0D0A0D0A000D0A515549540A2111000CC6336407CB007105DC040019";
        assertEquals(expectedHex, HexFormat.of().withUpperCase().formatHex(bytes));
    }

    @Test
    void v2LocalCommandSetsLowerNibbleToZero() {
        var config = new ProxyConfig(
                ProxyConfig.Version.V2, ProxyConfig.Command.LOCAL, ProxyConfig.Family.TCP4, "0.0.0.0", 0, "0.0.0.0", 0);
        var bytes = ProxyV2Writer.format(config);

        // 13th byte (index 12) is the version|command byte; LOCAL produces 0x20.
        assertEquals(0x20, bytes[12] & 0xFF);
    }
}
