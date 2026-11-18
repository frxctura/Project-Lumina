package com.javafxapp.javafxapp.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchivesTest {

    @Test
    void picksRootDllsFromGoldbergLayout() throws IOException {
        Map<String, String> picks = Archives.choose(List.of(
                "experimental/steam_api.dll", "experimental/steam_api64.dll",
                "steam_api.dll", "steam_api64.dll", "linux/x86/libsteam_api.so"));
        assertEquals("steam_api.dll", picks.get("steam_api.dll"));
        assertEquals("steam_api64.dll", picks.get("steam_api64.dll"));
    }

    @Test
    void picksRegularBuildFromGbeForkLayout() throws IOException {
        Map<String, String> picks = Archives.choose(List.of(
                "release/experimental/x32/steam_api.dll", "release/experimental/x64/steam_api64.dll",
                "release/regular/x32/steam_api.dll", "release/regular/x64/steam_api64.dll",
                "release/steamclient_experimental/steam_api.dll"));
        assertEquals("release/regular/x32/steam_api.dll", picks.get("steam_api.dll"));
        assertEquals("release/regular/x64/steam_api64.dll", picks.get("steam_api64.dll"));
    }

    @Test
    void mapsSmokeApiDllsOntoSteamApiNames() throws IOException {
        Map<String, String> picks = Archives.choose(List.of(
                "smoke_api32.dll", "smoke_api64.dll", "libsmoke_api32.so", "libsmoke_api64.so", "SmokeAPI.config.json"),
                Emulator.SMOKEAPI::archiveName);
        assertEquals("smoke_api32.dll", picks.get("steam_api.dll"));
        assertEquals("smoke_api64.dll", picks.get("steam_api64.dll"));
    }

    @Test
    void failsWhenDllMissing() {
        assertThrows(IOException.class, () -> Archives.choose(List.of("steam_api.dll")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LUMINA_TEST_ZIP", matches = ".+")
    void extractsRealGoldbergZip(@TempDir Path tmp) throws IOException {
        Archives.extractSteamApi(Path.of(System.getenv("LUMINA_TEST_ZIP")), Archives.Format.ZIP, tmp);
        assertEquals(1488896, Files.size(tmp.resolve("steam_api.dll")));
        assertEquals(1958912, Files.size(tmp.resolve("steam_api64.dll")));
    }
}
