package com.example.nfcdoorcard;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * Compatibility launcher that preserves MainActivity functionality while replacing
 * the obsolete self-hook activation indicator with libxposed/service state.
 */
public final class ActivationActivity extends MainActivity implements NfcDoorApplication.Listener {

    private TextView statusView;
    private boolean rewriting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        attachModernStatusBridge();
        rewriteStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        NfcDoorApplication.addListener(this);
        rewriteStatus();
    }

    @Override
    protected void onStop() {
        NfcDoorApplication.removeListener(this);
        super.onStop();
    }

    @Override
    public void onXposedStateChanged() {
        runOnUiThread(this::rewriteStatus);
    }

    private void attachModernStatusBridge() {
        try {
            Field field = MainActivity.class.getDeclaredField("status");
            field.setAccessible(true);
            Object value = field.get(this);
            if (!(value instanceof TextView)) return;
            statusView = (TextView) value;
            statusView.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (!rewriting) statusView.post(ActivationActivity.this::rewriteStatus);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void rewriteStatus() {
        TextView view = statusView;
        if (view == null || rewriting) return;

        String text = String.valueOf(view.getText());
        String modernStatus;
        if (!NfcDoorApplication.isFrameworkConnected()) {
            modernStatus = "LSPosed: 未连接框架";
        } else if (!NfcDoorApplication.isNfcScopeEnabled()) {
            modernStatus = "LSPosed: 已激活 · NFC 服务未在作用域";
        } else {
            modernStatus = "LSPosed: 已激活 · NFC 服务作用域已启用";
        }

        String replaced = text
                .replace("LSPosed: 未激活 (点击管理)", modernStatus)
                .replace("LSPosed: 已激活", modernStatus);

        if (!replaced.equals(text)) {
            rewriting = true;
            try {
                view.setText(replaced);
            } finally {
                rewriting = false;
            }
        }
        view.setContentDescription(modernStatus + " · " + NfcDoorApplication.getFrameworkSummary());
    }
}
