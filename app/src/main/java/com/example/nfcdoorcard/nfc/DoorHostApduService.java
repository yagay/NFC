package com.example.nfcdoorcard.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

/**
 * Minimal HCE health-check service. It only exposes a demo AID and does not replay
 * a captured access credential.
 */
public class DoorHostApduService extends HostApduService {
    private static final byte[] DEMO_AID = TagInspector.hexToBytes("F0010203040506");
    private static final byte[] OK = TagInspector.hexToBytes("9000");
    private static final byte[] UNKNOWN = TagInspector.hexToBytes("6A82");

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        return isSelectDemoAid(commandApdu) ? OK : UNKNOWN;
    }

    private boolean isSelectDemoAid(byte[] apdu) {
        if (apdu == null || apdu.length < 5) return false;
        if ((apdu[0] & 0xFF) != 0x00 || (apdu[1] & 0xFF) != 0xA4 ||
                (apdu[2] & 0xFF) != 0x04 || (apdu[3] & 0xFF) != 0x00) {
            return false;
        }

        int lc = apdu[4] & 0xFF;
        if (lc != DEMO_AID.length || apdu.length < 5 + lc) return false;
        for (int i = 0; i < lc; i++) {
            if (apdu[5 + i] != DEMO_AID[i]) return false;
        }
        // Optional trailing Le is intentionally ignored for this health-check SELECT.
        return true;
    }

    @Override
    public void onDeactivated(int reason) {
        // No persistent NFC routing changes are made.
    }
}
