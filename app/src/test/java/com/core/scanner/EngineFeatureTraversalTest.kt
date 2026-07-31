package com.core.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFeatureTraversalTest {
    @Test
    fun gameIniAllowsScanningUnpackedRpgMakerData() {
        assertTrue(
            EngineFeatureTraversal.shouldDescendIntoData(
                hasIndex = false,
                hasGameIni = true
            )
        )
    }

    @Test
    fun htmlEntryStillAllowsScanningData() {
        assertTrue(
            EngineFeatureTraversal.shouldDescendIntoData(
                hasIndex = true,
                hasGameIni = false
            )
        )
    }

    @Test
    fun unrelatedDataDirectoryRemainsPruned() {
        assertFalse(
            EngineFeatureTraversal.shouldDescendIntoData(
                hasIndex = false,
                hasGameIni = false
            )
        )
    }
}
