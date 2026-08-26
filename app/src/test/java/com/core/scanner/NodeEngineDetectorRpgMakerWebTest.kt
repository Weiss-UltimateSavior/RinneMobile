package com.core.scanner

import com.core.model.EngineType
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeEngineDetectorRpgMakerWebTest {
    @Test
    fun detectsRootRpgMakerMvCoreScript() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode("index.html"),
                NodeEngineDetector.TestNode(
                    "js",
                    listOf(NodeEngineDetector.TestNode("rpg_core.js"))
                )
            )
        )

        assertEquals(EngineType.RPG_MV, result.engine)
        assertEquals(95, result.confidence)
    }

    @Test
    fun detectsNestedWwwRpgMakerMvCoreScriptAtDefaultDepth() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode(
                    "www",
                    listOf(
                        NodeEngineDetector.TestNode("index.html"),
                        NodeEngineDetector.TestNode(
                            "js",
                            listOf(NodeEngineDetector.TestNode("rpg_core.js"))
                        )
                    )
                )
            )
        )

        assertEquals(EngineType.RPG_MV, result.engine)
        assertEquals(95, result.confidence)
    }

    @Test
    fun detectsRootRpgMakerMzCoreScript() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode("index.html"),
                NodeEngineDetector.TestNode("data", listOf(NodeEngineDetector.TestNode("Actors.json"))),
                NodeEngineDetector.TestNode(
                    "js",
                    listOf(NodeEngineDetector.TestNode("rmmz_core.js"))
                )
            )
        )

        assertEquals(EngineType.RPG_MZ, result.engine)
        assertEquals(95, result.confidence)
    }

    @Test
    fun detectsNestedWwwRpgMakerMzCoreScriptDespiteDataDirectory() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode(
                    "www",
                    listOf(
                        NodeEngineDetector.TestNode("index.html"),
                        NodeEngineDetector.TestNode("data", listOf(NodeEngineDetector.TestNode("Actors.json"))),
                        NodeEngineDetector.TestNode(
                            "js",
                            listOf(NodeEngineDetector.TestNode("rmmz_core.js"))
                        )
                    )
                )
            )
        )

        assertEquals(EngineType.RPG_MZ, result.engine)
        assertEquals(95, result.confidence)
    }

    @Test
    fun keepsIndexAndDataDirectoryAsTyranoFallbackWithoutRpgMakerCore() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode("index.html"),
                NodeEngineDetector.TestNode("data", listOf(NodeEngineDetector.TestNode("scenario.ks")))
            )
        )

        assertEquals(EngineType.TYRANO, result.engine)
        assertEquals(96, result.confidence)
    }

    @Test
    fun detectsTyranoInsideUnpackedAppAsarDirectory() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode(
                    "app.asar",
                    listOf(
                        NodeEngineDetector.TestNode("index.html"),
                        NodeEngineDetector.TestNode("tyrano", listOf(NodeEngineDetector.TestNode("tyrano.css")))
                    )
                )
            )
        )

        assertEquals(EngineType.TYRANO, result.engine)
        assertEquals(96, result.confidence)
    }

    @Test
    fun detectsTyranoInsideResourcesAppAsarDirectoryAtDefaultDepth() {
        val result = NodeEngineDetector.detectForTest(
            listOf(
                NodeEngineDetector.TestNode(
                    "resources",
                    listOf(
                        NodeEngineDetector.TestNode(
                            "app.asar",
                            listOf(
                                NodeEngineDetector.TestNode("index.html"),
                                NodeEngineDetector.TestNode("data", listOf(NodeEngineDetector.TestNode("scenario.ks")))
                            )
                        )
                    )
                )
            )
        )

        assertEquals(EngineType.TYRANO, result.engine)
        assertEquals(96, result.confidence)
    }

    @Test
    fun keepsBareIndexAsTyranoFallback() {
        val result = NodeEngineDetector.detectForTest(
            listOf(NodeEngineDetector.TestNode("index.html"))
        )

        assertEquals(EngineType.TYRANO, result.engine)
        assertEquals(70, result.confidence)
    }
}
