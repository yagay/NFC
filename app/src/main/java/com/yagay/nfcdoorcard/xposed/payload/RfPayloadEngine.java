package com.yagay.nfcdoorcard.xposed.payload;

/** Selects a codec per payload instead of locking the process to one vendor adapter. */
public final class RfPayloadEngine {
    private final RfPayloadCodec oplusCodec = new OplusTextConfigCodec();
    private final RfPayloadCodec rawCodec = new RawNciCodec();

    public int inspectScore(byte[] input) {
        return Math.max(inspect(oplusCodec, input), inspect(rawCodec, input));
    }

    public RewriteResult rewrite(byte[] input, byte[] uid) {
        int oplusScore = inspect(oplusCodec, input);
        int rawScore = inspect(rawCodec, input);
        if (oplusScore <= 0 && rawScore <= 0) return RewriteResult.skip("none", "UNKNOWN_PAYLOAD");

        RfPayloadCodec first = oplusScore >= rawScore ? oplusCodec : rawCodec;
        RfPayloadCodec second = first == oplusCodec ? rawCodec : oplusCodec;
        int secondScore = first == oplusCodec ? rawScore : oplusScore;

        RewriteResult firstResult = rewriteSafely(first, input, uid);
        if (firstResult.changed) return firstResult;
        if (secondScore <= 0) return RewriteResult.skip("none", first.id() + ':' + firstResult.reason);

        RewriteResult secondResult = rewriteSafely(second, input, uid);
        if (secondResult.changed) return secondResult;
        return RewriteResult.skip("none",
                first.id() + ':' + firstResult.reason + " | " + second.id() + ':' + secondResult.reason);
    }

    private static int inspect(RfPayloadCodec codec, byte[] input) {
        try { return codec.inspect(input); } catch (Throwable ignored) { return 0; }
    }

    private static RewriteResult rewriteSafely(RfPayloadCodec codec, byte[] input, byte[] uid) {
        try { return codec.rewrite(input, uid); }
        catch (Throwable t) {
            return RewriteResult.skip(codec.id(), t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
