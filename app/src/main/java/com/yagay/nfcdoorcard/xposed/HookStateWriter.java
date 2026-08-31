package com.yagay.nfcdoorcard.xposed;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Preserves ordered asynchronous writes and explicit synchronous lifecycle barriers. */
final class HookStateWriter {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI =
            Uri.parse("content://com.yagay.nfcdoorcard.config/settings");
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> NfcHookUtils.daemon(runnable, "NfcUIDSim-StateSync"));

    void writeAsync(ContentValues values, int attempts, long delayMs) {
        final ContentValues copy = new ContentValues(values);
        executor.execute(() -> {
            for (int i = 0; i < attempts; i++) {
                Context context = NfcHookUtils.currentContext();
                if (context != null) {
                    try {
                        context.getContentResolver().insert(CONFIG_URI, copy);
                        return;
                    } catch (Throwable error) {
                        Log.w(TAG, "status write attempt " + (i + 1) + " failed: " + error.getMessage());
                    }
                }
                NfcHookUtils.sleep(delayMs);
            }
        });
    }

    boolean writeSynchronously(ContentValues values, int attempts, long delayMs) {
        ContentValues copy = new ContentValues(values);
        for (int i = 0; i < attempts; i++) {
            Context context = NfcHookUtils.currentContext();
            if (context != null) {
                try {
                    context.getContentResolver().insert(CONFIG_URI, copy);
                    return true;
                } catch (Throwable error) {
                    Log.w(TAG, "sync state write attempt " + (i + 1) + " failed: " + error.getMessage());
                }
            }
            if (i + 1 < attempts) NfcHookUtils.sleep(delayMs);
        }
        return false;
    }
}
