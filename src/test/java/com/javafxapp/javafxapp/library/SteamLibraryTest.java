package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.common.Processes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SteamLibraryTest {

    private static final String LIBRARY_FOLDERS = """
            "libraryfolders"
            {
            	"0"
            	{
            		"path"		"C:\\\\Program Files (x86)\\\\Steam"
            		"label"		""
            		"apps"
            		{
            			"228980"		"122197158"
            		}
            	}
            	"1"
            	{
            		"path"		"E:\\\\SteamLibrary"
            	}
            }
            """;

    private static final String MANIFEST = """
            "AppState"
            {
            	"appid"		"1392820"
            	"name"		"Milk \\"inside\\" a bag"
            	"installdir"		"Milk inside a bag"
            	"SizeOnDisk"		"331241105"
            	"UserConfig" { "language" "english" }
            }
            """;

    @Test
    void parsesNestedKeyValues() {
        Map<String, Object> root = Vdf.parse("// comment\n\"a\" { \"B\" \"1\" \"c\" { \"d\" \"x\\\\y\" } }");
        Map<String, Object> a = Vdf.object(root, "A").orElseThrow();
        assertEquals(Optional.of("1"), Vdf.string(a, "b"));
        assertEquals(Optional.of("x\\y"), Vdf.object(a, "c").flatMap(c -> Vdf.string(c, "d")));
    }

    @Test
    void toleratesTruncatedAndUnquotedInput() {
        Map<String, Object> root = Vdf.parse("root { key value [$WIN32] nested { inner \"v\"");
        Map<String, Object> r = Vdf.object(root, "root").orElseThrow();
        assertEquals(Optional.of("value"), Vdf.string(r, "key"));
        assertEquals(Optional.of("v"), Vdf.object(r, "nested").flatMap(n -> Vdf.string(n, "inner")));
        assertTrue(Vdf.parse("").isEmpty());
        assertTrue(Vdf.parse("}}} \"lonely\"").isEmpty());
    }

    @Test
    void readsLibraryFolders() {
        assertEquals(List.of(Path.of("C:\\Program Files (x86)\\Steam"), Path.of("E:\\SteamLibrary")),
                SteamLibrary.parseLibraryFolders(LIBRARY_FOLDERS));
    }

    @Test
    void readsLegacyLibraryFolders() {
        String legacy = "\"LibraryFolders\" { \"TimeNextStatsReport\" \"123\" \"1\" \"D:\\\\Games\" }";
        assertEquals(List.of(Path.of("D:\\Games")), SteamLibrary.parseLibraryFolders(legacy));
    }

    @Test
    void readsManifest() {
        SteamLibrary.Manifest m = SteamLibrary.parseManifest(MANIFEST).orElseThrow();
        assertEquals("1392820", m.appId());
        assertEquals("Milk \"inside\" a bag", m.name());
        assertEquals("Milk inside a bag", m.installDir());
        assertEquals(331241105L, m.sizeOnDisk());
        assertFalse(SteamLibrary.isTool(m));
    }

    @Test
    void rejectsIncompleteManifestsAndFlagsTools() {
        assertTrue(SteamLibrary.parseManifest("\"AppState\" { \"appid\" \"1\" }").isEmpty());
        SteamLibrary.Manifest redist = SteamLibrary.parseManifest(
                "\"AppState\" { \"appid\" \"228980\" \"name\" \"Steamworks Common Redistributables\" \"installdir\" \"Steamworks Shared\" }").orElseThrow();
        assertTrue(SteamLibrary.isTool(redist));
        assertEquals(-1, SteamLibrary.parseManifest("\"AppState\" { \"appid\" \"5\" \"installdir\" \"x\" \"SizeOnDisk\" \"?\" }").orElseThrow().sizeOnDisk());
    }

    @Test
    void readsSteamPathFromRegQuery() {
        String out = "\r\nHKEY_CURRENT_USER\\Software\\Valve\\Steam\r\n    SteamPath    REG_SZ    c:/program files (x86)/steam\r\n\r\n";
        assertEquals(Optional.of("c:/program files (x86)/steam"), SteamLibrary.parseRegQuery(out));
        assertTrue(SteamLibrary.parseRegQuery("ERROR: The system was unable to find the specified registry key").isEmpty());
    }

    @Test
    void parsesOnlyTheValueThatWasAskedFor() {
        String out = "HKEY_X\\Y\r\n    Other    REG_SZ    nope\r\n    SteamPath    REG_SZ    D:\\Steam Games\r\n    SteamPathExtra    REG_SZ    also no\r\n";
        assertEquals(Optional.of("D:\\Steam Games"), SteamLibrary.parseRegQuery(out, "SteamPath"));
        assertEquals(Optional.of("nope"), SteamLibrary.parseRegQuery(out, "Other"));
        assertTrue(SteamLibrary.parseRegQuery(out, "Missing").isEmpty());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsARegistryValueWithCharactersOutsideTheConsoleCodePage() throws Exception {
        String key = "HKCU\\Software\\LuminaTest-" + UUID.randomUUID();
        String value = "D:\\Spiele Gr\u00f6\u00dfe\\Steam \u65e5\u672c";
        try {
            Processes.Result added = Processes.run(new ProcessBuilder("reg", "add", key, "/v", "SteamPath", "/t", "REG_SZ", "/d", value, "/f"),
                    Duration.ofSeconds(20));
            assertTrue(added.succeeded(), added.output());

            assertEquals(Optional.of(value), SteamLibrary.registryValue(key, "SteamPath"));
            assertTrue(SteamLibrary.registryValue(key, "NoSuchValue").isEmpty());
        } finally {
            Processes.run(new ProcessBuilder("reg", "delete", key, "/f"), Duration.ofSeconds(20));
        }
    }
}
