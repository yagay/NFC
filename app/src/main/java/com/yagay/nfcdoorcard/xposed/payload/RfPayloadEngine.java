package com.yagay.nfcdoorcard.xposed.payload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects a codec per payload instead of locking the process to one vendor adapter. */
public final class RfPayloadEngine {
    private final RfPayloadCodec[] codecs = new RfPayloadCodec[] {
            new OplusTextConfigCodec(),
            new RawNciCodec()
    };

    public int inspectScore(byte[] input) {
        int best = 0;
        for (RfPayloadCodec codec : codecs) {
            try { best = Math.max(best, codec.inspect(input)); } catch (Throwable ignored) { }
        }
        return best;
    }

    public RewriteResult rewrite(byte[] input, byte[] uid) {
        List<Entry> ranked = new ArrayList<>();
        for (RfPayloadCodec codec : codecs) {
            int score;
            try { score = codec.inspect(input); } catch (Throwable t) { score = 0; }
            if (score > 0) ranked.add(new Entry(codec, score));
        }
        ranked.sort(Comparator.comparingInt((Entry e) -> e.score).reversed());

        StringBuilder reasons = new StringBuilder();
        for (Entry entry : ranked) {
            RewriteResult result;
            try { result = entry.codec.rewrite(input, uid); }
            catch (Throwable t) {
                result = RewriteResult.skip(entry.codec.id(), t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            if (result.changed) return result;
            if (reasons.length() > 0) reasons.append(" | ");
            reasons.append(entry.codec.id()).append(':').append(result.reason);
        }
        if (reasons.length() == 0) reasons.append("UNKNOWN_PAYLOAD");
        return RewriteResult.skip("none", reasons.toString());
    }

    private static final class Entry {
        final RfPayloadCodec codec;
        final int score;
        Entry(RfPayloadCodec codec, int score) { this.codec = codec; this.score = score; }
    }
}
