package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.library.SteamStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class SteamApi {

    public static final List<String> DLL_NAMES = List.of("steam_api.dll", "steam_api64.dll");
    public static final String BACKUP_SUFFIX = ".bak";
    public static final String APPID_FILE = "steam_appid.txt";

    private SteamApi() {}

    public static boolean isDll(String lowerName) {
        return DLL_NAMES.contains(lowerName);
    }

    public static boolean isBackup(String lowerName) {
        return lowerName.endsWith(BACKUP_SUFFIX)
                && isDll(lowerName.substring(0, lowerName.length() - BACKUP_SUFFIX.length()));
    }

    public static Path backupOf(Path dll) {
        return dll.resolveSibling(dll.getFileName() + BACKUP_SUFFIX);
    }

    public static Path originalOf(Path backup) {
        String name = backup.getFileName().toString();
        return backup.resolveSibling(name.substring(0, name.length() - BACKUP_SUFFIX.length()));
    }

    public static Optional<String> readAppId(Path file) {
        try {
            String id = Files.readString(file).trim();
            return SteamStore.isValidAppId(id) ? Optional.of(id) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
