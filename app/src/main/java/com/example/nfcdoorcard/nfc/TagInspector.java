package com.example.nfcdoorcard.nfc;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;

import com.example.nfcdoorcard.data.CardSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TagInspector {
    private TagInspector() {}

    public static CardSnapshot inspect(Tag tag) {
        List<String> tech = new ArrayList<>();
        for (String name : tag.getTechList()) {
            int dot = name.lastIndexOf('.');
            tech.add(dot >= 0 ? name.substring(dot + 1) : name);
        }

        String atqa = "—";
        String sak = "—";
        NfcA nfcA = NfcA.get(tag);
        if (nfcA != null) {
            atqa = hex(nfcA.getAtqa());
            sak = String.format(Locale.US, "%02X", nfcA.getSak() & 0xFF);
        }

        boolean isoDep = IsoDep.get(tag) != null;
        boolean classic = MifareClassic.get(tag) != null;
        String classification;
        String note;

        if (classic) {
            classification = "MIFARE Classic / NFC-A";
            note = "普通 Android HCE 不能直接模拟 Classic/Crypto1。当前版本只做识别，不执行密钥认证或 UID 复制。";
        } else if (isoDep) {
            classification = "ISO-DEP / HCE 候选";
            note = "这类卡可进一步分析 APDU 协议，并用 HostApduService 为你有权限控制的门禁系统实现兼容。";
        } else {
            classification = "NFC-A / 非 ISO-DEP";
            note = "可能是只读 UID 或厂商私有协议。标准 HCE 无法保证复现固定 UID；需要先确认门禁读卡器实际校验内容。";
        }

        return new CardSnapshot(hex(tag.getId()), tech, atqa, sak, classification, note);
    }

    public static String hex(byte[] value) {
        if (value == null || value.length == 0) return "—";
        StringBuilder sb = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", value[i] & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String s) {
        String cleaned = s.replace(":", "").replace(" ", "").trim();
        if ((cleaned.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
