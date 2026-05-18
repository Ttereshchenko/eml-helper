package com.github.ttereshchenko.mailkit.smtp.proxy;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Emits the v2 binary PROXY header (HAProxy spec §2.2).
 *
 * <p>Layout:
 *
 * <pre>
 *   12 bytes  signature  0x0D 0x0A 0x0D 0x0A 0x00 0x0D 0x0A 0x51 0x55 0x49 0x54 0x0A
 *   1  byte   version(4) | command(4)   0x20 | (PROXY=0x01 or LOCAL=0x00)
 *   1  byte   family(4)  | protocol(4)  TCP4 -> 0x11, TCP6 -> 0x21
 *   2  bytes  payload length (big-endian)
 *   payload: src-addr, dst-addr, src-port, dst-port (binary)
 * </pre>
 */
public final class ProxyV2Writer {

    private static final byte[] SIGNATURE = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    private ProxyV2Writer() {}

    public static byte[] format(ProxyConfig config) {
        var payload = encodePayload(config);
        var output = new ByteArrayOutputStream(SIGNATURE.length + 4 + payload.length);
        output.write(SIGNATURE, 0, SIGNATURE.length);
        output.write((byte) (0x20 | versionCommandNibble(config.command())));
        output.write((byte) familyProtocolByte(config.family()));
        output.write((byte) ((payload.length >>> 8) & 0xFF));
        output.write((byte) (payload.length & 0xFF));
        output.write(payload, 0, payload.length);
        return output.toByteArray();
    }

    private static int versionCommandNibble(ProxyConfig.Command command) {
        return switch (command) {
            case PROXY -> 0x01;
            case LOCAL -> 0x00;
        };
    }

    private static int familyProtocolByte(ProxyConfig.Family family) {
        // Upper nibble = family (1=AF_INET, 2=AF_INET6), lower nibble = protocol (1=TCP/STREAM).
        return switch (family) {
            case TCP4 -> 0x11;
            case TCP6 -> 0x21;
        };
    }

    private static byte[] encodePayload(ProxyConfig config) {
        var srcAddr = parseAddress(config.sourceAddress());
        var dstAddr = parseAddress(config.destAddress());
        var addressLength =
                switch (config.family()) {
                    case TCP4 -> 4;
                    case TCP6 -> 16;
                };
        if (srcAddr.length != addressLength || dstAddr.length != addressLength) {
            throw new IllegalArgumentException("address length mismatch for " + config.family() + ": src="
                    + srcAddr.length + ", dst=" + dstAddr.length);
        }
        var payload = new byte[addressLength * 2 + 4];
        System.arraycopy(srcAddr, 0, payload, 0, addressLength);
        System.arraycopy(dstAddr, 0, payload, addressLength, addressLength);
        var portOffset = addressLength * 2;
        payload[portOffset] = (byte) ((config.sourcePort() >>> 8) & 0xFF);
        payload[portOffset + 1] = (byte) (config.sourcePort() & 0xFF);
        payload[portOffset + 2] = (byte) ((config.destPort() >>> 8) & 0xFF);
        payload[portOffset + 3] = (byte) (config.destPort() & 0xFF);
        return payload;
    }

    private static byte[] parseAddress(String address) {
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("not a literal IP address: " + address, failure);
        }
    }
}
