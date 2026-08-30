package com.example.nfcdoorcard.xposed.profile;

import android.app.Application;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import com.example.nfcdoorcard.xposed.discovery.HookTarget;

/** Persists the current verified hook target and the system identity it was verified on. */
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
        v.put("rf_hook_signature", target.parameterSignature + "->" + target.returnType);
        v.put("rf_hook_score", target.score);
        v.put("rf_hook_source", target.source);
        v.put("rf_hook_fingerprint", target.fingerprint());
        try { app.getContentResolver().insert(CONFIG_URI, v); } catch (Throwable ignored) { }
    }

    public boolean systemIdentityMatches(Application app, String fingerprint, String nfcVersion) {
        if (app == null) return false;
        String currentFingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        return currentFingerprint.equals(fingerprint == null ? "" : fingerprint)
                && nfcVersion(app).equals(nfcVersion == null ? "" : nfcVersion);
    }

    private String nfcVersion(Application app) {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo("com.android.nfc", 0);
            return String.valueOf(info.getLongVersionCode());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
