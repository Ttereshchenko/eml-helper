package com.github.ttereshchenko.mailkit.pst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The store-wide named-property map (NBT node {@code 0x61}, [MS-PST] §2.4.7): resolves a named
 * property (property-set GUID plus numeric id or string name) to the 16-bit property id this store
 * assigned to it.
 */
class NameToIdMap {

    private static final System.Logger LOG = System.getLogger(NameToIdMap.class.getName());

    public static final UUID PS_MAPI = UUID.fromString("00020328-0000-0000-C000-000000000046");
    public static final UUID PS_PUBLIC_STRINGS = UUID.fromString("00020329-0000-0000-C000-000000000046");
    public static final UUID PS_INTERNET_HEADERS = UUID.fromString("00020386-0000-0000-C000-000000000046");

    public record NamedProperty(UUID guid, String name, Integer id) {}

    private final Map<NamedProperty, Integer> propertyToId = new HashMap<>();
    private final Map<Integer, NamedProperty> idToProperty = new HashMap<>();

    NameToIdMap(NodeDatabase nodeDatabase) {
        byte[] guidStream = null;
        byte[] entryStream = null;
        byte[] stringStream = null;
        try {
            var mapNode = nodeDatabase.getNode(0x61);
            if (mapNode == null) {
                return;
            }
            byte[] nodeData = nodeDatabase.readNodeData(mapNode.dataBid());
            var propertyContext = new PropertyContext(nodeData, nodeDatabase, mapNode);
            guidStream = propertyContext.getProperty(0x0002) instanceof byte[] bytes ? bytes : null;
            entryStream = propertyContext.getProperty(0x0003) instanceof byte[] bytes ? bytes : null;
            stringStream = propertyContext.getProperty(0x0004) instanceof byte[] bytes ? bytes : null;
        } catch (Exception exception) {
            // The named-property map is an enrichment; a store whose node 0x61 is corrupt must still
            // open, so degrade to an empty map but leave a trace.
            LOG.log(System.Logger.Level.WARNING, "Failed to read the named-property map (node 0x61)", exception);
            return;
        }
        parseStreams(guidStream, entryStream, stringStream);
    }

    /** Builds the map directly from the three NPMAP streams; the production path extracts them from node 0x61. */
    NameToIdMap(byte[] guidStream, byte[] entryStream, byte[] stringStream) {
        parseStreams(guidStream, entryStream, stringStream);
    }

    private void parseStreams(byte[] guidStream, byte[] entryStream, byte[] stringStream) {
        if (entryStream == null) {
            return;
        }

        var guids = new UUID[guidStream != null ? guidStream.length / 16 : 0];
        if (guidStream != null) {
            var guidBuffer = ByteBuffer.wrap(guidStream).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < guids.length; i++) {
                long mostSigBits = guidBuffer.getLong();
                long leastSigBits = guidBuffer.getLong();
                // PST GUID is mixed endian: Data1 (4), Data2 (2), Data3 (2) are LE, Data4 (8) is BE.
                // Reconstruct standard UUID from the bytes
                var converter = ByteBuffer.allocate(16);
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

        var entryBuffer = ByteBuffer.wrap(entryStream).order(ByteOrder.LITTLE_ENDIAN);
        int entryCount = entryStream.length / 8;

        for (int i = 0; i < entryCount; i++) {
            int propertyIdOrOffset = entryBuffer.getInt();
            int guidIndicator = Short.toUnsignedInt(entryBuffer.getShort());
            int propertyIndex = Short.toUnsignedInt(entryBuffer.getShort());

            boolean isStringName = (guidIndicator & 1) != 0;
            int guidIndex = guidIndicator >>> 1;

            UUID guid = null;
            if (guidIndex == 1) {
                guid = PS_MAPI;
            } else if (guidIndex == 2) {
                guid = PS_PUBLIC_STRINGS;
            } else if (guidIndex >= 3 && guidIndex - 3 < guids.length) {
                guid = guids[guidIndex - 3];
            }

            String name = null;
            Integer propertyId = null;

            if (isStringName) {
                // The value is a signed offset into the string stream ([MS-PST] §2.4.7). A negative
                // value would throw at position(...) and a value within 4 bytes of the end would
                // underflow getInt(); validate before reading, and bound the payload with a long so a
                // huge length cannot overflow the comparison. Node 0x61 is the store-wide NPID map, so a
                // single malformed entry must not abort the whole map.
                if (stringStream != null && propertyIdOrOffset >= 0 && propertyIdOrOffset + 4 <= stringStream.length) {
                    var stringBuffer = ByteBuffer.wrap(stringStream).order(ByteOrder.LITTLE_ENDIAN);
                    stringBuffer.position(propertyIdOrOffset);
                    int length = stringBuffer.getInt();
                    if (length > 0 && (long) propertyIdOrOffset + 4 + length <= stringStream.length) {
                        var stringBytes = new byte[length];
                        stringBuffer.get(stringBytes);
                        // The string is stored as Unicode (UTF-16LE), length is in bytes
                        name = new String(stringBytes, StandardCharsets.UTF_16LE);
                        if (name.endsWith("\0")) {
                            name = name.substring(0, name.length() - 1);
                        }
                    }
                }
            } else {
                propertyId = propertyIdOrOffset;
            }

            int mappedId = 0x8000 + propertyIndex;
            var property = new NamedProperty(guid, name, propertyId);
            propertyToId.put(property, mappedId);
            idToProperty.put(mappedId, property);
        }
    }

    public Integer getId(NamedProperty property) {
        return propertyToId.get(property);
    }

    public Integer getId(UUID guid, String name) {
        return propertyToId.get(new NamedProperty(guid, name, null));
    }

    public Integer getId(UUID guid, int propertyId) {
        return propertyToId.get(new NamedProperty(guid, null, propertyId));
    }

    public NamedProperty getProperty(int mappedId) {
        return idToProperty.get(mappedId);
    }
}
