package com.example.nfcdoorcard.xposed.profile;

import android.app.Application;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import com.example.nfcdoorcard.xposed.discovery.Capability;
import com.example.nfcdoorcard.xposed.discovery.HookTarget;

import java.util.HashMap;
import java.util.Map;

/** Persists and validates the current verified hook target against the running NFC build. */
public final class HookProfileStore {
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    public void save(Application app, HookTarget target, String status) {
        if (app == null || target == null) return;
        ContentValues v = new ContentValues();
        v.put("profile_status", status == null ? "DISCOVERED" : status);
        v.put("profile_system_fingerprint", Build.FINGERPRINT == null ? "" : Build.FINGERPRINT);
        v.put("profile_nfc_version", nfcVersion(app));
        v.put("rf_hook_class", target.className);
        v.put("rf_hook_method", target.methodName);
        v.put("rf_hook_param_signature", target.parameterSignature);
        v.put("rf_hook_return_type", target.returnType);
        v.put("rf_hook_signature", target.parameterSignature + "->" + target.returnType);
        v.put("rf_hook_score", target.score);
        v.put("rf_hook_source", target.source);
        v.put("rf_hook_fingerprint", target.fingerprint());
        try { app.getContentResolver().insert(CONFIG_URI, v); } catch (Throwable ignored) { }
    }

    /** Returns only a VERIFIED target from the exact same system/NFC build and resolvable signature. */
    public HookTarget loadValid(Application app, ClassLoader classLoader) {
        if (app == null || classLoader == null) return null;
        Map<String, String> state = read(app);
        if (!"VERIFIED".equals(state.get("profile_status"))) return null;
        if (!systemIdentityMatches(app,
                state.get("profile_system_fingerprint"), state.get("profile_nfc_version"))) return null;

        String className = state.get("rf_hook_class");
        String methodName = state.get("rf_hook_method");
        String params = state.get("rf_hook_param_signature");
        String returnType = state.get("rf_hook_return_type");
        if (blank(className) || blank(methodName) || blank(params) || blank(returnType)) return null;

        int score = parseInt(state.get("rf_hook_score"), 1000);
        HookTarget target = new HookTarget(Capability.RF_CONFIG_WRITE, className, methodName,
                returnType, params, score, "profile-cache");
        try {
            target.resolve(classLoader);
            return target;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean systemIdentityMatches(Application app, String fingerprint, String nfcVersion) {
        if (app == null) return false;
        String currentFingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        return currentFingerprint.equals(fingerprint == null ? "" : fingerprint)
                && nfcVersion(app).equals(nfcVersion == null ? "" : nfcVersion);
    }

    private Map<String, String> read(Application app) {
        Map<String, String> out = new HashMap<>();
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private String nfcVersion(Application app) {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo("com.android.nfc", 0);
            return String.valueOf(info.getLongVersionCode());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Throwable ignored) { return fallback; }
    }

    private static boolean blank(String value) { return value == null || value.isEmpty(); }
}
