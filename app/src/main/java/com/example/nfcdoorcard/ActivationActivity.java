package com.example.nfcdoorcard;

import android.os.Bundle;

/**
 * Launcher activity that refreshes MainActivity when the modern libxposed service
 * connection or module scope changes.
 */
public final class ActivationActivity extends MainActivity implements NfcDoorApplication.Listener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        NfcDoorApplication.addListener(this);
        refreshStatus();
    }

    @Override
    protected void onStop() {
        NfcDoorApplication.removeListener(this);
        super.onStop();
    }

    @Override
    public void onXposedStateChanged() {
        runOnUiThread(this::refreshStatus);
    }
}
