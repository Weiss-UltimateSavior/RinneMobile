package com.apps.game;

import com.core.model.EngineType;

/** Shared selectable engine option for add/edit game forms. */
final class EngineOption {
    final EngineType engine;
    final String label;
    /** RPG Maker/Ren'Py/Godot subtype; null for engines without subtype routing. */
    final String rpgMakerSubtype;

    EngineOption(EngineType engine, String label, String rpgMakerSubtype) {
        this.engine = engine;
        this.label = label;
        this.rpgMakerSubtype = rpgMakerSubtype;
    }

    @Override
    public String toString() {
        return label;
    }
}
