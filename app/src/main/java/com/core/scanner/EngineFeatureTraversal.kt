package com.core.scanner

/**
 * Shared phase-two traversal rules for engine feature detection.
 *
 * RPG Maker XP/VX/VX Ace keeps its unpacked database below Data/, while Game.ini
 * lives at the game root.  Therefore Data/ is relevant to both HTML engines and
 * RGSS-based RPG Maker games.
 */
internal object EngineFeatureTraversal {
    fun shouldDescendIntoData(hasIndex: Boolean, hasGameIni: Boolean): Boolean =
        hasIndex || hasGameIni
}
