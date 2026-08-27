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
    private Button toolsButton;

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
        title.setText("4cfe2f 写死 UID 遗留追踪");
        title.setTextSize(23);
        root.addView(title, lp(-1, -2, 14));

        TextView info = new TextView(this);
        info.setText("只读检查 /data/nfc/CardEmulator/backup 与当前 NFC 配置，专门查 AABBCCDD、LA_NFCID1、NFCID1 和 NXP_CORE_CONF。不会写入 NFC，也不会删除文件。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        traceButton = new Button(this);
        traceButton.setText("一键追踪 4cfe2f 写死 UID 遗留");
        traceButton.setOnClickListener(v -> runTrace());
        root.addView(traceButton, lp(-1, dp(54), 12));

        toolsButton = new Button(this);
        toolsButton.setText("进入现有 NFC UID 工具");
        toolsButton.setOnClickListener(v -> startActivity(new Intent(this, SystemDefaultLauncherActivity.class)));
        root.addView(toolsButton, lp(-1, dp(52), 12));
        return root;
    }

    private void runTrace() {
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(this, "诊断正在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        setButtons(false);
        Toast.makeText(this, "正在只读追踪旧写死 UID…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String command =
                    "set +e\n" +
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

            RootShell.Result result = RootShell.execute(command);
            String report = result.output();
            if (report == null || report.isBlank()) report = "Root 诊断没有返回内容。\n" + result.describe();
            String finalReport = report;
            AppLogger.i("UID", "4cfe2f legacy UID trace complete: success=" + result.success());
            running.set(false);
            runOnUiThread(() -> { setButtons(true); showReport(finalReport); });
        }, "trace-4cfe2f-uid").start();
    }

    private void showReport(String report) {
        TextView view = new TextView(this);
        view.setText(report);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new android.app.AlertDialog.Builder(this)
                .setTitle("4cfe2f 写死 UID 遗留追踪结果")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void setButtons(boolean enabled) {
        if (traceButton != null) traceButton.setEnabled(enabled);
        if (toolsButton != null) toolsButton.setEnabled(enabled);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottomMargin);
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
