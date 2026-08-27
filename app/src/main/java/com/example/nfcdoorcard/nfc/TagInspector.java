package com.example.nfcdoorcard.nfc;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;

import com.example.nfcdoorcard.data.CardSnapshot;
import com.example.nfcdoorcard.data.CardType;

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

        NfcA nfcA = NfcA.get(tag);
        NfcB nfcB = NfcB.get(tag);
        NfcF nfcF = NfcF.get(tag);
        NfcV nfcV = NfcV.get(tag);
        IsoDep isoDep = IsoDep.get(tag);
        MifareClassic mifare = MifareClassic.get(tag);

        String atqa = "—";
        String sak = "—";
        if (nfcA != null) {
            atqa = hex(nfcA.getAtqa());
            sak = String.format(Locale.US, "%02X", nfcA.getSak() & 0xFF);
        }

        CardType cardType;
        String classification;
        String note;
        String uidLength = tag.getId() == null ? "—" : tag.getId().length + " 字节";
        String classicSize = "—";
        String classicSectors = "—";
        String classicBlocks = "—";
        String hceSupport;

        if (mifare != null) {
            cardType = CardType.MIFARE_CLASSIC;
            classification = "MIFARE Classic / NFC-A";
            classicSize = describeClassicSize(mifare.getSize());
            classicSectors = String.valueOf(mifare.getSectorCount());
            classicBlocks = String.valueOf(mifare.getBlockCount());
            hceSupport = "不支持（标准 Android HCE）";
            note = "检测到 MIFARE Classic。普通 Android HCE 不能直接模拟 Classic/Crypto1；当前版本只读取公开卡片元数据。";
        } else if (isoDep != null) {
            cardType = CardType.ISO_DEP;
            classification = "ISO-DEP / HCE 候选";
            hceSupport = "候选支持（需进一步分析 APDU）";
            note = "检测到 ISO-DEP。标准 HCE 可处理 APDU，但仍需确认目标系统实际协议。";
        } else if (nfcA != null) {
            cardType = CardType.NFC_A;
            classification = "NFC-A / 非 ISO-DEP";
            hceSupport = "通常不支持固定 UID";
            note = "检测到 NFC-A 非 ISO-DEP 标签。标准 HCE 无法保证复现固定 UID。";
        } else if (nfcB != null) {
            cardType = CardType.NFC_B;
            classification = "NFC-B / 非 ISO-DEP";
            hceSupport = "通常不支持";
            note = "检测到 NFC-B 标签。";
        } else if (nfcF != null) {
            cardType = CardType.NFC_F;
            classification = "NFC-F / FeliCa";
            hceSupport = "依设备/协议而定";
            note = "检测到 NFC-F/FeliCa 标签。";
        } else if (nfcV != null) {
            cardType = CardType.NFC_V;
            classification = "NFC-V / ISO 15693";
            hceSupport = "标准 HostApduService 不适用";
            note = "检测到 NFC-V/ISO 15693 标签。";
        } else {
            cardType = CardType.UNKNOWN;
            classification = "未知 NFC 技术";
            hceSupport = "未知";
            note = "Android 返回了未归类的 NFC 技术组合。";
        }

        return new CardSnapshot(
                hex(tag.getId()), tech, cardType, atqa, sak, classification, note,
                uidLength, classicSize, classicSectors, classicBlocks, hceSupport
        );
    }

    private static String describeClassicSize(int bytes) {
        if (bytes == MifareClassic.SIZE_1K) return "1 KB (MIFARE Classic 1K)";
        if (bytes == MifareClassic.SIZE_2K) return "2 KB";
        if (bytes == MifareClassic.SIZE_4K) return "4 KB (MIFARE Classic 4K)";
        if (bytes == MifareClassic.SIZE_MINI) return "320 B (MIFARE Mini)";
        return bytes + " B";
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
