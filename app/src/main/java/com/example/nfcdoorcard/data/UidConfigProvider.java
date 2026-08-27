package com.example.nfcdoorcard.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.util.Log;

/** Read-only bridge for NFC diagnostics/configuration data. */
public final class UidConfigProvider extends ContentProvider {
    private static final String TAG = "UidConfigProvider";
    public static final String AUTHORITY = "com.example.nfcdoorcard.uidconfig";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/target");
    public static final String COLUMN_UID = "uid";
    public static final String COLUMN_ACTIVE = "active";

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        enforceAllowedCaller();
        if (!URI.equals(uri)) return null;

        Context context = providerContext().createDeviceProtectedStorageContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
        String uid = prefs.getString("target_uid", null);
        boolean active = prefs.getBoolean("request_active", false);

        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_UID, COLUMN_ACTIVE}, 1);
        cursor.addRow(new Object[]{uid, active ? 1 : 0});
        return cursor;
    }

    private void enforceAllowedCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) return;

        PackageManager pm = providerContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("com.android.nfc".equals(pkg)) return;
            }
        }
        Log.w(TAG, "Rejected caller uid=" + callingUid);
        throw new SecurityException("Caller is not allowed to read NFC UID configuration");
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("ContentProvider context is unavailable");
        return context;
    }

    @Override
    public String getType(Uri uri) {
        if (!URI.equals(uri)) return null;
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".target";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
}
