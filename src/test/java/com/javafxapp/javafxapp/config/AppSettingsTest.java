package com.javafxapp.javafxapp.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {

    @Test
    void keepsTheNewestEntriesThatFit() {
        assertEquals(List.of("c", "d"), AppSettings.newest(List.of("a", "b", "c", "d"), 2, 100));
        assertEquals(List.of("a", "b", "c"), AppSettings.newest(List.of("a", "b", "c"), 100, 100));
        assertEquals(List.of(), AppSettings.newest(List.of(), 5, 100));
    }

    @Test
    void staysUnderTheRegistryValueLimit() {
        // 100 folders of ~110 characters is what a batch over a big library produces: more than a preference can hold
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 100; i++) paths.add("D:\\SteamLibrary\\steamapps\\common\\A Game With A Rather Long Folder Name " + i + "\\Binaries\\Win64\\Shipping");
        assertTrue(String.join("\n", paths).length() > 8192, "the scenario really is too long");

        List<String> kept = AppSettings.newest(paths, 100, 8000);

        assertTrue(String.join("\n", kept).length() <= 8000);
        assertEquals(paths.get(99), kept.get(kept.size() - 1), "the most recent one is always kept");
        assertTrue(kept.size() > 50, "and most of the rest");
    }

    @Test
    void anEntryTooLongForTheLimitIsDroppedNotTruncated() {
        assertEquals(List.of(), AppSettings.newest(List.of("x".repeat(50)), 5, 10));
    }
}
