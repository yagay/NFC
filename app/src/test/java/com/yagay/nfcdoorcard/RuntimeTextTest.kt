package com.yagay.nfcdoorcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTextTest {
    @Test fun boundedLinesKeepsNewestEntries() {
        assertEquals(listOf("3", "4"), RuntimeText.boundedLines("1\n2\n3\n4", 2))
    }

    @Test fun updateWindowProducesExactIncomingContent() {
        val target = mutableListOf("same", "old", "tail")
        RuntimeText.updateWindow(target, listOf("same", "new", "extra", "tail"))
        assertEquals(listOf("same", "new", "extra", "tail"), target)
    }

    @Test fun summaryIncludesSemanticProof() {
        val summary = RuntimeText.statusSummary(
            RuntimeStatus(operationState = "IDLE", effectiveState = "ACTIVE", rfAccepted = true),
            expectedHookBuild = 39,
            readModeEnabled = false
        )
        assertTrue(summary.contains("effective=ACTIVE"))
        assertTrue(summary.contains("expectedHook=39"))
    }
}
