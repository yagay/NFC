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
 * an explicit way to return NFC UID handling to the phone/system defaults.
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
        info.setText("“恢复系统默认 UID”不会写入新的 UID。若恢复后 UID 仍不变，可先使用只读扫描功能查找手机原厂 NFC 配置中的 NFCID1 / LA_NFCID1 候选值。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        Button openMain = new Button(this);
        openMain.setText("进入 NFC 门禁页面");
        openMain.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)));
        root.addView(openMain, lp(-1, dp(52), 12));

        scanConfigButton = new Button(this);
        scanConfigButton.setText("扫描手机原厂 NFC UID 配置（只读）");
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
        Toast.makeText(this, "正在只读扫描原厂 NFC 配置…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String command =
                    "set +e\n" +
                    "echo '=== NFC hardware properties ==='\n" +
                    "getprop ro.hardware.nfc\n" +
                    "getprop ro.boot.hardware\n" +
                    "echo\n" +
                    "echo '=== NFC config files ==='\n" +
                    "FILES=$(find /system /vendor /odm /product -type f 2>/dev/null | grep -Ei '/[^/]*nfc[^/]*\\.(conf|cfg)$' | sort -u)\n" +
                    "echo \"$FILES\"\n" +
                    "echo\n" +
                    "echo '=== Candidate NFCID1 / NCI config lines ==='\n" +
                    "for f in $FILES; do\n" +
                    "  m=$(grep -Ein 'LA_NFCID1|NFCID1|NXP_CORE_CONF|0[xX]?33' \"$f\" 2>/dev/null | head -n 80)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n";

            RootShell.Result result = RootShell.execute(command);
            String report = result.output();
            if (report == null || report.isBlank()) report = "未找到候选配置，或 Root 无法读取这些目录。\n" + result.describe();

            String finalReport = report;
            AppLogger.i("UID", "原厂 NFC 配置只读扫描完成: success=" + result.success());
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
                        .setTitle("原厂 NFC 配置扫描结果")
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "scan-oem-nfc-config").start();
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
                            "已清除 UID 模拟配置并重启 NFC；若 UID 仍不变，请先运行原厂配置扫描",
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
