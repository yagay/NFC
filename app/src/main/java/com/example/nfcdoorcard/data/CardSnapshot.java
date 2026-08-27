package com.example.nfcdoorcard.data;

import java.util.List;

public record CardSnapshot(
        String uid,
        List<String> techList,
        CardType cardType,
        String atqa,
        String sak,
        String classification,
        String note,
        String uidLength,
        String classicSize,
        String classicSectors,
        String classicBlocks,
        String hceSupport
) {
    public CardSnapshot {
        techList = List.copyOf(techList);
    }
}
