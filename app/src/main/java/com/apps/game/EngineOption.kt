package com.apps.game

import com.core.model.EngineType

/** Shared selectable engine option for add/edit game forms. */
internal class EngineOption(
    @JvmField val engine: EngineType,
    @JvmField val label: String,
    /** RPG Maker/Ren'Py/Godot subtype; null for engines without subtype routing. */
    @JvmField val rpgMakerSubtype: String?
) {
    override fun toString(): String = label
}
