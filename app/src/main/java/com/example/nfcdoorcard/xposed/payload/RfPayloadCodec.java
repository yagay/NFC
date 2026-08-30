package com.example.nfcdoorcard.xposed.payload;

/** Decoder/rewriter for one RF payload representation. */
public interface RfPayloadCodec {
    String id();

    /** Higher values mean this codec is more likely to own the payload. */
    int inspect(byte[] input);

    RewriteResult rewrite(byte[] input, byte[] uid);
}
