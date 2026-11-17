package com.javafxapp.javafxapp.backup;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FolderRestoreJobTest {

    @TempDir Path tmp;

    private Path game, backup;

    @BeforeEach
    void setUp() throws IOException {
        backup = tmp.resolve("backups/MyGame");
        Files.createDirectories(backup.resolve("bin"));
        Files.writeString(backup.resolve("bin/steam_api64.dll"), "original");
        Files.writeString(backup.resolve("game.exe"), "exe");

        game = tmp.resolve("games/MyGame");
        Files.createDirectories(game.resolve("bin"));
        Files.writeString(game.resolve("bin/steam_api64.dll"), "emulator");
        Files.writeString(game.resolve("bin/steam_api64.dll.bak"), "original");
        Files.writeString(game.resolve("game.exe"), "exe");
    }

    private Optional<Path> restore(boolean keep) throws IOException {
        return FolderRestoreJob.swap(FolderRestoreJob.stage(backup, game, Progress.NONE), game, keep);
    }

    private long siblings() throws IOException {
        try (Stream<Path> s = Files.list(game.getParent())) {
            return s.count();
        }
    }

    @Test
    void replacesGameFolderWithBackup() throws Exception {
        Optional<Path> kept = restore(false);

        assertEquals("original", Files.readString(game.resolve("bin/steam_api64.dll")));
        assertFalse(Files.exists(game.resolve("bin/steam_api64.dll.bak")), "files not in the backup are gone");
        assertTrue(kept.isEmpty());
        assertEquals(1, siblings(), "no temp or old folder left behind");
        assertEquals("exe", Files.readString(backup.resolve("game.exe")), "backup is untouched");
    }

    @Test
    void keepsCurrentFolderNextToGame() throws Exception {
        Files.createDirectories(tmp.resolve("games/MyGame (before restore)"));
        Optional<Path> kept = restore(true);

        assertEquals(tmp.resolve("games/MyGame (before restore 2)"), kept.orElseThrow());
        assertEquals("emulator", Files.readString(kept.get().resolve("bin/steam_api64.dll")));
        assertEquals("original", Files.readString(game.resolve("bin/steam_api64.dll")));
    }

    @Test
    void recreatesMissingGameFolder() throws Exception {
        FileOps.deleteRecursive(game);
        Optional<Path> kept = restore(true);

        assertTrue(kept.isEmpty());
        assertEquals("original", Files.readString(game.resolve("bin/steam_api64.dll")));
    }

    @Test
    void failedCopyLeavesGameUntouched() throws Exception {
        Path nothing = tmp.resolve("backups/Missing");
        assertThrows(IOException.class, () -> FolderRestoreJob.stage(nothing, game, Progress.NONE));
        assertEquals("emulator", Files.readString(game.resolve("bin/steam_api64.dll")));
        assertEquals(1, siblings());
    }

    @Test
    void failedSwapPutsGameFolderBack() throws Exception {
        Path notStaged = tmp.resolve("games/does-not-exist");
        assertThrows(IOException.class, () -> FolderRestoreJob.swap(notStaged, game, false));
        assertEquals("emulator", Files.readString(game.resolve("bin/steam_api64.dll")));
        assertEquals(1, siblings());
    }
}
