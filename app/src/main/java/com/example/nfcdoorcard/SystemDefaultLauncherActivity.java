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
 * Launcher for NFC UID diagnostics and restoration helpers.
 */
public final class SystemDefaultLauncherActivity extends AppCompatActivity {
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);
    private Button restoreButton;
    private Button scanConfigButton;

    private SharedPreferences simulationPrefs() {
        return createDeviceProtectedStorageContext()
                .getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
    }

    /** Legacy builds stored sim_prefs in credential-protected storage. */
    private SharedPreferences legacySimulationPrefs() {
        return getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
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
        info.setText("一键定位功能只读检查系统/厂商 NFC 配置、ST HAL 文件 hash/时间、挂载状态、KernelSU/Magisk 模块覆盖和旧版属性，用来判断固定 UID 更像来自文件覆盖还是 NFC Controller 持久配置。不会写入 NFC。\n\n恢复系统默认 UID 会清除新旧版本保存的 UID 请求和旧版 persist.nfcuidsim 属性，再重启 NFC；不会写入新的 UID。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        Button openMain = new Button(this);
        openMain.setText("进入 NFC 门禁页面");
        openMain.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)));
        root.addView(openMain, lp(-1, dp(52), 12));

        scanConfigButton = new Button(this);
        scanConfigButton.setText("一键定位 UID 持久位置（只读）");
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
        Toast.makeText(this, "正在一键定位 UID 持久位置…", Toast.LENGTH_SHORT).show();

        final String deviceUid = simulationPrefs().getString("target_uid", null);
        final boolean deviceActive = simulationPrefs().getBoolean("request_active", false);
        final String legacyUid = legacySimulationPrefs().getString("target_uid", null);
        final boolean legacyActive = legacySimulationPrefs().getBoolean("request_active", false);

        new Thread(() -> {
            String command =
                    "set +e\n" +
                    "echo '=== 1. Hardware / legacy properties ==='\n" +
                    "echo -n 'ro.hardware.nfc='; getprop ro.hardware.nfc\n" +
                    "echo -n 'ro.boot.hardware='; getprop ro.boot.hardware\n" +
                    "echo -n 'persist.nfcuidsim.active='; getprop persist.nfcuidsim.active\n" +
                    "echo -n 'persist.nfcuidsim.uid='; getprop persist.nfcuidsim.uid\n" +
                    "echo\n" +
                    "echo '=== 2. NFC filesystem mounts ==='\n" +
                    "mount 2>/dev/null | grep -E ' /(system|vendor|odm|product)( |/)' | head -n 80\n" +
                    "echo\n" +
                    "echo '=== 3. ST HAL config files ==='\n" +
                    "STFILES=$(find /vendor /odm /system /product -type f 2>/dev/null | grep -Ei '/libnfc-hal-st[^/]*\\.(conf|cfg)$' | sort -u)\n" +
                    "if [ -z \"$STFILES\" ]; then echo '(none found)'; else echo \"$STFILES\"; fi\n" +
                    "echo\n" +
                    "echo '=== 4. ST config SHA-256 / timestamp ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  echo \"--- $f ---\"\n" +
                    "  sha256sum \"$f\" 2>/dev/null\n" +
                    "  stat -c 'mode=%A uid=%u gid=%g size=%s mtime=%y' \"$f\" 2>/dev/null\n" +
                    "done\n" +
                    "echo\n" +
                    "echo '=== 5. NFCID1 / Listen-A text candidates ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  m=$(grep -Ein -C 4 'LA_NFCID1|NFCID1|LISTEN|POLL|RF_PARAM|RF_CONFIG' \"$f\" 2>/dev/null | head -n 180)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n" +
                    "echo\n" +
                    "echo '=== 6. NCI 0x30..0x33 candidates ==='\n" +
                    "for f in $STFILES; do\n" +
                    "  m=$(grep -Ein -C 5 '(^|[^0-9A-Fa-f])(0[xX])?(30|31|32|33)([^0-9A-Fa-f]|$)' \"$f\" 2>/dev/null | head -n 220)\n" +
                    "  if [ -n \"$m\" ]; then echo \"--- $f ---\"; echo \"$m\"; fi\n" +
                    "done\n" +
                    "echo\n" +
                    "echo '=== 7. All NFC config files with NFCID1 text ==='\n" +
                    "grep -RinE 'LA_NFCID1|NFCID1' /vendor/etc /odm/etc /system/etc /product/etc 2>/dev/null | head -n 240\n" +
                    "echo\n" +
                    "echo '=== 8. KernelSU / Magisk NFC config overrides ==='\n" +
                    "OVERRIDES=$(find /data/adb/modules -type f 2>/dev/null | grep -Ei '(libnfc|/[^/]*nfc[^/]*\\.(conf|cfg)$)' | head -n 240)\n" +
                    "if [ -z \"$OVERRIDES\" ]; then echo '(none found)'; else echo \"$OVERRIDES\"; fi\n" +
                    "echo\n" +
                    "echo '=== 9. Overlay / module mount hints ==='\n" +
                    "mount 2>/dev/null | grep -Ei '(/data/adb|overlay|modules)' | grep -Ei '(nfc|vendor|odm|system|product)' | head -n 160\n" +
                    "echo\n" +
                    "echo '=== 10. NFC-related writable files under /data (names only) ==='\n" +
                    "find /data -xdev -type f 2>/dev/null | grep -Ei '/[^/]*(nfc|nfcee|nci)[^/]*$' | head -n 240\n" +
                    "echo\n" +
                    "echo '=== 11. Automatic hint ==='\n" +
                    "if [ -n \"$OVERRIDES\" ]; then\n" +
                    "  echo 'RESULT: Found KernelSU/Magisk NFC-related override files. File/module override is possible; inspect section 8 first.'\n" +
                    "else\n" +
                    "  echo 'RESULT: No NFC-related module override file was found. If the fixed UID remains after app state/properties are cleared and ROM config files are unchanged/read-only, Controller/NVRAM persistence becomes more likely.'\n" +
                    "fi\n";

            RootShell.Result result = RootShell.execute(command);
            String shellReport = result.output();
            if (shellReport == null || shellReport.isBlank()) {
                shellReport = "Root 扫描没有返回内容。\n" + result.describe();
            }

            StringBuilder report = new StringBuilder();
            report.append("=== App UID state ===\n");
            report.append("Device Protected active=").append(deviceActive)
                    .append(" uid=").append(deviceUid == null ? "(unset)" : deviceUid).append('\n');
            report.append("Legacy Credential active=").append(legacyActive)
                    .append(" uid=").append(legacyUid == null ? "(unset)" : legacyUid).append("\n\n");
            report.append(shellReport);

            String finalReport = report.toString();
            AppLogger.i("UID", "一键 UID 持久位置诊断完成: success=" + result.success());
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
                        .setTitle("UID 持久位置诊断结果")
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "diagnose-uid-persistence").start();
    }

    private void confirmRestore() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("恢复系统默认 UID")
                .setMessage("将清除当前版本和旧版本保存的 UID 请求、清除旧版 persist.nfcuidsim 属性，然后重启 NFC。不会写入任何新的 UID。")
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
            boolean devicePrefsSaved = simulationPrefs().edit()
                    .putBoolean("request_active", false)
                    .remove("target_uid")
                    .commit();
            boolean legacyPrefsSaved = legacySimulationPrefs().edit()
                    .putBoolean("request_active", false)
                    .remove("target_uid")
                    .commit();
            boolean prefsSaved = devicePrefsSaved && legacyPrefsSaved;

            RootShell.Result result;
            if (!prefsSaved) {
                result = new RootShell.Result(false, -1,
                        "failed to clear current/legacy UID state", false);
            } else {
                result = RootShell.execute(
                        "set -e\n" +
                        "setprop persist.nfcuidsim.active '' || true\n" +
                        "setprop persist.nfcuidsim.uid '' || true\n" +
                        "svc nfc disable\n" +
                        "sleep 1\n" +
                        "svc nfc enable\n");
            }

            AppLogger.i("UID", "恢复系统默认 UID: devicePrefs=" + devicePrefsSaved
                    + ", legacyPrefs=" + legacyPrefsSaved + ", nfcRestart=" + result.success());
            operationRunning.set(false);
            runOnUiThread(() -> {
                setActionButtonsEnabled(true);
                if (result.success()) {
                    Toast.makeText(this,
                            "已清除新旧 UID 请求和旧版属性，并重启 NFC；当前模块不会重新写入固定 UID",
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
