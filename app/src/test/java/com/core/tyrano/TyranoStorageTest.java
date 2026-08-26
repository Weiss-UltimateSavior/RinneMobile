package com.core.tyrano;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class TyranoStorageTest {
    @Test
    public void resolveStorageFile_acceptsSimpleKeyInsideRoot() throws Exception {
        File root = Files.createTempDirectory("tyrano-storage").toFile();
        assertEquals(
                new File(root, "slot01.sav").getCanonicalFile(),
                TyranoActivity.resolveStorageFile(root, "slot01"));
    }

    @Test
    public void resolveStorageFile_rejectsTraversalAndSeparators() throws Exception {
        File root = Files.createTempDirectory("tyrano-storage").toFile();
        assertNull(TyranoActivity.resolveStorageFile(root, "../slot"));
        assertNull(TyranoActivity.resolveStorageFile(root, "nested/slot"));
        assertNull(TyranoActivity.resolveStorageFile(root, "nested\\slot"));
    }

    @Test
    public void resolveStorageFile_mapsSpecialKeysToSafeDeterministicName() throws Exception {
        File root = Files.createTempDirectory("tyrano-storage").toFile();
        File first = TyranoActivity.resolveStorageFile(root, "save data: 用户 1");
        File second = TyranoActivity.resolveStorageFile(root, "save data: 用户 1");
        assertEquals(first, second);
        assertTrue(first.getName().matches("key_[0-9a-f]{64}\\.sav"));
        assertTrue(first.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
    }

    @Test
    public void resolveStorageFile_keepsSafeLegacySpecialFilename() throws Exception {
        File root = Files.createTempDirectory("tyrano-storage").toFile();
        File legacy = new File(root, "save data.sav");
        assertTrue(legacy.createNewFile());
        assertEquals(legacy.getCanonicalFile(), TyranoActivity.resolveStorageFile(root, "save data"));
    }

    @Test
    public void rpgMakerStorage_usesBinExtension() throws Exception {
        File root = Files.createTempDirectory("tyrano-storage").toFile();
        TyranoStorage.write(root, "file1", "payload", ".bin");

        assertEquals("payload", TyranoStorage.read(root, "file1", ".bin"));
        assertTrue(new File(root, "file1.bin").isFile());
    }
}
