package com.core.launcher;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class KrkrLauncherTest {
    @Test
    public void normalizeEngineVersion_preservesSupportedAliases() {
        assertEquals("1.2.6", KrkrLauncher.normalizeEngineVersion("kr126"));
        assertEquals("1.3.4", KrkrLauncher.normalizeEngineVersion("134"));
        assertEquals("1.3.9", KrkrLauncher.normalizeEngineVersion("kirikiroid139"));
        assertEquals("auto", KrkrLauncher.normalizeEngineVersion("future"));
    }

    @Test
    public void resolvePath_xp3FirstSelectsLocalArchive() throws Exception {
        File root = Files.createTempDirectory("krkr-root").toFile();
        File archive = new File(root, "data.xp3");
        Files.write(archive.toPath(), new byte[]{1});

        assertEquals(
                archive.getAbsolutePath(),
                KrkrLauncher.resolvePath(null, root.getAbsolutePath(), "XP3_FIRST"));
    }

    @Test
    public void resolvePath_knownMissingEntryPreservesExpectedPath() throws Exception {
        File root = Files.createTempDirectory("krkr-root").toFile();
        assertEquals(
                new File(root, "startup.tjs").getAbsolutePath(),
                KrkrLauncher.resolvePath(null, root.getAbsolutePath(), "startup.tjs"));
    }

    @Test
    public void rootForPath_usesDirectoryContainingSelectedArchive() throws Exception {
        File root = Files.createTempDirectory("krkr-root").toFile();
        File archive = new File(root, "data.xp3");
        assertEquals(root.getAbsolutePath(), KrkrLauncher.rootForPath(archive.getPath(), archive.getPath()));
    }

    @Test
    public void preferEmbeddedStartupExecutable_selectsVerifiedExecutableForDataArchive() throws Exception {
        File root = Files.createTempDirectory("krkr-embedded-root").toFile();
        File archive = new File(root, "data.xp3");
        Files.write(archive.toPath(), new byte[]{1});
        File executable = new File(root, "translated-game.exe");
        Files.write(executable.toPath(), embeddedXp3("startup.tjs"));

        assertEquals(
                executable.getAbsolutePath(),
                KrkrLauncher.preferEmbeddedStartupExecutable(
                        root.getAbsolutePath(), "data.xp3", archive.getAbsolutePath()));
    }

    @Test
    public void preferEmbeddedStartupExecutable_doesNotSelectUnverifiedExecutable() throws Exception {
        File root = Files.createTempDirectory("krkr-invalid-exe-root").toFile();
        File archive = new File(root, "data.xp3");
        Files.write(archive.toPath(), new byte[]{1});
        Files.write(new File(root, "game.exe").toPath(), new byte[]{'M', 'Z'});

        assertEquals(
                archive.getAbsolutePath(),
                KrkrLauncher.preferEmbeddedStartupExecutable(
                        root.getAbsolutePath(), "data.xp3", archive.getAbsolutePath()));
    }

    @Test
    public void preferEmbeddedStartupExecutable_preservesExplicitCustomArchive() throws Exception {
        File root = Files.createTempDirectory("krkr-explicit-root").toFile();
        File archive = new File(root, "patch.xp3");
        Files.write(archive.toPath(), new byte[]{1});
        Files.write(new File(root, "game.exe").toPath(), embeddedXp3("startup.tjs"));

        assertEquals(
                archive.getAbsolutePath(),
                KrkrLauncher.preferEmbeddedStartupExecutable(
                        root.getAbsolutePath(), "patch.xp3", archive.getAbsolutePath()));
    }

    private static byte[] embeddedXp3(String name) throws Exception {
        byte[] signature = new byte[]{
                0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, (byte) 0x8b, 0x67, 0x01};
        byte[] nameBytes = name.getBytes("UTF-16LE");
        ByteArrayOutputStream infoBody = new ByteArrayOutputStream();
        infoBody.write(new byte[20]);
        infoBody.write(leShort(name.length()));
        infoBody.write(nameBytes);
        byte[] info = chunk("info", infoBody.toByteArray());
        byte[] file = chunk("File", info);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'M', 'Z', 0, 0});
        int archiveOffset = output.size();
        output.write(signature);
        output.write(leLong(signature.length + 8L));
        output.write(0); // uncompressed index
        output.write(leLong(file.length));
        output.write(file);
        if (archiveOffset < 0) throw new AssertionError();
        return output.toByteArray();
    }

    private static byte[] chunk(String name, byte[] body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(name.getBytes("US-ASCII"));
        output.write(leLong(body.length));
        output.write(body);
        return output.toByteArray();
    }

    private static byte[] leLong(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static byte[] leShort(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }
}
