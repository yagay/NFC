package com.example.nfcdoorcard;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import java.util.concurrent.atomic.AtomicBoolean;

public final class LegacyHardcodedUidTraceActivity extends AppCompatActivity {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Button traceButton;
    private Button runtimeButton;
    private Button toolsButton;
    private String lastReport;

    @Override protected void onCreate(Bundle savedInstanceState) {
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
        title.setText("NFC UID 诊断中心");
        title.setTextSize(23);
        root.addView(title, lp(-1, -2, 14));

        TextView info = new TextView(this);
        info.setText("只读检查旧 UID 遗留、当前 NFC 配置与运行时调用链。不会写入 NFC 控制器，也不会修改或删除系统 NFC 文件。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        traceButton = new Button(this);
        traceButton.setText("一键追踪旧写死 UID 遗留");
        traceButton.setOnClickListener(v -> runTrace());
        root.addView(traceButton, lp(-1, dp(54), 12));

        runtimeButton = new Button(this);
        runtimeButton.setText("抓取 NFC 运行时模拟链路");
        runtimeButton.setOnClickListener(v -> runRuntimeTrace());
        root.addView(runtimeButton, lp(-1, dp(54), 12));

        Button shareButton = new Button(this);
        shareButton.setText("分享最近一次诊断报告");
        shareButton.setOnClickListener(v -> shareLastReport());
        root.addView(shareButton, lp(-1, dp(52), 12));

        toolsButton = new Button(this);
        toolsButton.setText("进入现有 NFC UID 工具");
        toolsButton.setOnClickListener(v -> startActivity(new Intent(this, SystemDefaultLauncherActivity.class)));
        root.addView(toolsButton, lp(-1, dp(52), 12));
        return root;
    }

    private void runTrace() {
        runDiagnostic("正在只读追踪旧写死 UID…", "trace-legacy-uid", buildLegacyTraceCommand(), "旧写死 UID 遗留追踪结果");
    }

    private void runRuntimeTrace() {
        runDiagnostic("正在抓取 NFC 运行时链路…", "trace-nfc-runtime", buildRuntimeTraceCommand(), "NFC 运行时诊断结果");
    }

    private void runDiagnostic(String toastText, String threadName, String command, String dialogTitle) {
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(this, "诊断正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        setButtons(false);
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            RootShell.Result result = RootShell.execute(command);
            String report = result.output();
            if (report == null || report.isBlank()) report = "Root 诊断没有返回内容。\n" + result.describe();
            report = "Command success=" + result.success() + "\n" + result.describe() + "\n\n" + report;
            lastReport = report;
            String finalReport = report;
            AppLogger.i("UID", threadName + " complete: success=" + result.success());
            running.set(false);
            runOnUiThread(() -> {
                setButtons(true);
                showReport(dialogTitle, finalReport);
            });
        }, threadName).start();
    }

    private String buildLegacyTraceCommand() {
        return "set +e\n" +
                "echo '=== 1. CardEmulator backup inventory ==='\n" +
                "find /data/nfc/CardEmulator/backup -type f 2>/dev/null | sort | head -n 260\n" +
                "echo\n" +
                "echo '=== 2. Exact AABBCCDD remnants ==='\n" +
                "grep -RInaE 'AABBCCDD|AA[ :_-]*BB[ :_-]*CC[ :_-]*DD' /data/nfc/CardEmulator /data/nfc/libnfc-nxpTransit.conf 2>/dev/null | head -n 220\n" +
                "echo\n" +
                "echo '=== 3. Backup LA_NFCID1 / core config ==='\n" +
                "grep -RInaE -C 8 'LA_NFCID1|NFCID1|NXP_CORE_CONF' /data/nfc/CardEmulator/backup 2>/dev/null | head -n 320\n" +
                "echo\n" +
                "echo '=== 4. Current NFC config clues ==='\n" +
                "for f in /data/nfc/libnfc-nxpTransit.conf /data/vendor/nfc/libnfc-mtp-SN220.conf /data/vendor/nfc/libnfc-nci.conf /odm/etc/nfc/libnfc-mtp-SN220.conf /vendor/etc/libnfc-hal-st.conf; do\n" +
                " [ -f \"$f\" ] || continue; echo \"--- $f ---\"; stat -c 'size=%s mtime=%y' \"$f\" 2>/dev/null; sha256sum \"$f\" 2>/dev/null; grep -Ein -C 8 'LA_NFCID1|NFCID1|NXP_CORE_CONF|AABBCCDD' \"$f\" 2>/dev/null | head -n 120; done\n" +
                "echo\n" +
                "echo '=== 5. Backup/current hashes ==='\n" +
                "for f in /data/nfc/CardEmulator/backup/data/vendor/nfc/libnfc-mtp-SN220.conf.backup /data/nfc/CardEmulator/backup/data/vendor/nfc/libnfc-nci.conf.backup /data/nfc/CardEmulator/backup/vendor/etc/libnfc-hal-st.conf.backup; do [ -f \"$f\" ] && sha256sum \"$f\"; done\n" +
                "for f in /data/vendor/nfc/libnfc-mtp-SN220.conf /data/vendor/nfc/libnfc-nci.conf /vendor/etc/libnfc-hal-st.conf; do [ -f \"$f\" ] && sha256sum \"$f\"; done\n" +
                "echo\n" +
                "echo '=== 6. Assessment ==='\n" +
                "if grep -RqiE 'AABBCCDD|AA[ :_-]*BB[ :_-]*CC[ :_-]*DD' /data/nfc/CardEmulator /data/nfc/libnfc-nxpTransit.conf 2>/dev/null; then echo 'RESULT: exact legacy AABBCCDD text found in NFC data.'; else echo 'RESULT: no exact AABBCCDD text found. Inspect LA_NFCID1/core-config differences; if absent, controller/NVM persistence remains plausible.'; fi\n";
    }

    private String buildRuntimeTraceCommand() {
        return "set +e\n" +
                "echo '=== NFC UID runtime diagnostic v2 ==='\n" +
                "date\n" +
                "echo\n" +
                "echo '=== 1. LSPosed / app / framework NFC runtime logs ==='\n" +
                "logcat -b all -d -v threadtime -t 6000 2>/dev/null | grep -iE 'NfcUIDSim|NfcDoorHCE|UidConfigProvider|NfcService|NativeNfcManager|DeviceHost|changeRfParams|changeRfParamsByConfig|doWriteData|nativeSendRawVendorCmd|setDiscoveryTech|restartRfDiscovery|doRestartRFDiscovery' | tail -n 650\n" +
                "echo\n" +
                "echo '=== 2. HAL/vendor crash clues ==='\n" +
                "logcat -b all -d -v threadtime -t 6000 2>/dev/null | grep -iE 'libnfc|sn100|sn110|sn220|st21|nfc.*fatal|nfc.*abort|com.android.nfc.*crash|SIGABRT|WatchDogThread' | tail -n 360\n" +
                "echo\n" +
                "echo '=== 3. Android / device ==='\n" +
                "getprop ro.product.manufacturer\n" +
                "getprop ro.product.model\n" +
                "getprop ro.build.version.release\n" +
                "getprop ro.build.version.sdk\n" +
                "getprop ro.hardware.nfc\n" +
                "getprop ro.boot.hardware\n" +
                "echo\n" +
                "echo '=== 4. NFC processes/services ==='\n" +
                "ps -A 2>/dev/null | grep -iE 'nfc|ese' | head -n 80\n" +
                "service list 2>/dev/null | grep -i nfc | head -n 80\n" +
                "echo\n" +
                "echo '=== 5. NFC service summary ==='\n" +
                "dumpsys nfc 2>&1 | grep -E 'mState=|listenTech=|pollTech=|mTechMask:|mEnableLPD:|mEnableReader:|mEnableHostRouting:|mIsSecureNfcEnabled=|mIsReaderOptionEnabled=|mIsObserveMode' | head -n 100\n" +
                "echo\n" +
                "echo '=== 6. Relevant properties ==='\n" +
                "getprop 2>/dev/null | grep -iE '(^|\\[)(ro\\.hardware\\.nfc|ro\\.nfc|nfc\\.|persist\\.nfc|persist\\.nfcuidsim|oplus.*nfc|sn220|st21)' | head -n 160\n" +
                "echo\n" +
                "echo '=== 7. Key config files only ==='\n" +
                "for f in /data/vendor/nfc/libnfc-mtp-SN220.conf /data/vendor/nfc/libnfc-nci.conf /data/vendor/nfc/libnfc_accesscard_config.conf /data/vendor/nfc/libnfc_default_config.conf /data/vendor/nfc/libnfc_tap_config.conf /data/nfc/libnfc-nxpTransit.conf /vendor/etc/libnfc-hal-st.conf; do\n" +
                " [ -f \"$f\" ] || continue; echo \"--- $f ---\"; stat -c 'size=%s mtime=%y' \"$f\" 2>/dev/null; grep -Ein 'LA_NFCID1|NFCID1|NXP_CORE_CONF|CORE_CONF_PROP|RF_CONF' \"$f\" 2>/dev/null | head -n 45; done\n" +
                "echo\n" +
                "echo '=== 8. CardEmulator backup presence ==='\n" +
                "find /data/nfc/CardEmulator/backup -maxdepth 5 -type f 2>/dev/null | grep -iE 'libnfc|nfc.*conf' | head -n 100\n" +
                "echo\n" +
                "echo '=== End ==='\n";
    }

    private void showReport(String title, String report) {
        TextView view = new TextView(this);
        view.setText(report);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton("分享", (d, w) -> shareReport(report))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void shareLastReport() {
        if (lastReport == null || lastReport.isBlank()) {
            Toast.makeText(this, "还没有诊断报告，请先执行一次诊断", Toast.LENGTH_SHORT).show();
            return;
        }
        shareReport(lastReport);
    }

    private void shareReport(String report) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "NFC runtime diagnostic");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, "分享 NFC 诊断报告"));
    }

    private void setButtons(boolean enabled) {
        if (traceButton != null) traceButton.setEnabled(enabled);
        if (runtimeButton != null) runtimeButton.setEnabled(enabled);
        if (toolsButton != null) toolsButton.setEnabled(enabled);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottomMargin);
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
