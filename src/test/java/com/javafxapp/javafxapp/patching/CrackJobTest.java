package com.javafxapp.javafxapp.patching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CrackJobTest {

    @TempDir Path tmp;

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
