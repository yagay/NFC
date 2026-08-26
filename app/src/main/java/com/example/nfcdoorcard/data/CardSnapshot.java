package com.example.nfcdoorcard.data;

import java.util.List;

public record CardSnapshot(
        String uid,
        List<String> techList,
        String atqa,
        String sak,
        String classification,
        String note
) {}
