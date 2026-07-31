package com.core.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScannerPolicyTest {
    @Test
    fun rootEntryFilesDoNotPruneSiblingDirectories() {
        assertFalse(GameScanner.shouldPruneAfterFileMatches(selectedRoot = true, fileEntryMatched = true))
    }

    @Test
    fun nestedGameContainerStillPrunesItsInternalDirectories() {
        assertTrue(GameScanner.shouldPruneAfterFileMatches(selectedRoot = false, fileEntryMatched = true))
    }
}
