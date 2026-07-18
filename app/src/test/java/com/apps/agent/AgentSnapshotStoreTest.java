package com.apps.agent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentSnapshotStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recoversCommittedMetadataTempAndDeletesTrueOrphanData() throws Exception {
        File directory = temporary.newFolder("snapshots");
        String recoverable = "00000000-0000-0000-0000-000000000001";
        File recoverableData = new File(directory, recoverable + ".bin");
        File recoverableTemp = new File(directory, recoverable + ".json.tmp");
        Files.write(recoverableData.toPath(), "before".getBytes(StandardCharsets.UTF_8));
        Files.write(recoverableTemp.toPath(), "{\"id\":\"".concat(recoverable)
                .concat("\"}").getBytes(StandardCharsets.UTF_8));

        String orphan = "00000000-0000-0000-0000-000000000002";
        File orphanData = new File(directory, orphan + ".bin");
        Files.write(orphanData.toPath(), "orphan".getBytes(StandardCharsets.UTF_8));

        long future = System.currentTimeMillis() + 10 * 60 * 1000L;
        AgentSnapshotStore.recoverIncompleteSnapshots(directory, future);

        assertTrue(recoverableData.isFile());
        assertTrue(new File(directory, recoverable + ".json").isFile());
        assertFalse(recoverableTemp.exists());
        assertFalse(orphanData.exists());
    }
}
