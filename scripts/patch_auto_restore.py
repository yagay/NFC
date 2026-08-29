from pathlib import Path

# Manifest: boot permission + receiver.
p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
if 'android.permission.RECEIVE_BOOT_COMPLETED' not in s:
    s = s.replace('    <uses-permission android:name="android.permission.NFC" />\n',
                  '    <uses-permission android:name="android.permission.NFC" />\n    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n')
receiver = '''\n        <receiver\n            android:name=".AutoRestoreReceiver"\n            android:enabled="true"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.BOOT_COMPLETED" />\n                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n                <action android:name="com.example.nfcdoorcard.action.NFC_HOOK_READY" />\n            </intent-filter>\n        </receiver>\n'''
if 'android:name=".AutoRestoreReceiver"' not in s:
    s = s.replace('\n        <provider\n            android:name=".ConfigProvider"', receiver + '\n        <provider\n            android:name=".ConfigProvider"', 1)
p.write_text(s)

# NfcInjectionModule: when the hook becomes READY, explicitly wake the app receiver.
p = Path('app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java')
s = p.read_text()
if 'import android.content.Intent;' not in s:
    s = s.replace('import android.content.ContentValues;\n', 'import android.content.ContentValues;\nimport android.content.Intent;\n')
needle = '            Log.i(TAG, "PROD HOOK READY build=" + HOOK_BUILD + " adapter=" + adapter.id() + " pid=" + pid);'
replacement = needle + '''\n            notifyAppHookReady(pid, adapter.id());'''
if 'notifyAppHookReady(pid, adapter.id());' not in s:
    if needle not in s:
        raise SystemExit('hook ready log not found')
    s = s.replace(needle, replacement, 1)

insert_before = '    private void installRefreshProbes(ClassLoader cl, int pid) {'
method = '''    private void notifyAppHookReady(int pid, String adapterId) {\n        Application app = currentApplication();\n        if (app == null) {\n            Log.w(TAG, "AUTO_RESTORE: currentApplication unavailable pid=" + pid);\n            return;\n        }\n        try {\n            Intent intent = new Intent("com.example.nfcdoorcard.action.NFC_HOOK_READY");\n            intent.setPackage("com.example.nfcdoorcard");\n            intent.putExtra("nfc_pid", pid);\n            intent.putExtra("adapter", adapterId == null ? "" : adapterId);\n            app.sendBroadcast(intent);\n            Log.i(TAG, "AUTO_RESTORE: hook-ready broadcast sent pid=" + pid + " adapter=" + adapterId);\n        } catch (Throwable t) {\n            Log.w(TAG, "AUTO_RESTORE: broadcast failed " + t.getClass().getSimpleName() + ": " + t.getMessage());\n        }\n    }\n\n'''
if 'private void notifyAppHookReady(' not in s:
    if insert_before not in s:
        raise SystemExit('insert point not found')
    s = s.replace(insert_before, method + insert_before, 1)
p.write_text(s)
