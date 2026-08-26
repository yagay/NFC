package com.example.nfcdoorcard.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

/**
 * Read-only bridge used by the LSPosed code running inside com.android.nfc.
 *
 * The target UID is stored in device-protected storage so it can be read even when
 * the NFC service is initialized before the user unlocks the device. Access is
 * restricted to this app and com.android.nfc.
 */
public final class UidConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.example.nfcdoorcard.uidconfig";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/target");
    public static final String COLUMN_UID = "uid";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!isAllowedCaller()) {
            throw new SecurityException("Caller is not allowed to read NFC UID configuration");
        }
        if (!URI.equals(uri)) {
            return null;
        }

        Context context = requireContext().createDeviceProtectedStorageContext();
        String uid = context.getSharedPreferences("sim_prefs", Context.MODE_PRIVATE)
                .getString("target_uid", null);

        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_UID}, 1);
        cursor.addRow(new Object[]{uid});
        return cursor;
    }

    private boolean isAllowedCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) {
            return true;
        }

        PackageManager pm = requireContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if ("com.android.nfc".equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private Context requireContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("ContentProvider context is unavailable");
        }
        return context;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".target";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}
