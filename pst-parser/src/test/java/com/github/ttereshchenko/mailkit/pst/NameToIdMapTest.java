package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NameToIdMapTest {

    /** PSETID_Appointment, also used by the plugin's calendar export. */
    private static final UUID PSETID_APPOINTMENT = UUID.fromString("00062002-0000-0000-C000-000000000046");

    /** The PST GUID stream stores GUIDs mixed-endian: Data1/2/3 little-endian, Data4 as-is. */
    private static byte[] pstGuidBytes() {
        return new byte[] {
            0x02,
            0x20,
            0x06,
            0x00, // Data1 00062002 (LE)
            0x00,
            0x00, // Data2 (LE)
            0x00,
            0x00, // Data3 (LE)
            (byte) 0xC0,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x46 // Data4 (BE)
        };
    }

    @Test
    void resolvesNumericAndStringNamedProperties() {
        byte[] nameBytes = "Test".getBytes(StandardCharsets.UTF_16LE);
        var stringStream = ByteBuffer.allocate(4 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        stringStream.putInt(0, nameBytes.length);
        stringStream.put(4, nameBytes);

        var entryStream = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        // Entry 0: numeric id 0x820D in the custom GUID (index 3 -> guids[0]), property index 0.
        entryStream.putInt(0, 0x820D);
        entryStream.putShort(4, (short) ((3 << 1)));
        entryStream.putShort(6, (short) 0);
        // Entry 1: string name at offset 0 in PS_PUBLIC_STRINGS (index 2), property index 1.
        entryStream.putInt(8, 0);
        entryStream.putShort(12, (short) ((2 << 1) | 1));
        entryStream.putShort(14, (short) 1);

        var map = new NameToIdMap(pstGuidBytes(), entryStream.array(), stringStream.array());

        assertEquals(0x8000, map.getId(PSETID_APPOINTMENT, 0x820D));
        assertEquals(0x8001, map.getId(NameToIdMap.PS_PUBLIC_STRINGS, "Test"));
        assertNull(map.getId(PSETID_APPOINTMENT, 0x9999));

        var named = map.getProperty(0x8000);
        assertNotNull(named);
        assertEquals(PSETID_APPOINTMENT, named.guid(), "The mixed-endian GUID must round-trip");
    }

    @Test
    void malformedStringOffsetsAreSkippedNotFatal() {
        var entryStream = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        // Entry 0: a healthy numeric property.
        entryStream.putInt(0, 0x820E);
        entryStream.putShort(4, (short) (3 << 1));
        entryStream.putShort(6, (short) 0);
        // Entry 1: string entry with a negative stream offset — previously a crash candidate.
        entryStream.putInt(8, -5);
        entryStream.putShort(12, (short) ((2 << 1) | 1));
        entryStream.putShort(14, (short) 1);
        // Entry 2: string entry whose offset lands within 4 bytes of the stream end.
        entryStream.putInt(16, 6);
        entryStream.putShort(20, (short) ((2 << 1) | 1));
        entryStream.putShort(22, (short) 2);

        var map = new NameToIdMap(pstGuidBytes(), entryStream.array(), new byte[8]);

        assertEquals(0x8000, map.getId(PSETID_APPOINTMENT, 0x820E), "Healthy entries must survive bad neighbours");
        assertNull(map.getId(NameToIdMap.PS_PUBLIC_STRINGS, "anything"));
    }

    @Test
    void stringOffsetNearIntMaxDoesNotOverflowGuardAndAbortMap() {
        var entryStream = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        // Entry 0: a healthy numeric property.
        entryStream.putInt(0, 0x820F);
        entryStream.putShort(4, (short) (3 << 1));
        entryStream.putShort(6, (short) 0);
        // Entry 1: string entry whose offset is within four bytes of Integer.MAX_VALUE. The bounds
        // check `offset + 4 <= length` evaluated in 32-bit int arithmetic wrapped to a negative value
        // (<= length), so the guard passed and position(offset) threw out of the unguarded
        // parseStreams — dropping the entire store-wide named-property map. The long-widened guard
        // must reject it like any other out-of-range offset and keep the healthy neighbour.
        entryStream.putInt(8, 0x7FFFFFFD);
        entryStream.putShort(12, (short) ((2 << 1) | 1));
        entryStream.putShort(14, (short) 1);

        var map = new NameToIdMap(pstGuidBytes(), entryStream.array(), new byte[8]);

        assertEquals(
                0x8000,
                map.getId(PSETID_APPOINTMENT, 0x820F),
                "A near-MAX_INT string offset must not abort the whole map");
        assertNull(map.getId(NameToIdMap.PS_PUBLIC_STRINGS, "anything"));
    }

    @Test
    void missingEntryStreamYieldsEmptyMap() {
        var map = new NameToIdMap(pstGuidBytes(), null, null);
        assertNull(map.getId(PSETID_APPOINTMENT, 0x820D));
    }
}
