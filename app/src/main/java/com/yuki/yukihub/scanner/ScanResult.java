package com.yuki.yukihub.scanner;

import com.yuki.yukihub.model.EngineType;

import java.util.ArrayList;
import java.util.List;

public class ScanResult {
    public String title;
    public String uri;
    public EngineType engine;
    public int confidence;
    public String launchTarget;
    public String coverUri;
    /** Multiple non-data XP3 candidates which require a user choice before import. */
    public List<String> xp3Candidates;
    /** RPG Maker runtime alias: rpgmxp / rpgmvx / rpgmvxace / mkxp-z. */
    public String rpgMakerSubtype;
    /** Ren'Py runtime alias: renpy / renpy8. */
    public String renpySubtype;

    public ScanResult(String title, String uri, EngineType engine, int confidence) {
        this(title, uri, engine, confidence, "", "");
    }

    public ScanResult(String title, String uri, EngineType engine, int confidence, String launchTarget) {
        this(title, uri, engine, confidence, launchTarget, "");
    }

    public ScanResult(String title, String uri, EngineType engine, int confidence, String launchTarget, String coverUri) {
        this(title, uri, engine, confidence, launchTarget, coverUri, null);
    }

    public ScanResult(String title, String uri, EngineType engine, int confidence, String launchTarget, String coverUri, List<String> xp3Candidates) {
        this(title, uri, engine, confidence, launchTarget, coverUri, xp3Candidates, "", "");
    }

    public ScanResult(String title, String uri, EngineType engine, int confidence, String launchTarget,
                      String coverUri, List<String> xp3Candidates, String rpgMakerSubtype,
                      String renpySubtype) {
        this.title = title;
        this.uri = uri;
        this.engine = engine;
        this.confidence = confidence;
        this.launchTarget = launchTarget == null ? "" : launchTarget;
        this.coverUri = coverUri == null ? "" : coverUri;
        this.xp3Candidates = xp3Candidates == null ? new ArrayList<>() : new ArrayList<>(xp3Candidates);
        this.rpgMakerSubtype = rpgMakerSubtype == null ? "" : rpgMakerSubtype;
        this.renpySubtype = renpySubtype == null ? "" : renpySubtype;
    }
}
