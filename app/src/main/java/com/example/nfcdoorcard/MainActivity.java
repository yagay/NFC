package com.example.nfcdoorcard;

import android.app.Activity;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.nfcdoorcard.data.CardSnapshot;
import com.example.nfcdoorcard.nfc.TagInspector;

import java.util.stream.Collectors;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private NfcAdapter nfcAdapter;
    private TextView status;
    private TextView details;

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

        TextView title = text("NFC 门禁 · v0.1", 26, true);
        root.addView(title);
        TextView sub = text("先读取并判断卡类型，再决定是否适合标准 HCE。不会修改系统 NFC HAL。", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        status = text("", 16, true);
        root.addView(status);

        Button scan = new Button(this);
        scan.setText("开始读取门禁卡");
        scan.setOnClickListener(v -> enableReader());
        root.addView(scan, lp(-1, dp(52), 12));

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
        status.setText(nfc + "   ·   Root: " + (root ? "可用" : "未检测到/未授权"));
    }

    private void enableReader() {
        refreshStatus();
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
        details.setText("读取模式已开启。请把门禁卡贴近手机。\n\n读取完成后会显示 UID、Tech、ATQA、SAK 和协议分类。 ");
    }

    private void disableReader() {
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        details.setText("读取模式已停止。 ");
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        CardSnapshot s = TagInspector.inspect(tag);
        String tech = s.techList().stream().collect(Collectors.joining(", "));
        String out = "UID\n" + s.uid() +
                "\n\nTech\n" + tech +
                "\n\nATQA\n" + s.atqa() +
                "\n\nSAK\n" + s.sak() +
                "\n\n判断\n" + s.classification() +
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
