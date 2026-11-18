package com.javafxapp.javafxapp.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {

    @TempDir Path tmp;

    @Test
    void theFullReportHasEverySection() {
        String report = Diagnostics.build(Optional.empty());

        assertTrue(report.startsWith("Lumina "), report);
        for (String heading : List.of("Emulators", "Settings", "Game", "End of the log"))
            assertTrue(report.contains("\n" + heading + "\n"), "missing " + heading + " in:\n" + report);
        assertTrue(report.contains("Goldberg:") && report.contains("gbe_fork:") && report.contains("SmokeAPI:") && report.contains("Steamless:"));
        assertTrue(report.contains("none open"), "tells you to open the game first");
    }

    @Test
    void theReportNeverCarriesTheWindowsAccountFolder() {
        String home = System.getProperty("user.home");
        String redacted = Diagnostics.redact("path " + home + "\\Games\\X and " + home.replace('\\', '/') + "/y, " + home.toUpperCase() + "\\z");

        assertFalse(redacted.toLowerCase().contains(home.toLowerCase()), redacted);
        assertTrue(redacted.contains("%USERPROFILE%\\Games\\X"), redacted);
        assertTrue(redacted.contains("%USERPROFILE%/y"), "forward slashes too: " + redacted);
        assertTrue(redacted.contains("%USERPROFILE%\\z"), "any letter case: " + redacted);

        // the data folder lives under the home folder, so it's named by its own variable first
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) assertEquals("%LOCALAPPDATA%\\Lumina", Diagnostics.redact(localAppData + "\\Lumina"));

        assertEquals("D:\\SteamLibrary\\steamapps\\common\\Game", Diagnostics.redact("D:\\SteamLibrary\\steamapps\\common\\Game"),
                "paths outside the account folder stay, they're what helps");
    }

    @Test
    void describesAGameFolder() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("My Game/bin"));
        Files.writeString(game.resolve("steam_api64.dll"), "not an emulator " + System.nanoTime());
        Files.writeString(game.resolve("steam_api64.dll.bak"), "the original");
        Files.writeString(game.resolve("steam_appid.txt"), "480");
        Files.writeString(game.resolve("game.exe"), "exe");
        Path settings = Files.createDirectories(game.resolve("steam_settings"));
        Files.writeString(settings.resolve("steam_interfaces.txt"), "SteamUser021\r\nSteamUtils010\r\n");
        Files.writeString(settings.resolve("configs.user.ini"), "[user::general]\naccount_name=Secret Name\n");

        String text = Diagnostics.gameSection(tmp.resolve("My Game"));

        assertTrue(text.contains("My Game"), text);
        assertTrue(text.contains("Steam App ID: 480"), text);
        assertTrue(text.contains("bin\\steam_api64.dll: not an emulator DLL (original)"), text);
        assertTrue(text.contains("steam_interfaces.txt lists 2"), text);
        assertTrue(text.contains("steam_settings: configs.user.ini, steam_interfaces.txt"), "file names only: " + text);
        assertTrue(text.contains("backup files: bin\\steam_api64.dll.bak"), text);
        assertFalse(text.contains("Secret Name"), "never the contents of a settings file");
    }

    @Test
    void saysWhenTheGameFolderIsGone() {
        String text = Diagnostics.gameSection(tmp.resolve("Not There"));
        assertTrue(text.contains("could not read the folder"), text);
    }

    @Test
    void tailKeepsTheLastLines() throws Exception {
        Path log = tmp.resolve("lumina.log");
        Files.write(log, IntStream.rangeClosed(1, 100).mapToObj(i -> "line " + i).toList());

        List<String> last = Diagnostics.tail(log, 5);

        assertEquals(List.of("line 96", "line 97", "line 98", "line 99", "line 100"), last);
        assertEquals(100, Diagnostics.tail(log, 500).size(), "a short file is returned whole");
    }

    @Test
    void tailHandlesBigAndEmptyLogs() throws Exception {
        Path big = tmp.resolve("big.log");
        // far more than the 64 KB window, so the first line it reads is cut in the middle and must be dropped
        Files.write(big, IntStream.rangeClosed(1, 20_000).mapToObj(i -> "2026-11-18 12:00:00.000 INFO  [main] - entry number " + i).toList());
        List<String> last = Diagnostics.tail(big, 3);
        assertEquals(List.of("2026-11-18 12:00:00.000 INFO  [main] - entry number 19998",
                "2026-11-18 12:00:00.000 INFO  [main] - entry number 19999",
                "2026-11-18 12:00:00.000 INFO  [main] - entry number 20000"), last);

        Path empty = Files.writeString(tmp.resolve("empty.log"), "");
        assertEquals(List.of(), Diagnostics.tail(empty, 5));
        assertTrue(Diagnostics.logSection(empty, true).contains("nothing has been logged yet"));
        assertTrue(Diagnostics.logSection(empty, false).contains("Write activity to a log file"), "logging off: say how to turn it on");
    }

    @Test
    void logSectionExplainsHowToGetALog() {
        assertTrue(Diagnostics.logSection(tmp.resolve("missing.log"), false).contains("Write activity to a log file"));
        assertTrue(Diagnostics.logSection(tmp.resolve("missing.log"), true).contains("nothing has been logged yet"));
    }

    @Test
    void logSectionShowsTheEndOfARealLog() throws Exception {
        Path log = Files.write(tmp.resolve("lumina.log"), List.of("first", "second", "third"));
        assertEquals("  first\n  second\n  third\n", Diagnostics.logSection(log, true));
    }
}
