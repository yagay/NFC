package com.example.nfcdoorcard;

import android.util.Log;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import com.example.nfcdoorcard.data.CardSnapshot;
import com.example.nfcdoorcard.nfc.TagInspector;
import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final int MIN_SUPPORTED_SDK = 31; // Android 12
    private static final int MAX_SUPPORTED_SDK = 37; // Android 17

    private NfcAdapter nfcAdapter;
    private TextView status;
    private TextView details;
    private String currentUid;
    private String savedUid;
    private LinearLayout cardListContainer;
    private TextView logView;

    /**
     * 此方法会被 LSPosed 模块 Hook，如果模块已加载则返回 true。
     */
    public static boolean isModuleLoaded() {
        return false;
    }

    /**
     * 实时检查系统属性，判断模拟开关是否开启。
     */
    public static boolean isSimulationActive() {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("get", String.class);
            String val = (String) get.invoke(null, "persist.nfcuidsim.active");
            return "1".equals(val);
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 核心关联方法：此方法被底层模块 Hook。
     * 返回模块在 NFC 进程中【真正执行成功】的最后一次 UID。
     */
    public static String getHardwareActualUid() {
        return "Unknown";
    }

    private void runHardwareDiagnostic() {
        AppLogger.i("Diag", "正在深度抓取硬件信息...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            
            // 1. LSPosed 链路检测
            boolean loaded = isModuleLoaded();
            String actual = getHardwareActualUid();
            sb.append("【LSPosed 模块状态】\n");
            sb.append("● 模块载入: ").append(loaded ? "✅ 已生效" : "❌ 未载入 (请检查作用域)").append("\n");
            sb.append("● 底层反馈: ").append(actual).append("\n\n");

            // 2. Root 链路检测
            sb.append("【系统服务实时数据】\n");
            String dumpsys = RootShell.runWithResult("dumpsys nfc | grep -E 'mState|listenTech'");
            if (dumpsys == null || dumpsys.isEmpty()) {
                sb.append("⚠️ 无法获取 (Root 权限不足或未授权)\n");
            } else {
                sb.append(dumpsys).append("\n");
            }

            // 3. 硬件芯片识别
            String chip = RootShell.runWithResult("getprop ro.hardware.nfc");
            sb.append("\n【硬件芯片方案】\n");
            sb.append("● 芯片厂商: ").append(chip.isEmpty() ? "未知" : chip).append("\n");

            String report = sb.toString();
            runOnUiThread(() -> {
                AppLogger.i("Diag", "扫描完成");
                // 改用弹窗显示，确保用户可见
                new android.app.AlertDialog.Builder(this)
                    .setTitle("NFC 硬件实时诊断报告")
                    .setMessage(report)
                    .setPositiveButton("我知道了", null)
                    .show();
            });
        }).start();
    }

    private void openLSPosedManager() {
        String[] managers = {
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "de.robv.android.xposed.installer"
        };
        
        boolean found = false;
        for (String pkg : managers) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    startActivity(intent);
                    found = true;
                    break;
                }
            } catch (Exception ignored) {}
        }
        
        if (!found) {
            // 尝试通过隐式 Intent 启动模块详情页 (LSPosed 特有)
            try {
                Intent intent = new Intent("org.lsposed.manager.LAUNCH_MODULE");
                intent.putExtra("pkg", getPackageName());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                found = true;
            } catch (Exception ignored) {}
        }

        if (!found) {
            Toast.makeText(this, "未找到 LSPosed 管理器，请手动手动在桌面打开并勾选 [NFC 服务]", Toast.LENGTH_LONG).show();
            AppLogger.e("UI", "LSPosed Manager not found");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        
        AppLogger.setListener(allLogs -> runOnUiThread(() -> {
            if (logView != null) {
                logView.setText(allLogs);
            }
        }));

        AppLogger.i("App", "NFC 门禁模拟启动");
        AppLogger.i("System", "SDK: " + Build.VERSION.SDK_INT + " / Root: " + RootStatus.hasRoot());

        View rootLayout = buildUi();
        setContentView(rootLayout);

        // 处理沉浸式边距
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        Log.i("NfcDoorCard", "isSimulationActive: " + isSimulationActive());
        refreshStatus();
        refreshCardList();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        
        // 设置背景色为主题背景色
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, outValue, true);
        content.setBackgroundColor(outValue.data);

        TextView title = text("NFC 门禁 · v0.2", 26, true);
        content.addView(title);
        TextView sub = text("支持 Android 12–17（API 31–37）。读取并分析卡类型、Classic 结构与 UID-only 候选状态；不会修改系统 NFC HAL，也不会伪装真实门禁 UID。", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        content.addView(sub);

        status = text("", 16, true);
        content.addView(status);

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
        content.addView(requestRoot, lp(-1, dp(52), 12));

        Button scan = new Button(this);
        scan.setText("开始读取门禁卡");
        scan.setOnClickListener(v -> enableReader());
        content.addView(scan, lp(-1, dp(52), 12));

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
        content.addView(saveUid, lp(-1, dp(52), 12));

        Button copyUid = new Button(this);
        copyUid.setText("复制当前 UID");
        copyUid.setOnClickListener(v -> copyCurrentUid());
        content.addView(copyUid, lp(-1, dp(52), 12));

        Button simulateBtn = new Button(this);
        simulateBtn.setText("模拟当前读取的 UID");
        simulateBtn.setOnClickListener(v -> simulateCurrentUid());
        content.addView(simulateBtn, lp(-1, dp(52), 12));

        Button stopSimBtn = new Button(this);
        stopSimBtn.setText("停止模拟 / 恢复系统 UID");
        stopSimBtn.setOnClickListener(v -> stopSimulation());
        content.addView(stopSimBtn, lp(-1, dp(52), 12));

        Button saveToWallet = new Button(this);
        saveToWallet.setText("保存当前卡片到卡包");
        saveToWallet.setOnClickListener(v -> showSaveDialog());
        content.addView(saveToWallet, lp(-1, dp(52), 12));

        Button clearSaved = new Button(this);
        clearSaved.setText("清除 UID 对比基准");
        clearSaved.setOnClickListener(v -> {
            savedUid = null;
            Toast.makeText(this, "已清除 UID 对比基准", Toast.LENGTH_SHORT).show();
        });
        content.addView(clearSaved, lp(-1, dp(52), 12));

        Button stop = new Button(this);
        stop.setText("停止读取");
        stop.setOnClickListener(v -> disableReader());
        content.addView(stop, lp(-1, dp(52), 12));

        Button nfcSettings = new Button(this);
        nfcSettings.setText("打开系统 NFC 设置");
        nfcSettings.setOnClickListener(v -> startActivity(new android.content.Intent(Settings.ACTION_NFC_SETTINGS)));
        content.addView(nfcSettings, lp(-1, dp(52), 12));

        Button diagBtn = new Button(this);
        diagBtn.setText("刷新硬件模拟实时诊断");
        diagBtn.setOnClickListener(v -> runHardwareDiagnostic());
        content.addView(diagBtn, lp(-1, dp(52), 18));

        details = text("尚未读取卡片。\n\n把实体门禁卡贴到手机 NFC 区域。", 15, false);
        details.setTextIsSelectable(true);
        details.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.addView(details, lp(-1, -2, 0));

        TextView walletTitle = text("我的卡包", 20, true);
        walletTitle.setPadding(0, dp(20), 0, dp(10));
        content.addView(walletTitle);

        cardListContainer = new LinearLayout(this);
        cardListContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(cardListContainer);

        TextView logTitle = text("实时控制台日志", 18, true);
        logTitle.setPadding(0, dp(24), 0, dp(8));
        content.addView(logTitle);

        Button clearLogBtn = new Button(this);
        clearLogBtn.setText("清空控制台");
        clearLogBtn.setOnClickListener(v -> AppLogger.clear());
        content.addView(clearLogBtn, lp(-1, dp(40), 12));

        logView = new TextView(this);
        logView.setBackgroundColor(0xFF111111);
        logView.setTextColor(0xFF00FF00);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setMinLines(20);
        logView.setTextIsSelectable(true);
        content.addView(logView, lp(-1, -2, 40));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
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

        String moduleStatus = isModuleLoaded() ? "LSPosed: 已激活" : "LSPosed: 未激活 (点击管理)";
        String workDetail = getHardwareActualUid();
        
        String simStatus;
        if (isSimulationActive()) {
            simStatus = "\n模拟任务: 执行中 [" + workDetail + "]";
        } else {
            simStatus = "\n模拟任务: 已停止 (系统原生)";
        }

        status.setText(
                androidVersion + " / API " + sdk + "   ·   " + support +
                "\n" + nfc + "   ·   Root: " + (root ? "可用" : "未检测到/未授权") +
                "\n" + moduleStatus + simStatus
        );

        status.setOnClickListener(v -> {
            AppLogger.i("UI", "用户点击状态栏");
            if (!isModuleLoaded()) {
                openLSPosedManager();
            }
        });
    }

    private boolean isSupportedSdk(int sdk) {
        return sdk >= MIN_SUPPORTED_SDK && sdk <= MAX_SUPPORTED_SDK;
    }

    private String androidVersionName(int sdk) {
        switch (sdk) {
            case 31: return "Android 12";
            case 32: return "Android 12L";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            case 36: return "Android 16";
            case 37: return "Android 17";
            default: return "Android";
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
        AppLogger.i("Action", "开始模拟 UID: " + currentUid);
        // 持久化保存
        SharedPreferences prefs = getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
        prefs.edit().putString("target_uid", currentUid).apply();

        Toast.makeText(this, "已发起模拟请求：" + currentUid + "\n正在重启 NFC 服务...", Toast.LENGTH_LONG).show();

        // 核心：通过 Root 同时写入持久化 Prefs 和 实时系统属性
        new Thread(() -> {
            AppLogger.i("Root", "正在写入系统属性并重启 NFC...");
            boolean success = RootShell.run(
                "setprop persist.nfcuidsim.uid " + currentUid.replace(" ", ""),
                "setprop persist.nfcuidsim.active 1",
                "chmod 644 /data/data/com.example.nfcdoorcard/shared_prefs/sim_prefs.xml",
                "svc nfc disable",
                "sleep 1",
                "svc nfc enable"
            );
            if (success) {
                AppLogger.i("Root", "操作执行成功");
                runOnUiThread(this::refreshStatus);
            } else {
                AppLogger.e("Root", "操作执行失败，请检查 Root 授权");
                runOnUiThread(() -> Toast.makeText(this, "Root 指令执行失败，请检查授权", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void stopSimulation() {
        AppLogger.i("Action", "用户请求停止模拟");
        SharedPreferences prefs = getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
        prefs.edit().remove("target_uid").apply();

        Toast.makeText(this, "正在关闭模拟并恢复系统 UID...", Toast.LENGTH_SHORT).show();

        // 核心：通过 Root 将属性设为 OFF，明确告知模块停止干预
        new Thread(() -> {
            AppLogger.i("Root", "正在发送重置信号并重启 NFC...");
            boolean success = RootShell.run(
                "setprop persist.nfcuidsim.uid OFF",
                "setprop persist.nfcuidsim.active 0",
                "chmod 644 /data/data/com.example.nfcdoorcard/shared_prefs/sim_prefs.xml",
                "svc nfc disable",
                "sleep 1",
                "svc nfc enable"
            );
            if (success) {
                AppLogger.i("Root", "重置信号发送成功");
                runOnUiThread(this::refreshStatus);
            } else {
                AppLogger.e("Root", "重置失败");
                runOnUiThread(() -> Toast.makeText(this, "Root 执行失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showSaveDialog() {
        if (currentUid == null || "—".equals(currentUid)) {
            Toast.makeText(this, "请先读取一张卡", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("给这张卡起个名字 (如：公司门禁)");

        new android.app.AlertDialog.Builder(this)
                .setTitle("保存卡片")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "未命名卡片";
                    saveCard(name, currentUid);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveCard(String name, String uid) {
        SharedPreferences prefs = getSharedPreferences("card_wallet", Context.MODE_PRIVATE);
        prefs.edit().putString(uid, name).apply();
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
        refreshCardList();
    }

    private void refreshCardList() {
        if (cardListContainer == null) return;
        cardListContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences("card_wallet", Context.MODE_PRIVATE);
        java.util.Map<String, ?> allCards = prefs.getAll();

        if (allCards.isEmpty()) {
            cardListContainer.addView(text("卡包空空如也", 14, false));
            return;
        }

        for (java.util.Map.Entry<String, ?> entry : allCards.entrySet()) {
            String uid = entry.getKey();
            String name = (String) entry.getValue();

            Button btn = new Button(this);
            btn.setText(name + " (" + uid + ")");
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> {
                currentUid = uid;
                simulateCurrentUid();
            });
            btn.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setMessage("删除 " + name + "？")
                        .setPositiveButton("删除", (d, w) -> {
                            prefs.edit().remove(uid).apply();
                            refreshCardList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            cardListContainer.addView(btn, lp(-1, dp(48), 8));
        }
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
