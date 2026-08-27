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
        info.setText("“恢复系统默认 UID”不会写入新的 UID。它会先关闭并清除本 App 的 UID 测试配置，再清除旧版 persist.nfcuidsim 属性，最后重启 NFC 服务，让系统按手机原生配置重新初始化。");
        info.setTextSize(15);
        root.addView(info, lp(-1, -2, 20));

        Button openMain = new Button(this);
        openMain.setText("进入 NFC 门禁页面");
        openMain.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)));
        root.addView(openMain, lp(-1, dp(52), 12));

        restoreButton = new Button(this);
        restoreButton.setText("恢复系统默认 UID");
        restoreButton.setOnClickListener(v -> confirmRestore());
        root.addView(restoreButton, lp(-1, dp(52), 12));

        return root;
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
        restoreButton.setEnabled(false);

        new Thread(() -> {
            // Persist the disabled state BEFORE restarting NFC so the NFC process cannot
            // observe the previous target UID during its next initialization.
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
                restoreButton.setEnabled(true);
                if (result.success()) {
                    Toast.makeText(this,
                            "已清除 UID 模拟配置并重启 NFC，系统将使用手机默认值",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            "恢复失败: " + result.describe(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "restore-system-nfc-uid").start();
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
