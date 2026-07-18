package com.apps.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GameWorkspaceGatewayTextTest {
    @Test public void lcsKeepsCommonLinesOutOfChanges() throws Exception {
        JSONObject result = GameWorkspaceGateway.compareText("alpha\nbeta\ngamma", "alpha\ninserted\ngamma",
                20, () -> true);
        JSONArray changes = result.getJSONArray("changes");

        assertEquals(2, changes.length());
        assertEquals("added", changes.getJSONObject(0).getString("type"));
        assertEquals("inserted", changes.getJSONObject(0).getString("text"));
        assertEquals("removed", changes.getJSONObject(1).getString("type"));
        assertEquals("beta", changes.getJSONObject(1).getString("text"));
        assertFalse(result.getBoolean("truncated"));
    }

    @Test public void tailLogicalLinesIgnoreTerminalNewlineSentinel() {
        String[] lines = GameWorkspaceGateway.logicalLines("alpha\nbeta\n");
        assertEquals(2, lines.length);
        assertEquals("beta", lines[lines.length - 1]);
    }

    @Test public void logicalLinesPreserveRealBlankLines() {
        String[] lines = GameWorkspaceGateway.logicalLines("alpha\n\n");
        assertEquals(2, lines.length);
        assertEquals("", lines[1]);
    }

    @Test public void emptyTextDoesNotCreatePhantomDiffLine() throws Exception {
        JSONObject bothEmpty = GameWorkspaceGateway.compareText("", "", 20, () -> true);
        assertEquals(0, bothEmpty.getInt("left_line_count"));
        assertEquals(0, bothEmpty.getInt("right_line_count"));
        assertEquals(0, bothEmpty.getJSONArray("changes").length());

        JSONObject added = GameWorkspaceGateway.compareText("", "value", 20, () -> true);
        assertEquals(1, added.getJSONArray("changes").length());
        assertEquals("added", added.getJSONArray("changes").getJSONObject(0).getString("type"));
        assertEquals("value", added.getJSONArray("changes").getJSONObject(0).getString("text"));
    }
}
