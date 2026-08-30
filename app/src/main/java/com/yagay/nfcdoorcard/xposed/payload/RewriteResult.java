package com.yagay.nfcdoorcard.xposed.payload;

public final class RewriteResult {
    public final boolean changed;
    public final String codecId;
    public final String reason;
    public final byte[] data;
    public final int oldPayloadLength;
    public final int newPayloadLength;
    public final int oldParamCount;
    public final int newParamCount;

    private RewriteResult(boolean changed, String codecId, String reason, byte[] data,
                          int oldPayloadLength, int newPayloadLength,
                          int oldParamCount, int newParamCount) {
        this.changed = changed;
        this.codecId = codecId;
        this.reason = reason;
        this.data = data;
        this.oldPayloadLength = oldPayloadLength;
        this.newPayloadLength = newPayloadLength;
        this.oldParamCount = oldParamCount;
        this.newParamCount = newParamCount;
    }

    public static RewriteResult skip(String codecId, String reason) {
        return new RewriteResult(false, codecId, reason, null, 0, 0, 0, 0);
    }

    public static RewriteResult changed(String codecId, String reason, byte[] data,
                                        int oldPayloadLength, int newPayloadLength,
                                        int oldParamCount, int newParamCount) {
        return new RewriteResult(true, codecId, reason, data,
                oldPayloadLength, newPayloadLength, oldParamCount, newParamCount);
    }
}
