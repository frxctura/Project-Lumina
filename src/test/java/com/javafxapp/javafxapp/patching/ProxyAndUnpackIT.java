package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.common.Progress;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorInstaller;
import com.javafxapp.javafxapp.unpack.SteamStub;
import com.javafxapp.javafxapp.unpack.Steamless;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Downloads the real SmokeAPI and Steamless and runs the whole flow against a fake game folder.
 * Needs the network and a display, so it only runs with LUMINA_IT=1. Set LOCALAPPDATA to a scratch folder
 * first: Lumina keeps the downloads in %LOCALAPPDATA%\Lumina.
 */
@EnabledIfEnvironmentVariable(named = "LUMINA_IT", matches = "1")
class ProxyAndUnpackIT {

    @TempDir Path tmp;

    @BeforeAll
    static void startToolkit() throws Exception {
        assertTrue(AppPaths.home().toString().toLowerCase().contains("scratch"),
                "refusing to run against real app data: point LOCALAPPDATA at a scratch folder, was " + AppPaths.home());
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(30, TimeUnit.SECONDS));
    }

    /** A minimal PE file with a .bind section, which is all SteamStub detection looks at. */
    private static byte[] wrappedExe() {
        int peOffset = 0x80;
        ByteBuffer b = ByteBuffer.allocate(peOffset + 24 + 80 + 64).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short) 0x5A4D);
        b.putInt(0x3C, peOffset);
        b.putInt(peOffset, 0x00004550);
        b.putShort(peOffset + 6, (short) 2);
        b.position(peOffset + 24).put(".text".getBytes(StandardCharsets.US_ASCII));
        b.position(peOffset + 24 + 40).put(".bind".getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }

    @Test
    void smokeApiInstallsAndSteamlessRuns() throws Exception {
        // the real downloads
        EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        assertTrue(Emulator.SMOKEAPI.isInstalled());
        // the two DLLs differ in size, and the 32-bit one must not have ended up as the 64-bit one (or the reverse)
        assertTrue(Files.size(Emulator.SMOKEAPI.dll("steam_api.dll")) > 1_000_000, "smoke_api32.dll is stored as steam_api.dll");
        assertTrue(Files.size(Emulator.SMOKEAPI.dll("steam_api64.dll")) > 1_000_000, "smoke_api64.dll is stored as steam_api64.dll");
        assertNotEquals(Files.size(Emulator.SMOKEAPI.dll("steam_api.dll")), Files.size(Emulator.SMOKEAPI.dll("steam_api64.dll")));
        System.out.println("SmokeAPI version: " + Emulator.SMOKEAPI.installedVersion().orElse("?"));

        Steamless.install(StatusSink.NONE, Progress.NONE);
        assertTrue(Steamless.isInstalled());
        assertFalse(Files.exists(Steamless.dir().resolve("Steamless.exe")), "the GUI is not kept");
        assertTrue(Files.isDirectory(Steamless.dir().resolve("Plugins")));
        System.out.println("Steamless version: " + Steamless.installedVersion().orElse("?"));

        // Steamless starts and reports that a file it can't unpack failed, rather than failing to launch
        Path notStubbed = Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "notepad.exe"), tmp.resolve("notepad.exe"));
        IOException failure = assertThrows(IOException.class, () -> Steamless.unpack(notStubbed));
        System.out.println("Steamless on a normal exe: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("can't handle it"), failure.getMessage());
        assertFalse(failure.getMessage().contains("Could not start"), failure.getMessage());

        // patching a fake game with the proxy strategy, with unpacking on
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Files.writeString(game.resolve("steam_api64.dll"), "the real steam api");
        byte[] wrapped = wrappedExe();
        Files.write(game.resolve("game.exe"), wrapped);
        assertTrue(SteamStub.isProtected(game.resolve("game.exe")));

        var request = new CrackJob.Request(game, Emulator.SMOKEAPI, true, "480", Optional.empty(), Optional.empty(), true);
        CrackJob.Plan plan = CrackJob.plan(request);
        assertEquals(1, plan.toPatch());
        assertTrue(plan.dlls().get(0).proxy());
        assertEquals(1, plan.stubExes().size());

        CrackJob.Result r = run(request);
        assertEquals(1, r.patched());
        assertEquals(1, r.verified());
        assertFalse(r.rolledBack());
        assertEquals("the real steam api", Files.readString(game.resolve("steam_api64_o.dll")), "the original sits beside the proxy");
        assertEquals(Files.size(Emulator.SMOKEAPI.dll("steam_api64.dll")), Files.size(game.resolve("steam_api64.dll")));
        assertFalse(Files.exists(game.resolve("steam_api64.dll.bak")), "proxy mode does not also write a .bak");
        assertFalse(Files.exists(game.resolve("steam_appid.txt")), "proxies are not given an App ID file");
        assertTrue(r.dllBackups().contains(game.resolve("steam_api64_o.dll")));
        // Steamless cannot unpack a stub-less test file: the exe stays as it was and leaves nothing behind
        assertEquals(1, r.unpackFailed());
        assertEquals(0, r.unpacked());
        assertArrayEquals(wrapped, Files.readAllBytes(game.resolve("game.exe")));
        assertFalse(Files.exists(SteamApi.exeBackupOf(game.resolve("game.exe"))));
        assertFalse(Files.exists(game.resolve("game.exe.unpacked.exe")));

        // patching again changes nothing
        CrackJob.Result again = run(request);
        assertEquals(0, again.patched());
        assertEquals(1, again.upToDate());

        // restoring puts the original back and removes the proxy's copy
        RestoreJob restore = new RestoreJob(game, StatusSink.NONE);
        restore.run();
        assertEquals(1, restore.get());
        assertEquals("the real steam api", Files.readString(game.resolve("steam_api64.dll")));
        assertFalse(Files.exists(game.resolve("steam_api64_o.dll")));
    }

    /**
     * The success path against a real SteamStub executable. LUMINA_STUB_EXE names one; it is only ever read, the test
     * works on a copy in a temp folder.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LUMINA_STUB_EXE", matches = ".+")
    void realSteamStubExeIsUnpackedAndRestored() throws Exception {
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        if (!Steamless.isInstalled()) Steamless.install(StatusSink.NONE, Progress.NONE);

        Path source = Path.of(System.getenv("LUMINA_STUB_EXE"));
        Path game = Files.createDirectories(tmp.resolve("Real Game"));
        Path exe = Files.copy(source, game.resolve(source.getFileName()));
        Files.writeString(game.resolve("steam_api64.dll"), "the real steam api");
        String originalHash = PatchDetector.sha256(exe);
        assertEquals(PatchDetector.sha256(source), originalHash);
        assertTrue(SteamStub.isProtected(exe), "the test file must carry SteamStub DRM");

        var request = new CrackJob.Request(game, Emulator.SMOKEAPI, true, "", Optional.empty(), Optional.empty(), true);
        assertEquals(java.util.List.of(exe), CrackJob.plan(request).stubExes());

        CrackJob.Result r = run(request);
        System.out.println("unpacked=" + r.unpacked() + " failed=" + r.unpackFailed() + " patched=" + r.patched()
                + " size " + Files.size(source) + " -> " + Files.size(exe));
        assertEquals(1, r.unpacked(), "Steamless should unpack " + source.getFileName());
        assertEquals(0, r.unpackFailed());
        assertEquals(1, r.patched());
        assertFalse(SteamStub.isProtected(exe), "the wrapper is gone");
        assertNotEquals(originalHash, PatchDetector.sha256(exe), "the exe changed");
        Path bak = SteamApi.exeBackupOf(exe);
        assertEquals(originalHash, PatchDetector.sha256(bak), "the backup is the untouched original");
        assertTrue(r.dllBackups().contains(bak), "History gets to know about the exe backup");
        assertFalse(Files.exists(game.resolve(exe.getFileName() + ".unpacked.exe")), "no leftovers");

        // a second run finds nothing left to unpack
        CrackJob.Result again = run(request);
        assertEquals(0, again.unpacked());
        assertEquals(0, again.unpackFailed());

        // restore brings back the byte-identical original, and the DLL
        RestoreJob restore = new RestoreJob(game, StatusSink.NONE);
        restore.run();
        assertEquals(2, restore.get(), "the exe and the DLL");
        assertEquals(originalHash, PatchDetector.sha256(exe));
        assertTrue(SteamStub.isProtected(exe));
        assertFalse(Files.exists(bak));
        assertEquals("the real steam api", Files.readString(game.resolve("steam_api64.dll")));
        assertEquals(originalHash, PatchDetector.sha256(source), "the game's own file was never touched");
    }

    /** Patches, in place, a COPY of a real game (LUMINA_STUB_GAME_DIR) so it can be launched afterwards. Never point it at a real install. */
    @Test
    @EnabledIfEnvironmentVariable(named = "LUMINA_STUB_GAME_DIR", matches = ".+")
    void patchesACopyOfARealGame() throws Exception {
        Path game = Path.of(System.getenv("LUMINA_STUB_GAME_DIR"));
        assertTrue(game.toString().toLowerCase().contains("scratch"), "refusing to patch outside a scratch copy: " + game);
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        if (!Steamless.isInstalled()) Steamless.install(StatusSink.NONE, Progress.NONE);

        var request = new CrackJob.Request(game, Emulator.SMOKEAPI, true, "1167630", Optional.empty(), Optional.empty(), true);
        CrackJob.Plan plan = CrackJob.plan(request);
        System.out.println("plan: dlls=" + plan.dlls().size() + " stubExes=" + plan.stubExes());
        CrackJob.Result r = run(request);
        System.out.println("result: patched=" + r.patched() + " verified=" + r.verified() + " unpacked=" + r.unpacked()
                + " unpackFailed=" + r.unpackFailed() + " rolledBack=" + r.rolledBack());
        assertFalse(r.rolledBack());
        assertEquals(plan.stubExes().size(), r.unpacked());
        assertEquals(0, r.unpackFailed());
        assertTrue(SteamStub.find(game).isEmpty(), "no wrapped executable left");
        assertTrue(Files.exists(game.resolve("steam_api64_o.dll")));
    }

    @Test
    void failedPatchUndoesTheProxyInstall() throws Exception {
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        Path game = Files.createDirectories(tmp.resolve("Broken"));
        // already an emulator DLL and nothing was kept: SmokeAPI would have nothing to load
        Files.copy(Emulator.SMOKEAPI.dll("steam_api64.dll"), game.resolve("steam_api64.dll"));
        byte[] before = Files.readAllBytes(game.resolve("steam_api64.dll"));

        // up to date only counts with an original beside it
        var request = new CrackJob.Request(game, Emulator.SMOKEAPI, true, "", Optional.empty(), Optional.empty(), false);
        CrackJob.Plan plan = CrackJob.plan(request);
        assertEquals(1, plan.toPatch());
        assertTrue(plan.problem().isPresent());

        CrackJob.Result r = run(request);
        assertTrue(r.rolledBack());
        assertEquals(1, r.failed());
        assertArrayEquals(before, Files.readAllBytes(game.resolve("steam_api64.dll")));
        assertFalse(Files.exists(game.resolve("steam_api64_o.dll")));
    }

    @Test
    void aGameWithNothingToChangeIsNotCopiedForNothing() throws Exception {
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        Path backups = Files.createDirectories(tmp.resolve("Backups"));
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Files.writeString(game.resolve("steam_api64.dll"), "the real steam api");
        Files.writeString(game.resolve("data.pak"), "lots of data");
        var request = new CrackJob.Request(game, Emulator.SMOKEAPI, true, "", Optional.of(backups), Optional.empty(), false);

        CrackJob.Result first = run(request);
        assertEquals(1, first.patched());
        assertTrue(first.folderBackup().isPresent(), "the first patch changes things, so it gets its copy");
        assertTrue(Files.isDirectory(first.folderBackup().get()));

        // the same again: every DLL is up to date, so there's nothing a backup could protect
        assertTrue(CrackJob.plan(request).folderBackup().isEmpty(), "the preview doesn't promise a copy either");
        CrackJob.Result second = run(request);
        assertEquals(0, second.patched());
        assertEquals(1, second.upToDate());
        assertTrue(second.folderBackup().isEmpty());
        try (var folders = Files.list(backups)) {
            assertEquals(1, folders.count(), "still only the first copy, no second multi-gigabyte one");
        }
    }

    @Test
    void aFolderWithoutSteamApiDllsLeavesNoBackupBehind() throws Exception {
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        Path backups = Files.createDirectories(tmp.resolve("Backups"));
        Path game = Files.createDirectories(tmp.resolve("NoSteamHere"));
        Files.writeString(game.resolve("data.pak"), "lots of data");

        CrackJob.Result r = run(new CrackJob.Request(game, Emulator.SMOKEAPI, true, "", Optional.of(backups), Optional.empty(), false));

        assertEquals(0, r.patched());
        assertTrue(r.folderBackup().isEmpty());
        try (var folders = Files.list(backups)) {
            assertEquals(0, folders.count(), "nothing was copied");
        }
    }

    @Test
    void aBackupFolderInsideTheGameFailsBeforeTouchingAnything() throws Exception {
        if (!Emulator.SMOKEAPI.isInstalled()) EmulatorInstaller.install(Emulator.SMOKEAPI, StatusSink.NONE, Progress.NONE);
        Path game = Files.createDirectories(tmp.resolve("Game"));
        Files.writeString(game.resolve("steam_api64.dll"), "the real steam api");

        CrackJob job = new CrackJob(new CrackJob.Request(game, Emulator.SMOKEAPI, true, "", Optional.of(game.resolve("backups")),
                Optional.empty(), false), StatusSink.NONE);
        job.run();

        var failure = assertThrows(java.util.concurrent.ExecutionException.class, job::get);
        assertTrue(failure.getCause().getMessage().contains("inside the game folder"), failure.getCause().getMessage());
        assertEquals("the real steam api", Files.readString(game.resolve("steam_api64.dll")));
        assertFalse(Files.exists(game.resolve("backups")));
    }

    private static CrackJob.Result run(CrackJob.Request request) throws Exception {
        CrackJob job = new CrackJob(request, StatusSink.NONE);
        job.run();
        return job.get();
    }
}
