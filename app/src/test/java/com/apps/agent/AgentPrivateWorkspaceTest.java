package com.apps.agent;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AgentPrivateWorkspaceTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void supportsPrivateWorkspaceCrudWithinItsRoot() throws Exception {
        File root = temporary.newFolder("rinne-workspace");
        execute(root, "mkdir", "notes", "", "");
        execute(root, "write", "notes/task.md", "", "第一行");
        execute(root, "append", "notes/task.md", "", "\n第二行");

        JSONObject read = new JSONObject(execute(root, "read", "notes/task.md", "", ""));
        assertEquals("第一行\n第二行", read.getString("content"));
        assertEquals(64, read.getString("sha256").length());

        execute(root, "copy", "notes/task.md", "notes/copy.md", "");
        execute(root, "move", "notes/copy.md", "notes/moved.md", "");
        assertFalse(new File(root, "notes/copy.md").exists());
        assertTrue(new File(root, "notes/moved.md").isFile());

        JSONObject listed = new JSONObject(execute(root, "list", "notes", "", ""));
        assertEquals(2, listed.getJSONArray("items").length());

        JSONObject deleted = new JSONObject(execute(root, "delete", "notes/moved.md", "", ""));
        assertTrue(deleted.getBoolean("success"));
        assertFalse(new File(root, "notes/moved.md").exists());
    }

    @Test public void rejectsTraversalAndDeletingWorkspaceRoot() throws Exception {
        File root = temporary.newFolder("isolated-workspace");
        assertThrows(IllegalArgumentException.class,
                () -> execute(root, "write", "../outside.txt", "", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> execute(root, "delete", "", "", ""));
    }

    @Test public void statReportsFileAndDirectoryMetadata() throws Exception {
        File root = temporary.newFolder("stat-workspace");
        execute(root, "mkdir", "docs", "", "");
        execute(root, "write", "docs/a.txt", "", "hello");

        JSONObject fileStat = new JSONObject(execute(root, "stat", "docs/a.txt", "", ""));
        assertEquals("file", fileStat.getString("type"));
        assertTrue(fileStat.getLong("size") > 0);
        assertTrue(fileStat.getLong("last_modified") >= 0);

        JSONObject dirStat = new JSONObject(execute(root, "stat", "docs", "", ""));
        assertEquals("directory", dirStat.getString("type"));
    }

    @Test public void returnsStructuredErrorsForMissingAndMismatchedTargets() throws Exception {
        File root = temporary.newFolder("error-workspace");
        execute(root, "mkdir", "notes", "", "");

        JSONObject missingRead = new JSONObject(execute(root, "read", "notes/missing.md", "", ""));
        assertEquals("NOT_FOUND", missingRead.getString("error"));

        JSONObject missingStat = new JSONObject(execute(root, "stat", "notes/missing.md", "", ""));
        assertEquals("NOT_FOUND", missingStat.getString("error"));

        execute(root, "write", "notes/file.txt", "", "x");
        JSONObject listOnFile = new JSONObject(execute(root, "list", "notes/file.txt", "", ""));
        assertEquals("NOT_DIRECTORY", listOnFile.getString("error"));

        JSONObject writeIntoMissingParent = new JSONObject(execute(root, "write", "missing_dir/a.txt", "", "x"));
        assertEquals("PARENT_NOT_FOUND", writeIntoMissingParent.getString("error"));

        execute(root, "write", "notes/existing.txt", "", "x");
        JSONObject mkdirDuplicate = new JSONObject(execute(root, "mkdir", "notes", "", ""));
        assertEquals("ALREADY_EXISTS", mkdirDuplicate.getString("error"));

        JSONObject copyMissing = new JSONObject(execute(root, "copy", "notes/missing.md", "notes/copy.md", ""));
        assertEquals("NOT_FILE", copyMissing.getString("error"));

        JSONObject copyOntoExisting = new JSONObject(execute(root, "copy", "notes/existing.txt", "notes/existing.txt", ""));
        assertEquals("ALREADY_EXISTS", copyOntoExisting.getString("error"));
    }

    @Test public void rejectsOversizedWriteContent() throws Exception {
        File root = temporary.newFolder("oversize-workspace");
        StringBuilder big = new StringBuilder(128 * 1024 + 16);
        for (int i = 0; i < 128 * 1024 + 1; i++) big.append('x');
        assertThrows(IllegalArgumentException.class,
                () -> execute(root, "write", "big.txt", "", big.toString()));
    }

    @Test public void rejectsDeletingMissingPath() throws Exception {
        File root = temporary.newFolder("delete-missing");
        JSONObject result = new JSONObject(execute(root, "delete", "missing.txt", "", ""));
        assertEquals("NOT_FOUND", result.getString("error"));
    }

    private static String execute(File root, String command, String path,
                                  String secondary, String content) throws Exception {
        JSONObject args = new JSONObject().put("command", command).put("relative_path", path)
                .put("limit", 100).put("depth", 2);
        if (!secondary.isEmpty()) args.put("secondary_path", secondary);
        if ("write".equals(command) || "append".equals(command)) args.put("content", content);
        return AgentPrivateWorkspace.execute(root, args);
    }
}
