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
