package com.yagay.nfcdoorcard.xposed;

/** Pure lifecycle settling rules used by the NFC process hook. */
public final class RfSettlingPolicy {
    private RfSettlingPolicy() {}

    public static boolean isSettled(long now, long recoveryStartedAt, long lastRfWriteAt,
                                    long minSettleMs, long quietMs) {
        if (recoveryStartedAt <= 0L || now < recoveryStartedAt) return false;
        if (now - recoveryStartedAt < Math.max(0L, minSettleMs)) return false;
        if (lastRfWriteAt <= 0L) return true;
        if (now < lastRfWriteAt) return false;
        return now - lastRfWriteAt >= Math.max(0L, quietMs);
    }

    public static boolean isStrictlyNewer(long sequence, long baseline) {
        return sequence > baseline && sequence > 0L;
    }
}
