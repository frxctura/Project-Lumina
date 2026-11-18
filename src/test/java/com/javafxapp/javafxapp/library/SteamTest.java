package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.patching.SteamApi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SteamTest {

    @Test
    void recognisesDllsAndBackups() {
        assertTrue(SteamApi.isDll("steam_api64.dll"));
        assertTrue(SteamApi.isBackup("steam_api.dll.bak"));
        assertFalse(SteamApi.isBackup("other.dll.bak"));
        assertEquals(Path.of("a", "steam_api.dll"), SteamApi.originalOf(SteamApi.backupOf(Path.of("a", "steam_api.dll"))));
    }

    @Test
    void recognisesProxyOriginalsAndExeBackups() {
        assertTrue(SteamApi.isBackup("steam_api64_o.dll"));
        assertTrue(SteamApi.isBackup("steam_api_o.dll"));
        assertFalse(SteamApi.isBackup("other_o.dll"));
        assertTrue(SteamApi.isBackup("game.exe.steamstub.bak"));
        assertFalse(SteamApi.isBackup("game.dll.steamstub.bak"));
        assertFalse(SteamApi.isBackup("game.exe.bak")); // a .bak Lumina didn't make is left alone

        Path dll = Path.of("g", "steam_api64.dll");
        assertEquals(Path.of("g", "steam_api64_o.dll"), SteamApi.proxyBackupOf(dll));
        assertEquals(dll, SteamApi.originalOf(SteamApi.proxyBackupOf(dll)));
        Path exe = Path.of("g", "Game.exe");
        assertEquals(Path.of("g", "Game.exe.steamstub.bak"), SteamApi.exeBackupOf(exe));
        assertEquals(exe, SteamApi.originalOf(SteamApi.exeBackupOf(exe)));
    }

    @Test
    void validatesAppIds() {
        assertTrue(SteamStore.isValidAppId("480"));
        assertFalse(SteamStore.isValidAppId(""));
        assertFalse(SteamStore.isValidAppId("12a"));
    }

    @Test
    void unescapesStoreJson() {
        assertEquals("https://cdn/x.jpg", SteamStore.unescape("https:\\/\\/cdn\\/x.jpg"));
        assertEquals("Pokémon \"X\"", SteamStore.unescape("Pok\\u00e9mon \\\"X\\\""));
    }
}
