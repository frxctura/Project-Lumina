package com.javafxapp.javafxapp.safety;

import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.patching.CrackJob;
import com.javafxapp.javafxapp.patching.PatchDetector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SafetyTest {

    private static final long ABSURD = Long.MAX_VALUE / 4;

    @TempDir Path tmp;

    @Test
    void sameContentComparesBytes() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.dll"), "emulator");
        Path b = Files.writeString(tmp.resolve("b.dll"), "emulator");
        Path c = Files.writeString(tmp.resolve("c.dll"), "emulatoR");
        Path d = Files.writeString(tmp.resolve("d.dll"), "emulator!");
        assertTrue(PatchDetector.sameContent(a, b));
        assertFalse(PatchDetector.sameContent(a, c));
        assertFalse(PatchDetector.sameContent(a, d));
    }

    @Test
    void sameContentFailsForMissingFile() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.dll"), "emulator");
        assertThrows(IOException.class, () -> PatchDetector.sameContent(tmp.resolve("gone.dll"), a));
    }

    @Test
    void inspectReportsStates() throws Exception {
        assertEquals(PatchDetector.State.NO_DLLS, PatchDetector.inspect(List.of()).state());
        Path original = Files.writeString(tmp.resolve("steam_api64.dll"), "not an emulator " + System.nanoTime());
        assertEquals(PatchDetector.State.ORIGINAL, PatchDetector.inspect(List.of(original)).state());
    }

    @Test
    void requireThrowsForAbsurdSize() {
        IOException e = assertThrows(IOException.class, () -> DiskSpace.require(ABSURD, tmp, "the test"));
        assertTrue(e.getMessage().contains("the test"));
    }

    @Test
    void requireAcceptsSmallSize() {
        assertDoesNotThrow(() -> DiskSpace.require(1, tmp.resolve("not/created/yet"), "a tiny file"));
    }

    @Test
    void checkSpaceIgnoresMissingTargets() {
        assertDoesNotThrow(() -> CrackJob.checkSpace(request(Optional.empty(), Optional.empty()), ABSURD));
    }

    @Test
    void checkSpaceIgnoresMoveOnSameDrive() {
        assertDoesNotThrow(() -> CrackJob.checkSpace(request(Optional.empty(), Optional.of(tmp.resolve("moved"))), ABSURD));
    }

    @Test
    void checkSpaceRequiresRoomForFolderBackup() {
        IOException e = assertThrows(IOException.class,
                () -> CrackJob.checkSpace(request(Optional.of(tmp.resolve("backups")), Optional.empty()), ABSURD));
        assertTrue(e.getMessage().contains("folder backup"));
    }

    private CrackJob.Request request(Optional<Path> backupRoot, Optional<Path> moveRoot) {
        return new CrackJob.Request(tmp.resolve("game"), Emulator.GOLDBERG, true, "", backupRoot, moveRoot);
    }
}
