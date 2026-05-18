package com.github.ttereshchenko.mailkit.smtp.esmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SizePreflightTest {

    @Test
    void unparseableSizeArgumentYieldsEmpty() {
        assertTrue(SizePreflight.advertisedLimit(List.of("not-a-number")).isEmpty());
    }

    @Test
    void noSizeArgumentYieldsEmpty() {
        assertTrue(SizePreflight.advertisedLimit(List.of()).isEmpty());
        assertTrue(SizePreflight.advertisedLimit(null).isEmpty());
    }

    @Test
    void validSizeArgumentParses() {
        assertEquals(OptionalLong.of(52428800L), SizePreflight.advertisedLimit(List.of("52428800")));
    }

    @Test
    void exceedsLimitReturnsTrueOnlyWhenAdvertisedAndOverLimit() {
        assertFalse(SizePreflight.exceedsLimit(1024, OptionalLong.empty()));
        assertFalse(SizePreflight.exceedsLimit(1024, OptionalLong.of(2048)));
        assertFalse(SizePreflight.exceedsLimit(2048, OptionalLong.of(2048)));
        assertTrue(SizePreflight.exceedsLimit(2049, OptionalLong.of(2048)));
    }

    @Test
    void advertisedZeroMeansUnlimitedPerRfc1870() {
        assertFalse(SizePreflight.exceedsLimit(1, OptionalLong.of(0)));
        assertFalse(SizePreflight.exceedsLimit(Long.MAX_VALUE, OptionalLong.of(0)));
    }
}
