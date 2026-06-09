package com.github.ttereshchenko.mailkit.pst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NameToIdMap {

    public static final UUID PS_MAPI = UUID.fromString("00020328-0000-0000-C000-000000000046");
    public static final UUID PS_PUBLIC_STRINGS = UUID.fromString("00020329-0000-0000-C000-000000000046");
    public static final UUID PS_INTERNET_HEADERS = UUID.fromString("00020386-0000-0000-C000-000000000046");

    public record NamedProperty(UUID guid, String name, Integer id) {}

    private final Map<NamedProperty, Integer> propertyToId = new HashMap<>();
    private final Map<Integer, NamedProperty> idToProperty = new HashMap<>();

    public NameToIdMap(NodeDatabase nodeDatabase) {
        var mapNode = nodeDatabase.getNode(0x61);
        if (mapNode == null) return;

        byte[] nodeData;
        try {
            nodeData = nodeDatabase.readNodeData(mapNode.dataBid());
        } catch (Exception exception) {
            return;
        }

        var propCtx = new PropertyContext(nodeData, nodeDatabase, mapNode);

        byte[] guidStream = (byte[]) propCtx.getProperty(0x0002);
        byte[] entryStream = (byte[]) propCtx.getProperty(0x0003);
        byte[] stringStream = (byte[]) propCtx.getProperty(0x0004);

        if (entryStream == null) return;

        UUID[] guids = new UUID[guidStream != null ? guidStream.length / 16 : 0];
        if (guidStream != null) {
            ByteBuffer guidBuf = ByteBuffer.wrap(guidStream).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < guids.length; i++) {
                long mostSigBits = guidBuf.getLong();
                long leastSigBits = guidBuf.getLong();
                // PST GUID is mixed endian: Data1 (4), Data2 (2), Data3 (2) are LE, Data4 (8) is BE.
                // Reconstruct standard UUID from the bytes
                ByteBuffer converter = ByteBuffer.allocate(16);
                converter.order(ByteOrder.LITTLE_ENDIAN);
                converter.putLong(mostSigBits);
                converter.putLong(leastSigBits);
                byte[] bytes = converter.array();

                long msb = 0;
                msb |= (long) (bytes[3] & 0xFF) << 56;
                msb |= (long) (bytes[2] & 0xFF) << 48;
                msb |= (long) (bytes[1] & 0xFF) << 40;
                msb |= (long) (bytes[0] & 0xFF) << 32;
                msb |= (long) (bytes[5] & 0xFF) << 24;
                msb |= (long) (bytes[4] & 0xFF) << 16;
                msb |= (long) (bytes[7] & 0xFF) << 8;
                msb |= (long) (bytes[6] & 0xFF);

                long lsb = 0;
                for (int j = 8; j < 16; j++) {
                    lsb = (lsb << 8) | (bytes[j] & 0xFF);
                }
                guids[i] = new UUID(msb, lsb);
            }
        }

        ByteBuffer entryBuf = ByteBuffer.wrap(entryStream).order(ByteOrder.LITTLE_ENDIAN);
        int numEntries = entryStream.length / 8;

        for (int i = 0; i < numEntries; i++) {
            int dwPropertyID = entryBuf.getInt();
            int wGuidRaw = Short.toUnsignedInt(entryBuf.getShort());
            int wPropIdx = Short.toUnsignedInt(entryBuf.getShort());

            boolean isStringName = (wGuidRaw & 1) != 0;
            int guidIndex = wGuidRaw >>> 1;

            UUID guid = null;
            if (guidIndex == 1) guid = PS_MAPI;
            else if (guidIndex == 2) guid = PS_PUBLIC_STRINGS;
            else if (guidIndex >= 3 && guidIndex - 3 < guids.length) {
                guid = guids[guidIndex - 3];
            }

            String name = null;
            Integer propId = null;

            if (isStringName) {
                // dwPropertyID is a signed offset into the string stream ([MS-PST] §2.4.7). A negative
                // value would throw at position(...) and a value within 4 bytes of the end would
                // underflow getInt(); validate before reading, and bound the payload with a long so a
                // huge length cannot overflow the comparison. Node 0x61 is the store-wide NPID map, so a
                // single malformed entry must not abort the whole map.
                if (stringStream != null && dwPropertyID >= 0 && dwPropertyID + 4 <= stringStream.length) {
                    ByteBuffer strBuf = ByteBuffer.wrap(stringStream).order(ByteOrder.LITTLE_ENDIAN);
                    strBuf.position(dwPropertyID);
                    int length = strBuf.getInt();
                    if (length > 0 && (long) dwPropertyID + 4 + length <= stringStream.length) {
                        byte[] strBytes = new byte[length];
                        strBuf.get(strBytes);
                        // The string is stored as Unicode (UTF-16LE), length is in bytes
                        name = new String(strBytes, StandardCharsets.UTF_16LE);
                        if (name.endsWith("\0")) {
                            name = name.substring(0, name.length() - 1);
                        }
                    }
                }
            } else {
                propId = dwPropertyID;
            }

            int mappedId = 0x8000 + wPropIdx;
            var prop = new NamedProperty(guid, name, propId);
            propertyToId.put(prop, mappedId);
            idToProperty.put(mappedId, prop);
        }
    }

    public Integer getId(NamedProperty property) {
        return propertyToId.get(property);
    }

    public Integer getId(UUID guid, String name) {
        return propertyToId.get(new NamedProperty(guid, name, null));
    }

    public Integer getId(UUID guid, int propId) {
        return propertyToId.get(new NamedProperty(guid, null, propId));
    }

    public NamedProperty getProperty(int mappedId) {
        return idToProperty.get(mappedId);
    }
}
