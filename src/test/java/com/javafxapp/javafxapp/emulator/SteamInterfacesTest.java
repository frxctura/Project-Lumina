package com.javafxapp.javafxapp.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SteamInterfacesTest {

    @TempDir Path tmp;

    /** What a steam_api DLL looks like to the scanner: binary noise with the interface names in it. */
    private static byte[] dll(String... names) {
        StringBuilder sb = new StringBuilder("MZ\u0090\u0000 some code \u0001\u0002 ");
        for (String n : names) sb.append(n).append('\u0000').append("paddingÿ\u0000");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void findsVersionedInterfaceNames() {
        List<String> found = SteamInterfaces.scan(dll("SteamUser020", "STEAMAPPS_INTERFACE_VERSION008", "SteamFriends017"));
        assertEquals(List.of("STEAMAPPS_INTERFACE_VERSION008", "SteamFriends017", "SteamUser020"), found, "grouped by interface, in the tool's order");
    }

    @Test
    void doesNotMistakeLongerNamesForShorterOnes() {
        // SteamMatchMakingServers must not also be reported as SteamMatchMaking, nor GameServerStats as GameServer
        List<String> found = SteamInterfaces.scan(dll("SteamMatchMakingServers002", "SteamGameServerStats001", "SteamNetworkingSockets012"));
        assertEquals(List.of("SteamGameServerStats001", "SteamMatchMakingServers002", "SteamNetworkingSockets012"), found);
    }

    @Test
    void keepsOnlyClient017WhenSeveralClientVersionsArePresent() {
        assertEquals(List.of("SteamClient017"), SteamInterfaces.scan(dll("SteamClient012", "SteamClient017", "SteamClient020")));
        assertEquals(List.of("SteamClient012", "SteamClient020"), SteamInterfaces.scan(dll("SteamClient012", "SteamClient020")),
                "without 017 there's nothing to prefer");
    }

    @Test
    void listsARepeatedNameOnce() {
        assertEquals(List.of("SteamUtils009"), SteamInterfaces.scan(dll("SteamUtils009", "SteamUtils009", "SteamUtils009")));
    }

    @Test
    void controllerNameWithoutADigitSuffixIsFound() {
        assertEquals(List.of("STEAMCONTROLLER_INTERFACE_VERSION"), SteamInterfaces.scan(dll("STEAMCONTROLLER_INTERFACE_VERSION")));
    }

    @Test
    void ignoresNamesWithoutAVersion() {
        assertEquals(List.of(), SteamInterfaces.scan(dll("SteamUser", "SteamUserSomething", "steamuser020")));
    }

    @Test
    void writesTheFileTheEmulatorReads() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api64.dll"), dll("SteamUser021", "SteamClient017"));
        Path settings = game.resolve("steam_settings");

        List<String> written = SteamInterfaces.generate(settings);

        assertEquals(List.of("SteamClient017", "SteamUser021"), written);
        assertEquals("SteamClient017\r\nSteamUser021\r\n", Files.readString(settings.resolve("steam_interfaces.txt")));
        assertEquals(2, SteamInterfaces.existing(settings).orElseThrow());
    }

    @Test
    void readsTheKeptOriginalNotTheEmulatorDll() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        // the emulator's DLL answers to its own, different set of names
        Files.write(game.resolve("steam_api64.dll"), dll("SteamUser023", "SteamFriends018"));
        Files.write(game.resolve("steam_api64.dll.bak"), dll("SteamUser019"));

        assertEquals(List.of("SteamUser019"), SteamInterfaces.generate(game.resolve("steam_settings")));
    }

    @Test
    void readsTheProxyOriginalToo() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api64.dll"), dll("SteamUser023"));
        Files.write(game.resolve("steam_api64_o.dll"), dll("SteamUser020"));

        assertEquals(List.of("SteamUser020"), SteamInterfaces.generate(game.resolve("steam_settings")));
    }

    @Test
    void combinesBothDllsOfAGame() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api.dll"), dll("SteamUser020"));
        Files.write(game.resolve("steam_api64.dll"), dll("SteamUser020", "SteamUtils009"));

        assertEquals(List.of("SteamUser020", "SteamUtils009"), SteamInterfaces.generate(game.resolve("steam_settings")));
    }

    @Test
    void saysSoWhenTheGameNeedsNoFile() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api64.dll"), dll());
        IOException e = assertThrows(IOException.class, () -> SteamInterfaces.generate(game.resolve("steam_settings")));
        assertTrue(e.getMessage().contains("doesn't need the file"), e.getMessage());
        assertFalse(Files.exists(game.resolve("steam_settings").resolve("steam_interfaces.txt")), "nothing is written");
    }

    @Test
    void refusesADllThatCarriesEveryVersion() throws Exception {
        // some engines ship a steam_api DLL that names every historical version of every interface (Lethal Company's has 227)
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api64.dll"), dll("SteamUser019", "SteamUser020", "SteamUser021", "SteamUtils009", "SteamUtils010"));

        IOException e = assertThrows(IOException.class, () -> SteamInterfaces.generate(game.resolve("steam_settings")));

        assertTrue(e.getMessage().contains("several versions of the same interface"), e.getMessage());
        assertTrue(e.getMessage().contains("SteamUser and 1 more"), e.getMessage());
        assertFalse(Files.exists(game.resolve("steam_settings").resolve("steam_interfaces.txt")), "nothing misleading is written");
    }

    @Test
    void severalClientVersionsAreNotAProblem() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        Files.write(game.resolve("steam_api64.dll"), dll("SteamClient012", "SteamClient017", "SteamClient020", "SteamUser021"));

        assertEquals(List.of("SteamClient017", "SteamUser021"), SteamInterfaces.generate(game.resolve("steam_settings")));
    }

    @Test
    void realisticSingleVersionDllIsAccepted() {
        List<String> names = List.of("STEAMAPPS_INTERFACE_VERSION008", "SteamClient017", "SteamFriends017", "STEAMHTMLSURFACE_INTERFACE_VERSION_005",
                "STEAMINVENTORY_INTERFACE_V003", "SteamNetworkingSockets012", "SteamNetworking006", "STEAMCONTROLLER_INTERFACE_VERSION");
        assertEquals(List.of(), SteamInterfaces.withSeveralVersions(names));
        assertEquals(List.of("SteamNetworking"), SteamInterfaces.withSeveralVersions(List.of("SteamNetworking005", "SteamNetworking006")));
    }

    @Test
    void saysSoWhenThereIsNoDll() throws Exception {
        Path game = Files.createDirectories(tmp.resolve("game"));
        IOException e = assertThrows(IOException.class, () -> SteamInterfaces.generate(game.resolve("steam_settings")));
        assertTrue(e.getMessage().contains("no steam_api DLL"), e.getMessage());
    }

    @Test
    void removeDeletesTheFile() throws Exception {
        Path settings = Files.createDirectories(tmp.resolve("steam_settings"));
        Files.writeString(settings.resolve("steam_interfaces.txt"), "SteamUser021\r\n");
        assertTrue(SteamInterfaces.existing(settings).isPresent());
        SteamInterfaces.remove(settings);
        assertTrue(SteamInterfaces.existing(settings).isEmpty());
        assertDoesNotThrow(() -> SteamInterfaces.remove(settings), "removing it twice is fine");
    }

    @Test
    void onlyReplaceEmulatorsUseTheFile() {
        assertTrue(Emulator.GOLDBERG.supportsInterfacesFile());
        assertTrue(Emulator.GBE_FORK.supportsInterfacesFile());
        assertFalse(Emulator.SMOKEAPI.supportsInterfacesFile());
    }
}
