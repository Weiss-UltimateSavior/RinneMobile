package com.yuki.yukihub.launcher;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ExternalGameLaunchersTest {
    @Test
    public void parseWinlatorContainerId_supportsBothShortcutLayouts() {
        assertEquals(12, ExternalGameLaunchers.parseWinlatorContainerId(
                "/data/user/0/com.winlator/files/imagefs/home/xuser-12/shortcuts/game.desktop"));
        assertEquals(7, ExternalGameLaunchers.parseWinlatorContainerId("xuser-7/game.desktop"));
        assertEquals(0, ExternalGameLaunchers.parseWinlatorContainerId("game.desktop"));
    }

    @Test
    public void extractDesktopExecutable_handlesWineAndQuotedPaths() {
        assertEquals(
                "C:\\Games\\Example Game\\game.exe",
                ExternalGameLaunchers.extractDesktopExecutable(
                        "env WINEDEBUG=-all wine \"C:\\Games\\Example Game\\game.exe\" --fullscreen"));
        assertEquals(
                "/games/game.exe",
                ExternalGameLaunchers.extractDesktopExecutable("wine /games/game.exe --fullscreen"));
    }

    @Test
    public void resolveWinlatorExecPath_combinesDesktopWorkingDirectory() throws Exception {
        File desktop = Files.createTempFile("winlator", ".desktop").toFile();
        Files.write(
                desktop.toPath(),
                ("[Desktop Entry]\nPath=/games/demo\nExec=wine C:\\Games\\Demo\\demo.exe\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "/games/demo/demo.exe",
                ExternalGameLaunchers.resolveWinlatorExecPath(desktop.getPath(), "com.winlator"));
    }
}
