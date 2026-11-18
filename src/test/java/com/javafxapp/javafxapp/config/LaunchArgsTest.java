package com.javafxapp.javafxapp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaunchArgsTest {

    @TempDir Path tmp;

    @Test
    void keepsExistingFoldersInOrder() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("Game A"));
        Path b = Files.createDirectories(tmp.resolve("Game B"));

        assertEquals(List.of(b, a), LaunchArgs.folders(List.of(b.toString(), a.toString())), "the order they were given in");
    }

    @Test
    void ignoresEverythingThatIsNotAFolder() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Path file = Files.writeString(tmp.resolve("readme.txt"), "x");

        List<Path> folders = LaunchArgs.folders(List.of("--some-flag", file.toString(), tmp.resolve("gone").toString(),
                "bad|name?*", "", "   ", game.toString()));

        assertEquals(List.of(game), folders);
    }

    @Test
    void listsAFolderOnceEvenIfNamedTwice() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("Game"));

        List<Path> folders = LaunchArgs.folders(List.of(game.toString(), game.toString() + "\\", game.resolve("..").resolve("Game").toString()));

        assertEquals(List.of(game), folders);
    }

    @Test
    void noArgumentsMeansNoFolders() {
        assertEquals(List.of(), LaunchArgs.folders(List.of()));
    }
}
