package com.example.nfcdoorcard;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.regex.Matcher;

/**
 * Compatibility launcher that preserves MainActivity functionality while replacing
 * obsolete self-hook status with modern libxposed/service diagnostics.
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
        String executionStatus;

        if (!NfcDoorApplication.isFrameworkConnected()) {
            modernStatus = "LSPosed: 未连接框架";
            executionStatus = "框架未连接，无法确认 NFC 进程执行状态";
        } else if (!NfcDoorApplication.isNfcScopeEnabled()) {
            modernStatus = "LSPosed: 已激活 · NFC 服务未在作用域";
            executionStatus = "NFC 服务未在作用域，任务不会注入 NFC 进程";
        } else {
            modernStatus = "LSPosed: 已激活 · NFC 服务作用域已启用";
            executionStatus = "NFC 作用域已启用，等待/检查 NFC 进程 Hook 事件";
        }

        // Replace the complete LSPosed line instead of replacing a prefix. This avoids
        // repeatedly expanding an already-modern status string.
        String replaced = text.replaceAll(
                "(?m)^LSPosed:.*$",
                Matcher.quoteReplacement(modernStatus)
        );

        // MainActivity's legacy cross-process getter always returns Unknown under the
        // modern API because module apps are no longer self-hooked. Do not present that
        // placeholder as a hardware result; show the service/scope diagnostic instead.
        replaced = replaced.replace(
                "模拟任务: 执行中 [Unknown]",
                "模拟任务: 执行中 · " + executionStatus
        );

        if (!replaced.equals(text)) {
            rewriting = true;
            try {
                view.setText(replaced);
            } finally {
                rewriting = false;
            }
        }
        view.setContentDescription(modernStatus + " · " + executionStatus + " · "
                + NfcDoorApplication.getFrameworkSummary());
    }
}
