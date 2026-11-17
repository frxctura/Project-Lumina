package com.javafxapp.javafxapp.common;

import com.javafxapp.javafxapp.library.GameInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileOpsTest {

    @TempDir Path tmp;

    private Path makeGame() throws Exception {
        Path game = tmp.resolve("MyGame");
        Files.createDirectories(game.resolve("bin/x64"));
        Files.writeString(game.resolve("game.exe"), "exe");
        Files.writeString(game.resolve("bin/x64/steam_api64.dll"), "original");
        return game;
    }

    @Test
    void copyDirectoryCopiesNestedFiles() throws Exception {
        Path game = makeGame();
        Path copy = tmp.resolve("backup/MyGame");
        FileOps.copyDirectory(game, copy);
        assertEquals("original", Files.readString(copy.resolve("bin/x64/steam_api64.dll")));
        assertTrue(Files.exists(game.resolve("game.exe")), "source must be untouched");
    }

    @Test
    void moveDirectoryMovesTree() throws Exception {
        Path game = makeGame();
        Path target = tmp.resolve("moved");
        Files.createDirectories(target);
        FileOps.moveDirectory(game, target.resolve("MyGame"), Progress.NONE);
        assertFalse(Files.exists(game));
        assertTrue(Files.exists(target.resolve("MyGame/bin/x64/steam_api64.dll")));
    }

    @Test
    void moveRefusesToOverwrite() throws Exception {
        Path game = makeGame();
        Path existing = Files.createDirectories(tmp.resolve("other/MyGame"));
        assertThrows(FileAlreadyExistsException.class, () -> FileOps.moveDirectory(game, existing, Progress.NONE));
        assertTrue(Files.exists(game));
    }

    @Test
    void gameInfoFindsExeAndDlls() throws Exception {
        GameInfo info = GameInfo.scan(makeGame());
        assertEquals(2, info.fileCount());
        assertEquals(1, info.dllCount());
        assertEquals("game.exe", info.executable().orElseThrow().getFileName().toString());
    }

    @Test
    void formatSize() {
        assertEquals("512 B", FileOps.formatSize(512));
        assertEquals("1.50 KB", FileOps.formatSize(1536).replace(',', '.'));
    }

    @Test
    void copyReportsByteProgress() throws Exception {
        Path game = makeGame();
        long total = FileOps.totalSize(game);
        long[] last = {0, 0};
        FileOps.copyDirectory(game, tmp.resolve("copy"), (done, t) -> { last[0] = done; last[1] = t; });
        assertEquals(total, last[0]);
        assertEquals(total, last[1]);
    }

    @Test
    void interruptedCopyAborts() throws Exception {
        Path game = makeGame();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> FileOps.copyDirectory(game, tmp.resolve("copy"), (d, t) -> {}));
        } finally {
            Thread.interrupted();
        }
    }
}
