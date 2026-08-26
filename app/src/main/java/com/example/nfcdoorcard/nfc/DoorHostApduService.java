package com.example.nfcdoorcard.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.util.Arrays;

/**
 * Minimal, intentionally conservative HCE service.
 *
 * It only exposes a demo AID and a health-check response. It does NOT replay a captured
 * access credential. Replace the protocol only for a reader/system you own or administer.
 */
public class DoorHostApduService extends HostApduService {
    private static final byte[] SELECT_DEMO_AID = TagInspector.hexToBytes("00A4040007F001020304050600");
    private static final byte[] OK = TagInspector.hexToBytes("9000");
    private static final byte[] UNKNOWN = TagInspector.hexToBytes("6A82");

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null) return UNKNOWN;
        if (Arrays.equals(commandApdu, SELECT_DEMO_AID)) {
            return OK;
        }
        return UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
        // No persistent NFC routing changes are made in v0.1.0.
    }
}
