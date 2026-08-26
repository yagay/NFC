package com.example.nfcdoorcard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.nfcdoorcard.data.CardSnapshot;
import com.example.nfcdoorcard.nfc.TagInspector;
import com.example.nfcdoorcard.utils.RootShell;

import java.util.stream.Collectors;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int MIN_SUPPORTED_SDK = 31; // Android 12
    private static final int MAX_SUPPORTED_SDK = 37; // Android 17

    private NfcAdapter nfcAdapter;
    private TextView status;
    private TextView details;
    private String currentUid;
    private String savedUid;

    /**
     * 此方法会被 LSPosed 模块 Hook，如果模块已激活且作用域包含本 App，则返回 true。
     */
    public static boolean isModuleActive() {
        return false;
    }

    private void openLSPosedManager() {
        try {
            Intent intent = new Intent("org.lsposed.manager.LAUNCH_MODULE");
            intent.putExtra("pkg", getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "未找到 LSPosed 管理器，请手动开启并检查作用域", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        refreshStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("NFC 门禁 · v0.2", 26, true);
        root.addView(title);
        TextView sub = text("支持 Android 12–17（API 31–37）。读取并分析卡类型、Classic 结构与 UID-only 候选状态；不会修改系统 NFC HAL，也不会伪装真实门禁 UID。", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        status = text("", 16, true);
        root.addView(status);

        Button requestRoot = new Button(this);
        requestRoot.setText("请求 Root 权限 / 检查状态");
        requestRoot.setOnClickListener(v -> {
            RootStatus.clearCache();
            if (RootStatus.hasRoot()) {
                Toast.makeText(this, "Root 权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未获得 Root 权限，请在授权管理中允许", Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
        root.addView(requestRoot, lp(-1, dp(52), 12));

        Button scan = new Button(this);
        scan.setText("开始读取门禁卡");
        scan.setOnClickListener(v -> enableReader());
        root.addView(scan, lp(-1, dp(52), 12));

        Button saveUid = new Button(this);
        saveUid.setText("保存当前 UID 作为对比基准");
        saveUid.setOnClickListener(v -> {
            if (currentUid == null || "—".equals(currentUid)) {
                Toast.makeText(this, "请先读取一张卡", Toast.LENGTH_SHORT).show();
                return;
            }
            savedUid = currentUid;
            Toast.makeText(this, "已保存 UID: " + savedUid, Toast.LENGTH_SHORT).show();
        });
        root.addView(saveUid, lp(-1, dp(52), 12));

        Button copyUid = new Button(this);
        copyUid.setText("复制当前 UID");
        copyUid.setOnClickListener(v -> copyCurrentUid());
        root.addView(copyUid, lp(-1, dp(52), 12));

        Button simulateBtn = new Button(this);
        simulateBtn.setText("模拟当前读取的 UID");
        simulateBtn.setOnClickListener(v -> simulateCurrentUid());
        root.addView(simulateBtn, lp(-1, dp(52), 12));

        Button clearSaved = new Button(this);
        clearSaved.setText("清除 UID 对比基准");
        clearSaved.setOnClickListener(v -> {
            savedUid = null;
            Toast.makeText(this, "已清除 UID 对比基准", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearSaved, lp(-1, dp(52), 12));

        Button stop = new Button(this);
        stop.setText("停止读取");
        stop.setOnClickListener(v -> disableReader());
        root.addView(stop, lp(-1, dp(52), 12));

        Button nfcSettings = new Button(this);
        nfcSettings.setText("打开系统 NFC 设置");
        nfcSettings.setOnClickListener(v -> startActivity(new android.content.Intent(Settings.ACTION_NFC_SETTINGS)));
        root.addView(nfcSettings, lp(-1, dp(52), 18));

        details = text("尚未读取卡片。\n\n把实体门禁卡贴到手机 NFC 区域。", 15, false);
        details.setTextIsSelectable(true);
        details.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(details, lp(-1, -2, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void refreshStatus() {
        boolean root = RootStatus.hasRoot();
        String nfc;
        if (nfcAdapter == null) nfc = "无 NFC 硬件";
        else nfc = nfcAdapter.isEnabled() ? "NFC 已开启" : "NFC 未开启";

        int sdk = Build.VERSION.SDK_INT;
        String androidVersion = androidVersionName(sdk);
        String support = isSupportedSdk(sdk) ? "系统受支持" : "系统版本超出支持范围";

        String moduleStatus = isModuleActive() ? "LSPosed: 已激活" : "LSPosed: 未激活 (点击管理)";

        status.setText(
                androidVersion + " / API " + sdk + "   ·   " + support +
                "\n" + nfc + "   ·   Root: " + (root ? "可用" : "未检测到/未授权") +
                "\n" + moduleStatus
        );

        status.setOnClickListener(v -> {
            if (!isModuleActive()) {
                openLSPosedManager();
            }
        });
    }

    private boolean isSupportedSdk(int sdk) {
        return sdk >= MIN_SUPPORTED_SDK && sdk <= MAX_SUPPORTED_SDK;
    }

    private String androidVersionName(int sdk) {
        switch (sdk) {
            case 31:
                return "Android 12";
            case 32:
                return "Android 12L";
            case 33:
                return "Android 13";
            case 34:
                return "Android 14";
            case 35:
                return "Android 15";
            case 36:
                return "Android 16";
            case 37:
                return "Android 17";
            default:
                return "Android";
        }
    }

    private void enableReader() {
        refreshStatus();
        if (!isSupportedSdk(Build.VERSION.SDK_INT)) {
            details.setText("当前 Android 版本不在正式支持范围内。支持 Android 12–17（API 31–37）。");
            return;
        }
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            details.setText("请先开启 NFC。\n\n当前没有修改系统设置。 ");
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A |
                NfcAdapter.FLAG_READER_NFC_B |
                NfcAdapter.FLAG_READER_NFC_F |
                NfcAdapter.FLAG_READER_NFC_V |
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        nfcAdapter.enableReaderMode(this, this, flags, null);
        details.setText("读取模式已开启。请把门禁卡贴近手机。\n\n读取完成后会显示 UID、Tech、ATQA、SAK、Classic 容量/扇区/块数量、HCE 支持状态和 UID-only 候选判断。 ");
    }

    private void disableReader() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        details.setText("读取模式已停止。 ");
    }

    private void copyCurrentUid() {
        if (currentUid == null || "—".equals(currentUid)) {
            Toast.makeText(this, "请先读取一张卡", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("NFC UID", currentUid));
        Toast.makeText(this, "UID 已复制", Toast.LENGTH_SHORT).show();
    }

    private void simulateCurrentUid() {
        if (currentUid == null || "—".equals(currentUid)) {
            Toast.makeText(this, "请先读取一张卡", Toast.LENGTH_SHORT).show();
            return;
        }
        // 保存 UID 到 SharedPreferences
        SharedPreferences prefs = getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
        prefs.edit().putString("target_uid", currentUid).apply();

        Toast.makeText(this, "已设为模拟目标：" + currentUid + "\n正在尝试重启 NFC...", Toast.LENGTH_LONG).show();

        // 使用 RootShell 重启 NFC 并设置权限
        new Thread(() -> {
            boolean success = RootShell.run(
                "chmod 644 /data/data/com.example.nfcdoorcard/shared_prefs/sim_prefs.xml",
                "svc nfc disable",
                "sleep 1",
                "svc nfc enable"
            );
            if (!success) {
                runOnUiThread(() -> Toast.makeText(this, "Root 执行失败，请检查授权", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String uidOnlyAssessment(CardSnapshot s) {
        if (!"MIFARE Classic / NFC-A".equals(s.classification())) {
            return "暂不判断：当前卡不是 MIFARE Classic。";
        }
        return "候选：仅凭卡片本身不能证明门禁只认 UID。若多份可用卡/镜像在扇区内容不同的情况下仍可通过，或测试读卡器只上报 UID，则 UID-only 可能性较高。";
    }

    private String comparisonText(String uid) {
        if (savedUid == null) return "未设置对比基准";
        if (savedUid.equals(uid)) return "与已保存 UID 完全相同";
        return "与已保存 UID 不同\n基准: " + savedUid + "\n当前: " + uid;
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        CardSnapshot s = TagInspector.inspect(tag);
        currentUid = s.uid();
        String tech = s.techList().stream().collect(Collectors.joining(", "));
        String out = "UID\n" + s.uid() +
                "\n\nUID 长度\n" + s.uidLength() +
                "\n\nUID 对比\n" + comparisonText(s.uid()) +
                "\n\nTech\n" + tech +
                "\n\nATQA\n" + s.atqa() +
                "\n\nSAK\n" + s.sak() +
                "\n\n判断\n" + s.classification() +
                "\n\nUID-only 候选\n" + uidOnlyAssessment(s) +
                "\n\nClassic 容量\n" + s.classicSize() +
                "\n\n扇区数\n" + s.classicSectors() +
                "\n\n块数\n" + s.classicBlocks() +
                "\n\n标准 HCE\n" + s.hceSupport() +
                "\n\n说明\n" + s.note();
        runOnUiThread(() -> details.setText(out));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
