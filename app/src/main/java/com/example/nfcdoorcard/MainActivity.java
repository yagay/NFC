package com.example.nfcdoorcard;

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

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.nfcdoorcard.data.CardSnapshot;
import com.example.nfcdoorcard.nfc.TagInspector;
import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final int MIN_SUPPORTED_SDK = 31;
    private static final int MAX_SUPPORTED_SDK = 37;

    private NfcAdapter nfcAdapter;
    private TextView status;
    private TextView details;
    private TextView logView;
    private LinearLayout cardListContainer;
    private String currentUid;
    private String savedUid;

    public static boolean isSimulationRequestActive() {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("get", String.class);
            String val = (String) get.invoke(null, "persist.nfcuidsim.active");
            return "1".equals(val);
        } catch (Exception ignored) {
            return false;
        }
    }

    private SharedPreferences simulationPrefs() {
        return createDeviceProtectedStorageContext()
                .getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
    }

    private void runHardwareDiagnostic() {
        AppLogger.i("Diag", "正在抓取 NFC 诊断信息...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();

            sb.append("【LSPosed / libxposed】\n");
            boolean connected = NfcDoorApplication.isFrameworkConnected();
            boolean scoped = NfcDoorApplication.isNfcScopeEnabled();
            sb.append("● 框架连接: ").append(connected ? "✅ 已连接" : "❌ 未连接").append("\n");
            sb.append("● NFC 作用域: ").append(scoped ? "✅ com.android.nfc 已启用" : "❌ 未启用").append("\n");
            sb.append("● 框架信息: ").append(NfcDoorApplication.getFrameworkSummary()).append("\n");
            sb.append("● 底层 UID 写入: 未启用（当前模块仅诊断 doInitialize）\n\n");

            sb.append("【测试请求】\n");
            String target = simulationPrefs().getString("target_uid", null);
            sb.append("● 请求状态: ").append(isSimulationRequestActive() ? "已开启" : "已停止").append("\n");
            sb.append("● 目标 UID: ").append(target == null ? "未设置" : target).append("\n\n");

            sb.append("【系统服务实时数据】\n");
            RootShell.Result nfcDump = RootShell.execute("dumpsys nfc | grep -E 'mState|listenTech'");
            if (nfcDump.success() && !nfcDump.output().isEmpty()) {
                sb.append(nfcDump.output()).append("\n");
            } else {
                sb.append("⚠️ 获取失败: ").append(nfcDump.output()).append("\n");
            }

            RootShell.Result chipResult = RootShell.execute("getprop ro.hardware.nfc");
            sb.append("\n【硬件芯片方案】\n");
            sb.append("● ro.hardware.nfc: ")
                    .append(chipResult.success() && !chipResult.output().isEmpty() ? chipResult.output() : "未知")
                    .append("\n");

            String report = sb.toString();
            runOnUiThread(() -> {
                AppLogger.i("Diag", "扫描完成");
                new android.app.AlertDialog.Builder(this)
                        .setTitle("NFC 实时诊断报告")
                        .setMessage(report)
                        .setPositiveButton("我知道了", null)
                        .show();
            });
        }, "nfc-diagnostic").start();
    }

    private void openLSPosedManager() {
        String[] managers = {
                "org.lsposed.manager",
                "io.github.lsposed.manager",
                "org.meowcat.edxposed.manager",
                "de.robv.android.xposed.installer"
        };

        for (String pkg : managers) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            Intent intent = new Intent("org.lsposed.manager.LAUNCH_MODULE");
            intent.putExtra("pkg", getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }

        Toast.makeText(this, "未找到 LSPosed 管理器，请手动打开并检查 NFC 服务作用域", Toast.LENGTH_LONG).show();
        AppLogger.e("UI", "LSPosed Manager not found");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        View rootLayout = buildUi();
        setContentView(rootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        AppLogger.i("App", "NFC 门禁诊断启动");
        AppLogger.i("System", "SDK: " + Build.VERSION.SDK_INT);
        refreshStatus();
        refreshCardList();
        refreshRootStatusAsync(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.setListener(allLogs -> runOnUiThread(() -> {
            if (logView != null) logView.setText(allLogs);
        }));
        refreshStatus();
    }

    @Override
    protected void onStop() {
        AppLogger.setListener(null);
        super.onStop();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, outValue, true);
        content.setBackgroundColor(outValue.data);

        TextView title = text("NFC 门禁 · v" + BuildConfig.VERSION_NAME, 26, true);
        content.addView(title);
        TextView sub = text("支持 Android 12–17（API 31–37）。读取和诊断 NFC 卡片，并验证 HCE/LSPosed 链路；当前版本不会写入 NFC 控制器固定 UID。", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        content.addView(sub);

        status = text("", 16, true);
        content.addView(status);

        Button requestRoot = new Button(this);
        requestRoot.setText("请求 Root 权限 / 检查状态");
        requestRoot.setOnClickListener(v -> refreshRootStatusAsync(true));
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
        simulateBtn.setText("设置当前 UID 测试请求");
        simulateBtn.setOnClickListener(v -> simulateCurrentUid());
        content.addView(simulateBtn, lp(-1, dp(52), 12));

        Button stopSimBtn = new Button(this);
        stopSimBtn.setText("停止 UID 测试请求");
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
        nfcSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        content.addView(nfcSettings, lp(-1, dp(52), 12));

        Button diagBtn = new Button(this);
        diagBtn.setText("刷新 NFC / LSPosed 实时诊断");
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

    protected void refreshStatus() {
        Boolean root = RootStatus.getCachedResult();
        String rootText = root == null ? "检测中" : (root ? "可用" : "未检测到/未授权");

        String nfc;
        if (nfcAdapter == null) nfc = "无 NFC 硬件";
        else nfc = nfcAdapter.isEnabled() ? "NFC 已开启" : "NFC 未开启";

        int sdk = Build.VERSION.SDK_INT;
        String support = isSupportedSdk(sdk) ? "系统受支持" : "系统版本超出支持范围";

        String moduleStatus;
        if (!NfcDoorApplication.isFrameworkConnected()) {
            moduleStatus = "LSPosed: 未连接框架";
        } else if (!NfcDoorApplication.isNfcScopeEnabled()) {
            moduleStatus = "LSPosed: 已连接 · NFC 服务未在作用域";
        } else {
            moduleStatus = "LSPosed: 已连接 · NFC 服务作用域已启用";
        }

        String simStatus;
        if (isSimulationRequestActive()) {
            String uid = simulationPrefs().getString("target_uid", "未设置");
            simStatus = "\nUID 测试请求: 已开启 [" + uid + "] · 底层固定 UID 写入未启用";
        } else {
            simStatus = "\nUID 测试请求: 已停止";
        }

        status.setText(
                androidVersionName(sdk) + " / API " + sdk + "   ·   " + support +
                        "\n" + nfc + "   ·   Root: " + rootText +
                        "\n" + moduleStatus + simStatus
        );

        status.setOnClickListener(v -> {
            AppLogger.i("UI", "用户点击状态栏");
            if (!NfcDoorApplication.isFrameworkConnected() || !NfcDoorApplication.isNfcScopeEnabled()) {
                openLSPosedManager();
            }
        });
    }

    private void refreshRootStatusAsync(boolean showToast) {
        RootStatus.clearCache();
        refreshStatus();
        new Thread(() -> {
            boolean ok = RootStatus.hasRoot();
            runOnUiThread(() -> {
                refreshStatus();
                if (showToast) {
                    Toast.makeText(this, ok ? "Root 权限已获取" : "未获得 Root 权限，请在授权管理中允许",
                            ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                }
            });
        }, "root-check").start();
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
            details.setText("请先开启 NFC。");
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A |
                NfcAdapter.FLAG_READER_NFC_B |
                NfcAdapter.FLAG_READER_NFC_F |
                NfcAdapter.FLAG_READER_NFC_V |
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        nfcAdapter.enableReaderMode(this, this, flags, null);
        details.setText("读取模式已开启。请把门禁卡贴近手机。");
    }

    private void disableReader() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        details.setText("读取模式已停止。");
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

        String safeUid = currentUid.replaceAll("[^0-9A-Fa-f:]", "");
        simulationPrefs().edit().putString("target_uid", safeUid).apply();
        AppLogger.i("Action", "设置 UID 测试请求: " + safeUid);
        Toast.makeText(this, "已设置 UID 测试请求，正在重启 NFC 服务进行链路诊断", Toast.LENGTH_LONG).show();

        new Thread(() -> {
            RootShell.Result result = RootShell.execute(
                    "set -e\n" +
                    "setprop persist.nfcuidsim.uid '" + safeUid + "'\n" +
                    "setprop persist.nfcuidsim.active 1\n" +
                    "test \"$(getprop persist.nfcuidsim.active)\" = \"1\"\n" +
                    "svc nfc disable\n" +
                    "sleep 1\n" +
                    "svc nfc enable\n"
            );
            if (result.success()) {
                AppLogger.i("Root", "测试请求已写入并完成 NFC 重启");
            } else {
                AppLogger.e("Root", "测试请求失败: " + result.output());
                simulationPrefs().edit().remove("target_uid").apply();
            }
            runOnUiThread(() -> {
                refreshStatus();
                if (!result.success()) {
                    Toast.makeText(this, "Root 执行失败: " + result.output(), Toast.LENGTH_LONG).show();
                }
            });
        }, "nfc-request-start").start();
    }

    private void stopSimulation() {
        AppLogger.i("Action", "停止 UID 测试请求");
        simulationPrefs().edit().remove("target_uid").apply();

        new Thread(() -> {
            RootShell.Result result = RootShell.execute(
                    "set -e\n" +
                    "setprop persist.nfcuidsim.uid OFF\n" +
                    "setprop persist.nfcuidsim.active 0\n" +
                    "test \"$(getprop persist.nfcuidsim.active)\" = \"0\"\n" +
                    "svc nfc disable\n" +
                    "sleep 1\n" +
                    "svc nfc enable\n"
            );
            if (result.success()) {
                AppLogger.i("Root", "UID 测试请求已停止");
            } else {
                AppLogger.e("Root", "停止请求失败: " + result.output());
            }
            runOnUiThread(() -> {
                refreshStatus();
                if (!result.success()) {
                    Toast.makeText(this, "Root 执行失败: " + result.output(), Toast.LENGTH_LONG).show();
                }
            });
        }, "nfc-request-stop").start();
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
            String name = String.valueOf(entry.getValue());

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
        return "候选：仅凭卡片本身不能证明门禁只认 UID。需要在你有权限管理的读卡器上进一步验证。";
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
