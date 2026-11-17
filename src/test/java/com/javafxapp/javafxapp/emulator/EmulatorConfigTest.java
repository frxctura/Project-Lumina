package com.javafxapp.javafxapp.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorConfigTest {

    @TempDir Path dir;

    private static final EmulatorConfig SAMPLE = new EmulatorConfig("Lumi", "76561197960287930", "german", true, true,
            List.of(new EmulatorConfig.Dlc("1001", "Soundtrack"), new EmulatorConfig.Dlc("1002", "Art Book")));

    @Test
    void goldbergRoundTrip() throws Exception {
        SAMPLE.save(dir, Emulator.GOLDBERG);
        assertEquals("Lumi", Files.readString(dir.resolve("force_account_name.txt")));
        assertTrue(Files.exists(dir.resolve("offline.txt")));
        assertEquals(List.of("1001=Soundtrack", "1002=Art Book"), Files.readAllLines(dir.resolve("DLC.txt")));

        EmulatorConfig loaded = EmulatorConfig.load(dir, Emulator.GOLDBERG);
        assertEquals("Lumi", loaded.username());
        assertEquals("76561197960287930", loaded.steamId());
        assertEquals("german", loaded.language());
        assertTrue(loaded.offline());
        assertEquals(SAMPLE.dlcs(), loaded.dlcs());
    }

    @Test
    void goldbergBlankValuesRemoveFiles() throws Exception {
        SAMPLE.save(dir, Emulator.GOLDBERG);
        EmulatorConfig.empty().save(dir, Emulator.GOLDBERG);
        for (String f : List.of("force_account_name.txt", "force_steamid.txt", "force_language.txt", "offline.txt", "DLC.txt"))
            assertFalse(Files.exists(dir.resolve(f)), f);
    }

    @Test
    void gbeRoundTripKeepsUnknownKeysAndComments() throws Exception {
        Files.writeString(dir.resolve("configs.user.ini"),
                "# my notes\n[user::general]\naccount_name=Old\nip_country=DE\n\n[user::saves]\nlocal_save_path=./saves\n");
        Files.writeString(dir.resolve("configs.app.ini"), "[app::general]\nbranch_name=public\n[app::dlcs]\nunlock_all=0\n999=Old DLC\n");

        SAMPLE.save(dir, Emulator.GBE_FORK);

        String user = Files.readString(dir.resolve("configs.user.ini"));
        assertTrue(user.contains("# my notes"));
        assertTrue(user.contains("ip_country=DE"));
        assertTrue(user.contains("local_save_path=./saves"));
        assertTrue(user.contains("account_name=Lumi"));
        assertFalse(user.contains("account_name=Old"));
        String app = Files.readString(dir.resolve("configs.app.ini"));
        assertTrue(app.contains("branch_name=public"));
        assertFalse(app.contains("999=Old DLC"));

        EmulatorConfig loaded = EmulatorConfig.load(dir, Emulator.GBE_FORK);
        assertEquals(SAMPLE, loaded);
    }

    @Test
    void parsesDlcLines() {
        assertEquals("Name = with = signs", EmulatorConfig.parseDlc("12=Name = with = signs").orElseThrow().name());
        assertTrue(EmulatorConfig.parseDlc("# comment").isEmpty());
        assertTrue(EmulatorConfig.parseDlc("abc=def").isEmpty());
    }
}
