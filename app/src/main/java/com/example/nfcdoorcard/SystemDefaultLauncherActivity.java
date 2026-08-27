package com.example.nfcdoorcard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small launcher that keeps the existing diagnostic UI untouched while providing
 * an explicit way to inspect/restore NFC UID handling.
 */
public final class SystemDefaultLauncherActivity extends AppCompatActivity {
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);
    private Button restoreButton;
    private Button scanConfigButton;

    private SharedPreferences simulationPrefs() {
        return createDeviceProtectedStorageContext()
                .getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private LinearLayout buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC UID 控制");
        title.setTextSize(24);
        root.addView(title, lp(-1, -2, 16));

        TextView info = new TextView(this);
        info.setText("当前硬件属性已确认是 ST_NFC。下面的扫描只读取 ST HAL 配置，重点定位 NFC-A Listen 参数 0x30/0x31/0x32/0x33 及 NFCID1 候选值，不写入任何 NFC 数据。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        Button openMain = new Button(this);
        openMain.setText("进入 NFC 门禁页面");
        openMain.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)));
        root.addView(openMain, lp(-1, dp(52), 12));

        scanConfigButton = new Button(this);
        scanConfigButton.setText("解析 ST 原厂 NFCID1（只读）");
        scanConfigButton.setOnClickListener(v -> scanFactoryNfcConfig());
        root.addView(scanConfigButton, lp(-1, dp(52), 12));

        restoreButton = new Button(this);
        restoreButton.setText("恢复系统默认 UID");
        restoreButton.setOnClickListener(v -> confirmRestore());
        root.addView(restoreButton, lp(-1, dp(52), 12));

        return root;
    }

    private void scanFactoryNfcConfig() {
        if (!operationRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "NFC 操作正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        setActionButtonsEnabled(false);
        Toast.makeText(this, "正在只读解析 ST NFC 配置…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String command =
                    "set +e\n" +
                    "echo '=== NFC hardware ==='\n" +
                    "echo -n 'ro.hardware.nfc='; getprop ro.hardware.nfc\n" +
                    "echo -n 'ro.boot.hardware='; getprop ro.boot.hardware\n" +
                    "echo\n" +
                    "echo '=== ST HAL files ==='\n" +
                    "STFILES=$(find /vendor /odm /system /product -type f 2>/dev/null | grep -Ei '/libnfc-hal-st[^/]*\\.(conf|cfg)$' | sort -u)\n" +
                    "if [ -z \"$STFILES\" ]; then echo '(none found)'; else echo \"$STFILES\"; fi\n" +
                    "echo\n" +
                    "echo '=== Generic ST config link / metadata ==='\n" +
                    "ls -l /vendor/etc/libnfc-hal-st.conf 2>/dev/null\n" +
                    "stat /vendor/etc/libnfc-hal-st.conf 2>/dev/null | head -n 8\n" +
                    "echo\n" +
                    "echo '=== NFC-related properties / init hints ==='\n" +
                    "getprop | grep -Ei '(^|[._])(nfc|stnfc|st21|st54|sn[0-9])([._]|$)' | head -n 120\n" +
                    "echo\n" +
                    "echo '=== Direct NFCID1 / Listen-A text matches ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  m=$(grep -Ein -C 4 'LA_NFCID1|NFCID1|LISTEN|POLL|NCI|RF_PARAM|RF_CONFIG' \"$f\" 2>/dev/null | head -n 180)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n" +
                    "echo\n" +
                    "echo '=== Raw 0x30..0x33 candidate lines ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  m=$(grep -Ein -C 5 '(^|[^0-9A-Fa-f])(0[xX])?(30|31|32|33)([^0-9A-Fa-f]|$)' \"$f\" 2>/dev/null | head -n 220)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n" +
                    "echo\n" +
                    "echo '=== Compact hex-array candidates containing 30/31/32/33 ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  m=$(grep -Ein '[{=].*(30|31|32|33)[, }]' \"$f\" 2>/dev/null | head -n 120)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n";

            RootShell.Result result = RootShell.execute(command);
            String report = result.output();
            if (report == null || report.isBlank()) {
                report = "未找到 ST 配置，或 Root 无法读取相关目录。\n" + result.describe();
            }

            String finalReport = report;
            AppLogger.i("UID", "ST 原厂 NFCID1 只读解析完成: success=" + result.success());
            operationRunning.set(false);
            runOnUiThread(() -> {
                setActionButtonsEnabled(true);
                TextView view = new TextView(this);
                view.setText(finalReport);
                view.setTextIsSelectable(true);
                view.setPadding(dp(16), dp(8), dp(16), dp(8));
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(view);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("ST 原厂 NFCID1 解析结果")
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "scan-st-nfc-config").start();
    }

    private void confirmRestore() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("恢复系统默认 UID")
                .setMessage("将停止 UID 测试、删除已保存的目标 UID，并重启 NFC 服务。不会写入任何新的 UID。")
                .setPositiveButton("恢复", (dialog, which) -> restoreSystemDefaultUid())
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreSystemDefaultUid() {
        if (!operationRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "NFC 操作正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        setActionButtonsEnabled(false);

        new Thread(() -> {
            SharedPreferences.Editor editor = simulationPrefs().edit();
            editor.putBoolean("request_active", false);
            editor.remove("target_uid");
            boolean prefsSaved = editor.commit();

            RootShell.Result result;
            if (!prefsSaved) {
                result = new RootShell.Result(false, -1,
                        "failed to persist UID default state", false);
            } else {
                result = RootShell.execute(
                        "set -e\n" +
                        "setprop persist.nfcuidsim.active '' || true\n" +
                        "setprop persist.nfcuidsim.uid '' || true\n" +
                        "svc nfc disable\n" +
                        "sleep 1\n" +
                        "svc nfc enable\n");
            }

            AppLogger.i("UID", "恢复系统默认 UID: prefsSaved=" + prefsSaved +
                    ", nfcRestart=" + result.success());
            operationRunning.set(false);
            runOnUiThread(() -> {
                setActionButtonsEnabled(true);
                if (result.success()) {
                    Toast.makeText(this,
                            "已清除 UID 模拟配置并重启 NFC；若 UID 仍不变，请运行 ST 原厂 NFCID1 解析",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            "恢复失败: " + result.describe(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "restore-system-nfc-uid").start();
    }

    private void setActionButtonsEnabled(boolean enabled) {
        if (restoreButton != null) restoreButton.setEnabled(enabled);
        if (scanConfigButton != null) scanConfigButton.setEnabled(enabled);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottomMargin);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
