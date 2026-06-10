package com.github.ttereshchenko.mailkit.smtp;

/**
 * Per-send toggles for ESMTP extensions. Each toggle defaults to a sensible "use if advertised"
 * value, so a caller who knows nothing about the extension surface still gets correct behavior;
 * power users flip individual knobs per-send.
 */
public record EsmtpConfig(
        boolean usePipelining,
        boolean useBdat,
        boolean usePrdr,
        boolean enforceSmtpUtf8,
        boolean honorSize,
        EightBitMimePolicy eightBitMime,
        boolean declareSizeOnMail) {

    public enum EightBitMimePolicy {
        /** Use BODY=8BITMIME when advertised; fail if the body needs it but the server cannot. */
        REQUIRE_WHEN_NEEDED,
        /** Use BODY=8BITMIME when advertised; otherwise downgrade silently (the server may garble). */
        DOWNGRADE_IF_UNADVERTISED,
        /** Never declare BODY=8BITMIME. */
        NEVER
    }

    public static EsmtpConfig defaults() {
        return new EsmtpConfig(true, false, false, true, true, EightBitMimePolicy.REQUIRE_WHEN_NEEDED, true);
    }

    public EsmtpConfig withPipelining(boolean value) {
        return new EsmtpConfig(value, useBdat, usePrdr, enforceSmtpUtf8, honorSize, eightBitMime, declareSizeOnMail);
    }

    public EsmtpConfig withBdat(boolean value) {
        return new EsmtpConfig(
                usePipelining, value, usePrdr, enforceSmtpUtf8, honorSize, eightBitMime, declareSizeOnMail);
    }

    public EsmtpConfig withPrdr(boolean value) {
        return new EsmtpConfig(
                usePipelining, useBdat, value, enforceSmtpUtf8, honorSize, eightBitMime, declareSizeOnMail);
    }

    public EsmtpConfig withEnforceSmtpUtf8(boolean value) {
        return new EsmtpConfig(usePipelining, useBdat, usePrdr, value, honorSize, eightBitMime, declareSizeOnMail);
    }

    public EsmtpConfig withEightBitMime(EightBitMimePolicy value) {
        return new EsmtpConfig(usePipelining, useBdat, usePrdr, enforceSmtpUtf8, honorSize, value, declareSizeOnMail);
    }

    public EsmtpConfig withHonorSize(boolean value) {
        return new EsmtpConfig(
                usePipelining, useBdat, usePrdr, enforceSmtpUtf8, value, eightBitMime, declareSizeOnMail);
    }

    public EsmtpConfig withDeclareSizeOnMail(boolean value) {
        return new EsmtpConfig(usePipelining, useBdat, usePrdr, enforceSmtpUtf8, honorSize, eightBitMime, value);
    }
}
