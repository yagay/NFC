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
import com.example.nfcdoorcard.data.CardType;
import com.example.nfcdoorcard.nfc.TagInspector;
import com.example.nfcdoorcard.utils.AppLogger;
import com.example.nfcdoorcard.utils.RootShell;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final int MIN_SUPPORTED_SDK = 31;
    private static final int MAX_SUPPORTED_SDK = 37;

    private final ExecutorService nfcExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean nfcOperationRunning = new AtomicBoolean(false);
    private final AtomicBoolean hceStatusRunning = new AtomicBoolean(false);

    private NfcAdapter nfcAdapter;
    private TextView status;
    private TextView details;
    private TextView logView;
    private LinearLayout cardListContainer;
    private Button simulateBtn;
    private Button stopSimBtn;
    private String currentUid;
    private String savedUid;
    private boolean readerRequested;
    private volatile String observedHceStatus = "底层 HCE Type-A: 等待运行时观测";

    private SharedPreferences simulationPrefs() {
        return createDeviceProtectedStorageContext().getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
    }

    /** Legacy builds stored sim_prefs in credential-protected storage. */
    private SharedPreferences legacySimulationPrefs() {
        return getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
    }

    private SharedPreferences uiPrefs() {
        return getSharedPreferences("ui_prefs", Context.MODE_PRIVATE);
    }

    private boolean isSimulationRequestActive() {
        return simulationPrefs().getBoolean("request_active", false);
    }

    private void runHardwareDiagnostic() {
        AppLogger.i("Diag", "正在抓取 NFC 诊断信息...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("【LSPosed / libxposed】\n");
            boolean connected = NfcDoorApplication.isFrameworkConnected();
            boolean scoped = NfcDoorApplication.isNfcScopeEnabled();
            sb.append("● 框架连接: ").append(connected ? "✅ 已连接" : "❌ 未连接").append("\n");
            sb.append("● NFC 作用域配置: ").append(scoped ? "✅ com.android.nfc 已启用" : "❌ 未启用").append("\n");
            sb.append("● 实际 NFC 进程 Hook: 通过运行时 HCE 观测判断\n");
            sb.append("● 框架信息: ").append(NfcDoorApplication.getFrameworkSummary()).append("\n");
            sb.append("● ").append(observedHceStatus).append("\n");
            sb.append("● 底层固定 UID 写入: 未启用\n\n");

            SharedPreferences prefs = simulationPrefs();
            sb.append("【测试请求】\n");
            sb.append("● 请求状态: ").append(prefs.getBoolean("request_active", false) ? "已开启" : "已停止").append("\n");
            sb.append("● 目标 UID: ").append(prefs.getString("target_uid", "未设置")).append("\n\n");

            sb.append("【系统服务实时数据】\n");
            RootShell.Result nfcDump = RootShell.execute("dumpsys nfc | grep -E 'mState|listenTech'");
            sb.append(nfcDump.success() && !nfcDump.output().isEmpty() ? nfcDump.output() : "⚠️ 获取失败: " + nfcDump.describe()).append("\n");

            RootShell.Result chipResult = RootShell.execute("getprop ro.hardware.nfc");
            sb.append("\n【硬件芯片方案】\n● ro.hardware.nfc: ")
                    .append(chipResult.success() && !chipResult.output().isEmpty() ? chipResult.output() : "未知")
                    .append("\n");

            String report = sb.toString();
            runOnUiThreadIfAlive(() -> {
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
        String[] managers = {"org.lsposed.manager", "io.github.lsposed.manager", "org.meowcat.edxposed.manager", "de.robv.android.xposed.installer"};
        for (String pkg : managers) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) { startActivity(intent); return; }
            } catch (Exception ignored) {}
        }
        try {
            Intent intent = new Intent("org.lsposed.manager.LAUNCH_MODULE");
            intent.putExtra("pkg", getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {}
        Toast.makeText(this, "未找到 LSPosed 管理器，请手动打开并检查 NFC 服务作用域", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        savedUid = uiPrefs().getString("comparison_uid", null);

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
        refreshObservedHceStatusAsync();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.setListener(allLogs -> runOnUiThreadIfAlive(() -> {
            if (logView != null) logView.setText(allLogs);
        }));
        refreshStatus();
        refreshObservedHceStatusAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readerRequested) enableReaderModeInternal(false);
        refreshObservedHceStatusAsync();
    }

    @Override
    protected void onPause() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        super.onPause();
    }

    @Override
    protected void onStop() {
        AppLogger.setListener(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        nfcExecutor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, outValue, true);
        content.setBackgroundColor(outValue.data);

        content.addView(text("NFC 门禁 · v" + BuildConfig.VERSION_NAME, 26, true));
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
            if (!hasCurrentUid()) { toast("请先读取一张卡"); return; }
            savedUid = currentUid;
            uiPrefs().edit().putString("comparison_uid", savedUid).apply();
            toast("已保存 UID: " + savedUid);
        });
        content.addView(saveUid, lp(-1, dp(52), 12));

        Button copyUid = new Button(this);
        copyUid.setText("复制当前 UID");
        copyUid.setOnClickListener(v -> copyCurrentUid());
        content.addView(copyUid, lp(-1, dp(52), 12));

        simulateBtn = new Button(this);
        simulateBtn.setText("设置当前 UID 测试请求");
        simulateBtn.setOnClickListener(v -> simulateCurrentUid());
        content.addView(simulateBtn, lp(-1, dp(52), 12));

        stopSimBtn = new Button(this);
        stopSimBtn.setText("停止 UID 测试请求 / 清理旧状态");
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
            uiPrefs().edit().remove("comparison_uid").apply();
            toast("已清除 UID 对比基准");
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
        diagBtn.setOnClickListener(v -> {
            refreshObservedHceStatusAsync();
            runHardwareDiagnostic();
        });
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
        if (status == null) return;
        Boolean root = RootStatus.getCachedResult();
        String rootText = root == null ? "检测中" : (root ? "可用" : "未检测到/未授权");
        String nfc = nfcAdapter == null ? "无 NFC 硬件" : (nfcAdapter.isEnabled() ? "NFC 已开启" : "NFC 未开启");
        int sdk = Build.VERSION.SDK_INT;
        String support = isSupportedSdk(sdk) ? "系统受支持" : "系统版本超出支持范围";
        String moduleStatus = !NfcDoorApplication.isFrameworkConnected()
                ? "LSPosed: 未连接框架"
                : (!NfcDoorApplication.isNfcScopeEnabled()
                ? "LSPosed: 已连接 · NFC 服务未在作用域"
                : "LSPosed: 已连接 · NFC 作用域已配置");

        SharedPreferences prefs = simulationPrefs();
        boolean requested = prefs.getBoolean("request_active", false);
        String simStatus = requested
                ? "\nUID 测试请求: 已保存 [" + prefs.getString("target_uid", "未设置") + "]"
                : "\nUID 测试请求: 已停止";
        String runtimeStatus = "\n" + observedHceStatus;
        if (requested && observedHceStatus.contains("已关闭")) {
            runtimeStatus += " · 请求未进入底层 HCE";
        }

        status.setText(androidVersionName(sdk) + " / API " + sdk + "   ·   " + support +
                "\n" + nfc + "   ·   Root: " + rootText + "\n" + moduleStatus + simStatus + runtimeStatus);
        status.setOnClickListener(v -> {
            if (!NfcDoorApplication.isFrameworkConnected() || !NfcDoorApplication.isNfcScopeEnabled()) openLSPosedManager();
        });
    }

    private void refreshObservedHceStatusAsync() {
        if (!hceStatusRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            String command = "set +e\n" +
                    "for d in /data/adb/lspd/log /data/adb/lspd/log/verbose; do\n" +
                    " [ -d \"$d\" ] || continue;\n" +
                    " find \"$d\" -maxdepth 1 -type f 2>/dev/null | sort | tail -n 6 | while IFS= read -r f; do\n" +
                    "  tail -n 5000 \"$f\" 2>/dev/null | grep -E 'NFC-TRACE ENTER (VendorNfcService\\.doSetHceTypeAConfig|NxpNativeNfcManager\\.setHceTypeAConfig) args=';\n" +
                    " done;\n" +
                    "done | tail -n 1";
            RootShell.Result result = RootShell.execute(command);
            String line = result.output() == null ? "" : result.output().trim();
            String next;
            if (line.contains("args=[0=true")) {
                next = "底层 HCE Type-A: 已开启（运行时已观测）";
            } else if (line.contains("args=[0=false")) {
                next = "底层 HCE Type-A: 已关闭（OPlus/NXP 运行时已观测）";
            } else if (!result.success()) {
                next = "底层 HCE Type-A: 无法读取运行时日志";
            } else {
                next = "底层 HCE Type-A: 尚未观测到状态切换";
            }
            observedHceStatus = next;
            hceStatusRunning.set(false);
            runOnUiThreadIfAlive(this::refreshStatus);
        }, "hce-runtime-status").start();
    }

    private void refreshRootStatusAsync(boolean showToast) {
        RootStatus.clearCache();
        refreshStatus();
        new Thread(() -> {
            boolean ok = RootStatus.hasRoot();
            runOnUiThreadIfAlive(() -> {
                refreshStatus();
                if (showToast) Toast.makeText(this, ok ? "Root 权限已获取" : "未获得 Root 权限，请在授权管理中允许", ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            });
        }, "root-check").start();
    }

    private boolean isSupportedSdk(int sdk) { return sdk >= MIN_SUPPORTED_SDK && sdk <= MAX_SUPPORTED_SDK; }

    private String androidVersionName(int sdk) {
        return switch (sdk) {
            case 31 -> "Android 12"; case 32 -> "Android 12L"; case 33 -> "Android 13";
            case 34 -> "Android 14"; case 35 -> "Android 15"; case 36 -> "Android 16";
            case 37 -> "Android 17"; default -> "Android";
        };
    }

    private void enableReader() {
        readerRequested = true;
        enableReaderModeInternal(true);
    }

    private void enableReaderModeInternal(boolean updateMessage) {
        refreshStatus();
        if (!isSupportedSdk(Build.VERSION.SDK_INT)) {
            readerRequested = false;
            if (updateMessage) details.setText("当前 Android 版本不在正式支持范围内。支持 Android 12–17（API 31–37）。");
            return;
        }
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            readerRequested = false;
            if (updateMessage) details.setText("NFC 不可用或未开启。");
            return;
        }
        int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B |
                NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V |
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        nfcAdapter.enableReaderMode(this, this, flags, null);
        if (updateMessage) details.setText("读取模式已开启。请把门禁卡贴近手机。");
    }

    private void disableReader() {
        readerRequested = false;
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        details.setText("读取模式已停止。");
    }

    private void copyCurrentUid() {
        if (!hasCurrentUid()) { toast("请先读取一张卡"); return; }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("NFC UID", currentUid));
        toast("UID 已复制");
    }

    private void simulateCurrentUid() {
        if (!hasCurrentUid()) { toast("请先读取或从卡包选择一张卡"); return; }
        String safeUid = currentUid.replaceAll("[^0-9A-Fa-f:]", "");
        new android.app.AlertDialog.Builder(this)
                .setTitle("设置测试请求")
                .setMessage("将保存 UID " + safeUid + " 并重启 NFC，然后自动检查底层 HCE Type-A 实际状态。当前版本不会写入控制器固定 UID。")
                .setPositiveButton("继续", (d, w) -> enqueueNfcRequest(true, safeUid))
                .setNegativeButton("取消", null)
                .show();
    }

    private void stopSimulation() {
        enqueueNfcRequest(false, null);
    }

    private void enqueueNfcRequest(boolean start, String uid) {
        if (!nfcOperationRunning.compareAndSet(false, true)) {
            toast("NFC 操作正在执行，请稍后再试");
            return;
        }
        setNfcButtonsEnabled(false);
        observedHceStatus = "底层 HCE Type-A: 等待 NFC 重启后的运行时观测";
        AppLogger.i("Action", start ? "准备设置 UID 测试请求: " + uid : "准备停止 UID 测试请求并清理旧状态");

        nfcExecutor.execute(() -> {
            SharedPreferences.Editor editor = simulationPrefs().edit();
            if (start) {
                editor.putString("target_uid", uid).putBoolean("request_active", true);
            } else {
                editor.remove("target_uid").putBoolean("request_active", false);
            }
            boolean currentPrefsSaved = editor.commit();

            boolean legacyPrefsCleared = legacySimulationPrefs().edit()
                    .remove("target_uid")
                    .putBoolean("request_active", false)
                    .commit();

            RootShell.Result result;
            if (!currentPrefsSaved || !legacyPrefsCleared) {
                result = new RootShell.Result(false, -1,
                        "failed to persist current UID state or clear legacy state", false);
            } else {
                result = RootShell.execute(
                        "set -e\n" +
                        "setprop persist.nfcuidsim.active '' || true\n" +
                        "setprop persist.nfcuidsim.uid '' || true\n" +
                        "svc nfc disable\n" +
                        "sleep 1\n" +
                        "svc nfc enable\n");
            }

            if (result.success()) {
                AppLogger.i("Root", start
                        ? "UID 请求已保存；NFC 已重启，等待底层 HCE Type-A 状态观测"
                        : "UID 请求和旧状态已清理，然后完成 NFC 重启");
            } else {
                AppLogger.e("Root", "NFC 操作失败: " + result.describe());
            }
            nfcOperationRunning.set(false);
            runOnUiThreadIfAlive(() -> {
                setNfcButtonsEnabled(true);
                refreshStatus();
                if (result.success()) {
                    toast(start ? "UID 请求已保存，正在核对底层 HCE 状态" : "UID 测试请求和旧状态已清理");
                    refreshObservedHceStatusAsync();
                } else {
                    Toast.makeText(this, "配置/Root/NFC 操作失败: " + result.describe(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void setNfcButtonsEnabled(boolean enabled) {
        if (simulateBtn != null) simulateBtn.setEnabled(enabled);
        if (stopSimBtn != null) stopSimBtn.setEnabled(enabled);
    }

    private void showSaveDialog() {
        if (!hasCurrentUid()) { toast("请先读取一张卡"); return; }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("给这张卡起个名字 (如：公司门禁)");
        new android.app.AlertDialog.Builder(this)
                .setTitle("保存卡片").setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    saveCard(name.isEmpty() ? "未命名卡片" : name, currentUid);
                }).setNegativeButton("取消", null).show();
    }

    private void saveCard(String name, String uid) {
        try {
            JSONObject card = new JSONObject();
            card.put("uid", uid);
            card.put("name", name);
            String key = "card_" + UUID.randomUUID();
            getSharedPreferences("card_wallet", Context.MODE_PRIVATE).edit().putString(key, card.toString()).apply();
            toast("保存成功");
            refreshCardList();
        } catch (Exception e) {
            AppLogger.e("Wallet", "保存卡片失败: " + e.getMessage());
            toast("保存失败");
        }
    }

    private void refreshCardList() {
        if (cardListContainer == null) return;
        cardListContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences("card_wallet", Context.MODE_PRIVATE);
        java.util.Map<String, ?> allCards = prefs.getAll();
        if (allCards.isEmpty()) { cardListContainer.addView(text("卡包空空如也", 14, false)); return; }

        for (java.util.Map.Entry<String, ?> entry : allCards.entrySet()) {
            String storageKey = entry.getKey();
            String uid;
            String name;
            if (storageKey.startsWith("card_")) {
                try {
                    JSONObject card = new JSONObject(String.valueOf(entry.getValue()));
                    uid = card.optString("uid", "—");
                    name = card.optString("name", "未命名卡片");
                } catch (Exception e) {
                    AppLogger.w("Wallet", "跳过损坏的卡片记录: " + storageKey);
                    continue;
                }
            } else {
                uid = storageKey;
                name = String.valueOf(entry.getValue());
            }

            String selectedUid = uid;
            String selectedName = name;
            Button btn = new Button(this);
            btn.setText(selectedName + " (" + selectedUid + ")");
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> {
                currentUid = selectedUid;
                details.setText("已从卡包选择：" + selectedName + "\nUID: " + selectedUid + "\n\n如需设置测试请求，请点击上方“设置当前 UID 测试请求”。");
            });
            btn.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this).setMessage("删除 " + selectedName + "？")
                        .setPositiveButton("删除", (d, w) -> { prefs.edit().remove(storageKey).apply(); refreshCardList(); })
                        .setNegativeButton("取消", null).show();
                return true;
            });
            cardListContainer.addView(btn, lp(-1, dp(48), 8));
        }
    }

    private String uidOnlyAssessment(CardSnapshot s) {
        if (s.cardType() != CardType.MIFARE_CLASSIC) return "暂不判断：当前卡不是 MIFARE Classic。";
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
        String out = "UID\n" + s.uid() + "\n\nUID 长度\n" + s.uidLength() +
                "\n\nUID 对比\n" + comparisonText(s.uid()) + "\n\nTech\n" + tech +
                "\n\nATQA\n" + s.atqa() + "\n\nSAK\n" + s.sak() +
                "\n\n判断\n" + s.classification() + "\n\nUID-only 候选\n" + uidOnlyAssessment(s) +
                "\n\nClassic 容量\n" + s.classicSize() + "\n\n扇区数\n" + s.classicSectors() +
                "\n\n块数\n" + s.classicBlocks() + "\n\n标准 HCE\n" + s.hceSupport() +
                "\n\n说明\n" + s.note();
        runOnUiThreadIfAlive(() -> details.setText(out));
    }

    private boolean hasCurrentUid() { return currentUid != null && !"—".equals(currentUid); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void runOnUiThreadIfAlive(Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }
    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.bottomMargin = dp(bottom); return p;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
