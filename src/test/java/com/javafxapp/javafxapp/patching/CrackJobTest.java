package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.emulator.Emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CrackJobTest {

    @TempDir Path tmp;

    private static CrackJob.Request request(Path game, Optional<Path> backup, Optional<Path> move) {
        return new CrackJob.Request(game, Emulator.GOLDBERG, true, "", backup, move);
    }

    @Test
    void aBackupFolderInsideTheGameIsRefused() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Path inside = game.resolve("backups");

        IOException e = assertThrows(IOException.class, () -> CrackJob.checkTargets(request(game, Optional.of(inside), Optional.empty())));
        assertTrue(e.getMessage().startsWith("The backup folder"), e.getMessage());
        assertTrue(assertThrows(IOException.class,
                () -> CrackJob.checkTargets(request(game, Optional.of(game), Optional.empty()))).getMessage().contains("inside the game folder"),
                "the game folder itself counts too");
        assertTrue(assertThrows(IOException.class,
                () -> CrackJob.checkTargets(request(game, Optional.empty(), Optional.of(game.resolve("a/b"))))).getMessage().startsWith("The move folder"));
    }

    @Test
    void foldersBesideOrAboveTheGameAreFine() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("Game"));
        assertDoesNotThrow(() -> CrackJob.checkTargets(request(game, Optional.of(tmp.resolve("Backups")), Optional.of(tmp.resolve("Moved")))));
        assertDoesNotThrow(() -> CrackJob.checkTargets(request(game, Optional.of(tmp), Optional.empty())), "the folder that contains the game");
        // "Game 2" starts with "Game" as text but is a different folder
        assertDoesNotThrow(() -> CrackJob.checkTargets(request(game, Optional.of(tmp.resolve("Game 2")), Optional.empty())));
        assertDoesNotThrow(() -> CrackJob.checkTargets(request(game, Optional.empty(), Optional.empty())));
    }

    @Test
    void thePreviewReportsABackupFolderInsideTheGame() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Files.writeString(game.resolve("steam_api64.dll"), "original");

        CrackJob.Plan plan = CrackJob.plan(request(game, Optional.of(game.resolve("backups")), Optional.empty()));

        assertTrue(plan.problem().orElse("").contains("inside the game folder"), plan.problem().toString());
    }

    @Test
    void backupTargetUsesPlainNameWhenFree() {
        assertEquals(tmp.resolve("Game"), CrackJob.freeBackupTarget(tmp, "Game"));
    }

    @Test
    void backupTargetNeverReusesAnExistingFolder() throws Exception {
        Files.createDirectory(tmp.resolve("Game"));
        Path second = CrackJob.freeBackupTarget(tmp, "Game");
        assertNotEquals(tmp.resolve("Game"), second);
        assertTrue(second.getFileName().toString().startsWith("Game (backup "));

        Files.createDirectory(second);
        Path third = CrackJob.freeBackupTarget(tmp, "Game");
        assertFalse(Files.exists(third));
        assertNotEquals(second, third);
    }
}
