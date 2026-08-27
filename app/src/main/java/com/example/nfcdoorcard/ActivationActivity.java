package com.example.nfcdoorcard;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Launcher activity with libxposed status and in-app NFC/LSPosed diagnostics. */
public final class ActivationActivity extends MainActivity implements NfcDoorApplication.Listener {
    private static final int REQUEST_EXPORT_LOG = 4102;

    private volatile String lastSystemLogReport = "";
    private boolean initialLogCaptureStarted;
    private Button readLogButton;
    private Button exportLogButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addLogControls();
        cleanupLegacyPersistProperties();
    }

    @Override
    protected void onStart() {
        super.onStart();
        NfcDoorApplication.addListener(this);
        refreshStatus();
        if (!initialLogCaptureStarted) {
            initialLogCaptureStarted = true;
            captureSystemLogs(false);
        }
    }

    @Override
    protected void onStop() {
        NfcDoorApplication.removeListener(this);
        super.onStop();
    }

    @Override
    public void onXposedStateChanged() {
        runOnUiThread(this::refreshStatus);
    }

    private void cleanupLegacyPersistProperties() {
        new Thread(() -> {
            RootShell.Result oldActive = RootShell.execute("getprop persist.nfcuidsim.active");
            RootShell.Result oldUid = RootShell.execute("getprop persist.nfcuidsim.uid");
            boolean present = !oldActive.output().isEmpty() || !oldUid.output().isEmpty();
            if (!present) return;

            RootShell.Result clear = RootShell.execute(
                    "setprop persist.nfcuidsim.active ''\n" +
                    "setprop persist.nfcuidsim.uid ''\n");
            if (clear.success()) {
                AppLogger.i("Diag", "已清空旧版 persist.nfcuidsim.* 系统属性");
            } else {
                AppLogger.w("Diag", "旧版 persist.nfcuidsim.* 清理失败: " + clear.describe());
            }
        }, "legacy-prop-cleanup").start();
    }

    private void addLogControls() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpLocal(8), dpLocal(8), dpLocal(8), dpLocal(8));

        readLogButton = new Button(this);
        readLogButton.setText("读取 NFC / LSPosed 日志");
        readLogButton.setAllCaps(false);
        readLogButton.setOnClickListener(v -> captureSystemLogs(true));
        panel.addView(readLogButton, new LinearLayout.LayoutParams(dpLocal(210), dpLocal(48)));

        exportLogButton = new Button(this);
        exportLogButton.setText("导出日志");
        exportLogButton.setAllCaps(false);
        exportLogButton.setOnClickListener(v -> exportLogs());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(dpLocal(210), dpLocal(48));
        exportParams.topMargin = dpLocal(6);
        panel.addView(exportLogButton, exportParams);

        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        params.setMargins(dpLocal(16), dpLocal(16), dpLocal(16), dpLocal(32));
        addContentView(panel, params);
    }

    private void captureSystemLogs(boolean showDialog) {
        setLogButtonsEnabled(false);
        AppLogger.i("Diag", "正在自动读取 NFC / LSPosed 系统日志...");

        new Thread(() -> {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            StringBuilder report = new StringBuilder();
            report.append("NFC 门禁诊断日志\n");
            report.append("生成时间: ").append(timestamp).append('\n');
            report.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            report.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n');
            report.append("App version: ").append(BuildConfig.VERSION_NAME).append("\n\n");

            boolean frameworkConnected = NfcDoorApplication.isFrameworkConnected();
            boolean scopeEnabled = NfcDoorApplication.isNfcScopeEnabled();
            boolean nfcHooked = NfcDoorApplication.isNfcProcessHooked();
            report.append("【libxposed 运行状态】\n");
            report.append("框架连接: ").append(frameworkConnected ? "YES" : "NO").append('\n');
            report.append("com.android.nfc 作用域: ").append(scopeEnabled ? "YES" : "NO").append('\n');
            report.append("com.android.nfc 实际运行 Hook: ").append(nfcHooked ? "YES" : "NO").append('\n');
            report.append("框架: ").append(NfcDoorApplication.getFrameworkSummary()).append('\n');
            report.append("运行目标:\n").append(NfcDoorApplication.getRunningTargetsSummary()).append("\n\n");

            RootShell.Result nfcPid = RootShell.execute("pidof com.android.nfc || true");
            report.append("【NFC 进程】\n");
            report.append("PID: ").append(nfcPid.output().isEmpty() ? "未找到" : nfcPid.output()).append("\n\n");

            RootShell.Result props = RootShell.execute(
                    "printf 'ro.hardware.nfc='; getprop ro.hardware.nfc; " +
                    "printf 'ro.boot.hardware='; getprop ro.boot.hardware; " +
                    "printf 'legacy.active='; getprop persist.nfcuidsim.active; " +
                    "printf 'legacy.uid='; getprop persist.nfcuidsim.uid");
            report.append("【设备 NFC 属性】\n");
            report.append(props.output().isEmpty() ? props.describe() : props.output()).append("\n\n");

            RootShell.Result logcat = RootShell.execute(
                    "logcat -d -v threadtime 2>/dev/null | " +
                    "grep -i -E 'NfcUIDSim|NFC-SCAN|LSPosed|libxposed|com\\.android\\.nfc' | " +
                    "tail -n 2000 || true");
            report.append("【NFC / LSPosed Logcat】\n");
            if (logcat.output().isEmpty()) report.append("未找到匹配日志。\n");
            else report.append(logcat.output()).append('\n');

            RootShell.Result lsposedFiles = RootShell.execute(
                    "for d in /data/adb/lspd/log /data/adb/lsposed/log; do " +
                    "[ -d \"$d\" ] && find \"$d\" -maxdepth 2 -type f 2>/dev/null; done | tail -n 80 || true");
            report.append("\n【LSPosed 持久日志文件】\n");
            report.append(lsposedFiles.output().isEmpty() ? "未找到常见 LSPosed 日志目录/文件\n" : lsposedFiles.output() + "\n");

            RootShell.Result lsposedPersistent = RootShell.execute(
                    "for d in /data/adb/lspd/log /data/adb/lsposed/log; do " +
                    "[ -d \"$d\" ] || continue; " +
                    "find \"$d\" -maxdepth 2 -type f -print0 2>/dev/null | " +
                    "xargs -0 grep -H -i -E 'NfcUIDSim|NFC-SCAN|com\\.example\\.nfcdoorcard|com\\.android\\.nfc' 2>/dev/null; " +
                    "done | tail -n 3000 || true");
            report.append("\n【LSPosed 持久日志匹配内容】\n");
            if (lsposedPersistent.output().isEmpty()) {
                report.append("未找到模块/NFC 匹配内容。若上面的“实际运行 Hook”为 NO，优先检查模块加载/框架兼容性。\n");
            } else {
                report.append(lsposedPersistent.output()).append('\n');
            }

            lastSystemLogReport = report.toString();
            runOnUiThread(() -> {
                setLogButtonsEnabled(true);
                AppLogger.i("Diag", "系统日志读取完成，共 " + lastSystemLogReport.length() + " 个字符");
                if (showDialog) showCapturedLogs();
            });
        }, "nfc-system-log-reader").start();
    }

    private void showCapturedLogs() {
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(lastSystemLogReport.isEmpty() ? "尚未读取系统日志" : lastSystemLogReport);
        text.setTextIsSelectable(true);
        text.setTextSize(11);
        text.setPadding(dpLocal(16), dpLocal(12), dpLocal(16), dpLocal(12));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);

        new android.app.AlertDialog.Builder(this)
                .setTitle("NFC / LSPosed 系统日志")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("导出", (d, w) -> exportLogs())
                .show();
    }

    private void exportLogs() {
        if (lastSystemLogReport.isEmpty()) {
            Toast.makeText(this, "还没有日志，正在先读取", Toast.LENGTH_SHORT).show();
            captureSystemLogs(true);
            return;
        }

        String name = "nfc-diagnostics-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQUEST_EXPORT_LOG);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("无法打开导出文件");
            out.write(lastSystemLogReport.getBytes(StandardCharsets.UTF_8));
            out.flush();
            AppLogger.i("Diag", "日志已导出: " + uri);
            Toast.makeText(this, "日志导出成功", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLogger.e("Diag", "日志导出失败: " + e.getMessage());
            Toast.makeText(this, "日志导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setLogButtonsEnabled(boolean enabled) {
        if (readLogButton != null) readLogButton.setEnabled(enabled);
        if (exportLogButton != null) exportLogButton.setEnabled(enabled);
    }

    private int dpLocal(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
