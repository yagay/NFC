package com.example.nfcdoorcard.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

/** Minimal HCE health-check service for a demo AID only. */
public class DoorHostApduService extends HostApduService {
    private static final String TAG = "NfcDoorHCE";
    private static final byte[] DEMO_AID = TagInspector.hexToBytes("F0010203040506");
    private static final byte[] OK = TagInspector.hexToBytes("9000");
    private static final byte[] UNKNOWN = TagInspector.hexToBytes("6A82");

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        Log.i(TAG, "RX " + TagInspector.hex(commandApdu));
        boolean matched = isSelectDemoAid(commandApdu);
        byte[] response = matched ? OK : UNKNOWN;
        Log.i(TAG, (matched ? "Demo AID matched; TX " : "Unsupported APDU; TX ") + TagInspector.hex(response));
        return response;
    }

    private boolean isSelectDemoAid(byte[] apdu) {
        if (apdu == null || apdu.length < 5) return false;
        if ((apdu[0] & 0xFF) != 0x00 || (apdu[1] & 0xFF) != 0xA4 || (apdu[2] & 0xFF) != 0x04) {
            return false;
        }
        // Accept common SELECT-by-name P2 variants; AID bytes are the actual routing key.
        int lc = apdu[4] & 0xFF;
        if (lc != DEMO_AID.length || apdu.length < 5 + lc) return false;
        for (int i = 0; i < lc; i++) {
            if (apdu[5 + i] != DEMO_AID[i]) return false;
        }
        return true;
    }

    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "HCE deactivated reason=" + reason);
    }
}
