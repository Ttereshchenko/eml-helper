package com.github.ttereshchenko.mailkit.pst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class LzFu {

    public static final String LZFU_HEADER =
            "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx";

    private LzFu() {
        // Utility class
    }

    public static String decode(byte[] data) throws PstException {
        if (data == null || data.length < 16) {
            return "";
        }

        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int compressedSize = buf.getInt();
        int uncompressedSize = buf.getInt();
        int compressionSig = buf.getInt();
        int compressedCrc = buf.getInt();

        if (compressionSig == 0x75465A4C) { // LZFu
            if (uncompressedSize < 0 || uncompressedSize > 100 * 1024 * 1024) {
                return ""; // or throw exception, but this is safer for now.
            }
            byte[] output = new byte[uncompressedSize];
            int outputPosition = 0;
            byte[] lzBuffer = new byte[4096];

            byte[] headerBytes = LZFU_HEADER.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(headerBytes, 0, lzBuffer, 0, headerBytes.length);

            int bufferPosition = headerBytes.length;
            int currentDataPosition = 16;

            while (currentDataPosition < data.length - 2 && outputPosition < output.length) {
                int flags = data[currentDataPosition++] & 0xFF;
                for (int x = 0; x < 8 && outputPosition < output.length; x++) {
                    boolean isRef = ((flags & 1) == 1);
                    flags >>= 1;
                    if (isRef) {
                        if (currentDataPosition + 1 >= data.length) break; // truncated reference token
                        int refOffsetOrig = data[currentDataPosition++] & 0xFF;
                        int refSizeOrig = data[currentDataPosition++] & 0xFF;
                        int refOffset = (refOffsetOrig << 4) | (refSizeOrig >>> 4);
                        int refSize = (refSizeOrig & 0xF) + 2;

                        int index = refOffset;
                        for (int y = 0; y < refSize && outputPosition < output.length; y++) {
                            output[outputPosition++] = lzBuffer[index];
                            lzBuffer[bufferPosition] = lzBuffer[index];
                            bufferPosition = (bufferPosition + 1) % 4096;
                            index = (index + 1) % 4096;
                        }
                    } else {
                        if (currentDataPosition >= data.length) break; // truncated literal token
                        lzBuffer[bufferPosition] = data[currentDataPosition];
                        bufferPosition = (bufferPosition + 1) % 4096;
                        output[outputPosition++] = data[currentDataPosition++];
                    }
                }
            }
            if (outputPosition != uncompressedSize) {
                // Warning/Log? We will just return what we decompressed
            }
            return new String(output, 0, outputPosition, StandardCharsets.UTF_8).trim();

        } else if (compressionSig == 0x414C454D) { // MELA (uncompressed)
            return new String(data, 16, data.length - 16, StandardCharsets.UTF_8).trim();
        }

        return "";
    }
}
