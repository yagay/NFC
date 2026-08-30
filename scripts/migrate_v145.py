from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# 1) Register the adapter lifecycle bridge before waiting for Application.
replace_once(
    "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java",
    "        installEarlyKnownRfHook(cl, pid);\n        commandExecutor.execute(() -> initializeRuntime(cl, pid));",
    "        installEarlyKnownRfHook(cl, pid);\n        installEarlyAdapterStateBridge(pid);\n        commandExecutor.execute(() -> initializeRuntime(cl, pid));"
)

# Add the early lifecycle bridge helper before initializeRuntime.
replace_once(
    "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java",
    "    private void initializeRuntime(ClassLoader cl, int pid) {",
    '''    /** Register adapter OFF/ON tracking as early as the system context becomes available.\n     * This is deliberately independent of waitForApplication(): controller power transitions can\n     * happen before the NFC Application object is published, and missing OFF would leave stale RF\n     * evidence valid for the following ON cycle.\n     */\n    private void installEarlyAdapterStateBridge(int pid) {\n        lifecycleExecutor.execute(() -> {\n            long end = System.currentTimeMillis() + 2_000L;\n            while (adapterStateReceiver == null && System.currentTimeMillis() < end) {\n                Context ctx = currentContext();\n                if (ctx != null) {\n                    registerAdapterStateReceiver(ctx);\n                    if (adapterStateReceiver != null) {\n                        Log.i(TAG, "EARLY ADAPTER STATE bridge ready pid=" + pid);\n                        return;\n                    }\n                }\n                sleep(25L);\n            }\n            if (adapterStateReceiver == null) {\n                Log.w(TAG, "EARLY ADAPTER STATE bridge unavailable pid=" + pid);\n            }\n        });\n    }\n\n    private void initializeRuntime(ClassLoader cl, int pid) {'''
)

# Generalize receiver registration to Context and make it idempotent.
replace_once(
    "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java",
    "    private void registerAdapterStateReceiver(Application app) {\n        BroadcastReceiver receiver = new BroadcastReceiver() {",
    "    private synchronized void registerAdapterStateReceiver(Context app) {\n        if (adapterStateReceiver != null) return;\n        BroadcastReceiver receiver = new BroadcastReceiver() {"
)

# Add structured rewrite diagnostics to provider/logs.
replace_once(
    "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java",
    "            activeCodec = rewritten.codecId;\n            writeRfProgress(cfg, \"APPLYING\", uidHex, rewritten.reason, rewritten.codecId);\n            Log.i(TAG, \"NFCID1 APPLY target=\" + target.fingerprint() + \" codec=\" + rewritten.codecId +",
    '''            activeCodec = rewritten.codecId;\n            writeRfProgress(cfg, "APPLYING", uidHex, rewritten.reason, rewritten.codecId);\n            persistRewriteDiagnostics(rewritten);\n            Log.i(TAG, "RF_REWRITE codec=" + rewritten.codecId + " reason=" + rewritten.reason +\n                    " oldPayload=" + rewritten.oldPayloadLength + " newPayload=" + rewritten.newPayloadLength +\n                    " oldCount=" + rewritten.oldParamCount + " newCount=" + rewritten.newParamCount +\n                    " originalBytes=" + original.length + " rewrittenBytes=" + rewritten.data.length);\n            Log.i(TAG, "NFCID1 APPLY target=" + target.fingerprint() + " codec=" + rewritten.codecId +'''
)

# Helper for provider-visible rewrite diagnostics.
replace_once(
    "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java",
    "    private void persistRfCaller(int pid, String caller) {",
    '''    private void persistRewriteDiagnostics(RewriteResult rewritten) {\n        if (rewritten == null) return;\n        ContentValues v = new ContentValues();\n        v.put("rf_rewrite_reason", rewritten.reason == null ? "" : rewritten.reason);\n        v.put("rf_rewrite_codec", rewritten.codecId == null ? "" : rewritten.codecId);\n        v.put("rf_rewrite_old_payload_len", rewritten.oldPayloadLength);\n        v.put("rf_rewrite_new_payload_len", rewritten.newPayloadLength);\n        v.put("rf_rewrite_old_param_count", rewritten.oldParamCount);\n        v.put("rf_rewrite_new_param_count", rewritten.newParamCount);\n        writeValuesWithRetry(v, 8, 75L);\n    }\n\n    private void persistRfCaller(int pid, String caller) {'''
)

# 2) Upgrade OPLUS fallback: before appending, safely prefer a unique existing 33 00/04/07/0A.
oplus = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/xposed/payload/OplusTextConfigCodec.java"
text = oplus.read_text()
text = text.replace('    @Override public String id() { return "oplus-text-config-v3"; }',
                    '    @Override public String id() { return "oplus-text-config-v4"; }')
text = text.replace(
    "        RewriteResult nested = raw.rewrite(block, uid);\n        if (!nested.changed) nested = provenOplusAppend(block, uid, nested.reason);",
    "        RewriteResult nested = raw.rewrite(block, uid);\n        if (!nested.changed) nested = boundedExistingNfcid1Resize(block, uid, nested.reason);\n        if (!nested.changed) nested = provenOplusAppend(block, uid, nested.reason);"
)
marker = "    private RewriteResult provenOplusAppend(byte[] block, byte[] uid, String strictReason) {"
if marker not in text:
    raise SystemExit("Oplus marker not found")
helper = '''    /**\n     * OPLUS wrappers on some vendor builds contain a valid CORE_SET_CONFIG frame that the strict\n     * generic parser cannot fully prove because of proprietary parameter encoding. Before adding a\n     * second LA_NFCID1, look for exactly one bounded existing 0x33 parameter with a stock/supported\n     * length. A unique 33 00 is especially important on OxygenOS/NXP: resizing it is reversible.\n     * If the candidate is ambiguous, do nothing and let the conservative append fallback decide.\n     */\n    private RewriteResult boundedExistingNfcid1Resize(byte[] block, byte[] uid, String strictReason) {\n        for (int i = 0; i + 3 < block.length; i++) {\n            if ((block[i] & 0xFF) != 0x20 || (block[i + 1] & 0xFF) != 0x02) continue;\n            int oldPayload = block[i + 2] & 0xFF;\n            int frameEnd = i + 3 + oldPayload;\n            if (oldPayload < 1 || frameEnd > block.length) continue;\n            int oldCount = block[i + 3] & 0xFF;\n\n            int candidate = -1;\n            int oldLen = -1;\n            for (int p = i + 4; p + 1 < frameEnd; p++) {\n                if ((block[p] & 0xFF) != 0x33) continue;\n                int len = block[p + 1] & 0xFF;\n                if (len != 0 && len != 4 && len != 7 && len != 10) continue;\n                if (p + 2 + len > frameEnd) continue;\n                if (candidate >= 0) {\n                    return RewriteResult.skip(id(), "OPLUS_AMBIGUOUS_EXISTING_LA_NFCID1_AFTER_" + strictReason);\n                }\n                candidate = p;\n                oldLen = len;\n            }\n            if (candidate < 0) continue;\n\n            int delta = uid.length - oldLen;\n            int newPayload = oldPayload + delta;\n            if (newPayload < 1 || newPayload > 0xFF) continue;\n            byte[] out = new byte[block.length + delta];\n            int valueOffset = candidate + 2;\n            System.arraycopy(block, 0, out, 0, valueOffset);\n            out[candidate + 1] = (byte) uid.length;\n            System.arraycopy(uid, 0, out, valueOffset, uid.length);\n            int oldTail = valueOffset + oldLen;\n            int newTail = valueOffset + uid.length;\n            System.arraycopy(block, oldTail, out, newTail, block.length - oldTail);\n            out[i + 2] = (byte) newPayload;\n            return RewriteResult.changed(id(), "OPLUS_BOUNDED_RESIZED_EXISTING_LA_NFCID1_AFTER_" + strictReason, out,\n                    oldPayload, newPayload, oldCount, oldCount);\n        }\n        return RewriteResult.skip(id(), strictReason);\n    }\n\n'''
text = text.replace(marker, helper + marker, 1)
oplus.write_text(text)

# 3) Tests for bounded 33 00 recovery when strict parsing cannot complete.
test = ROOT / "app/src/test/java/com/yagay/nfcdoorcard/xposed/payload/OplusTextConfigCodecTest.java"
t = test.read_text()
insert = '''    @Test public void prefersBoundedExistingZeroLengthNfcid1BeforeAppend() {\n        // Deliberately make the parameter list impossible for the strict parser to fully prove,\n        // while keeping one unique bounded stock LA_NFCID1=33 00 inside the declared frame.\n        String input = "OPLUS_CONF_EXTN = { 20,02,08,02,33,00,FE,03,11,22,33 }";\n        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("C1B0BC1B"));\n        assertTrue(r.changed);\n        assertTrue(r.reason.contains("BOUNDED_RESIZED_EXISTING_LA_NFCID1"));\n        String compact = new String(r.data, StandardCharsets.UTF_8).replaceAll("\\\\s+", "");\n        assertTrue(compact.contains("33,04,C1,B0,BC,1B"));\n        assertFalse(compact.contains("33,00"));\n        assertEquals(r.oldParamCount, r.newParamCount);\n    }\n'''
needle = "    private static byte[] hex(String s) {"
if needle not in t:
    raise SystemExit("test marker missing")
t = t.replace(needle, insert + needle, 1)
test.write_text(t)

# 4) Version / hook build bump.
replace_once("app/build.gradle.kts", 'versionCode = 45\n        versionName = "1.0.44"', 'versionCode = 46\n        versionName = "1.0.45"')
replace_once("app/build.gradle.kts", 'buildConfigField("int", "HOOK_BUILD", "32")', 'buildConfigField("int", "HOOK_BUILD", "33")')
replace_once(
    "app/build.gradle.kts",
    "// Runtime protocol v6; complete com.yagay.nfcdoorcard namespace migration; hook build 32; early RF replay + Android 15+ controller attribution + reversible stock LA_NFCID1 resize + controller-epoch OFF/ON reapply; safe 4/7/10-byte NFCID1 and vendor-neutral NCI discovery.",
    "// Runtime protocol v6; hook build 33; pre-Application adapter lifecycle bridge + deterministic natural RF rewrite + bounded OPLUS existing-NFCID1 recovery + controller-epoch OFF/ON watchdog; safe 4/7/10-byte NFCID1 and vendor-neutral NCI discovery."
)

print("v1.0.45 migration complete")
