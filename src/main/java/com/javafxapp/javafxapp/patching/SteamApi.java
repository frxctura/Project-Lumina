package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.library.SteamStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Names of the files Lumina reads, replaces and backs up next to a game. */
public final class SteamApi {

    public static final List<String> DLL_NAMES = List.of("steam_api.dll", "steam_api64.dll");
    public static final String BACKUP_SUFFIX = ".bak";
    /** SmokeAPI-style proxies expect the original DLL next to them under this name: steam_api64_o.dll. */
    public static final String PROXY_SUFFIX = "_o.dll";
    /** Backup of a game executable that was unpacked: game.exe.steamstub.bak. */
    public static final String EXE_BACKUP_SUFFIX = ".steamstub.bak";
    public static final String APPID_FILE = "steam_appid.txt";

    private SteamApi() {}

    public static boolean isDll(String lowerName) {
        return DLL_NAMES.contains(lowerName);
    }

    /** True for every file Lumina keeps the original in: .bak DLLs, _o.dll proxy originals, unpacked-exe backups. */
    public static boolean isBackup(String lowerName) {
        if (lowerName.endsWith(EXE_BACKUP_SUFFIX))
            return lowerName.length() > EXE_BACKUP_SUFFIX.length() + 4 && lowerName.substring(0, lowerName.length() - EXE_BACKUP_SUFFIX.length()).endsWith(".exe");
        if (lowerName.endsWith(PROXY_SUFFIX))
            return isDll(lowerName.substring(0, lowerName.length() - PROXY_SUFFIX.length()) + ".dll");
        return lowerName.endsWith(BACKUP_SUFFIX)
                && isDll(lowerName.substring(0, lowerName.length() - BACKUP_SUFFIX.length()));
    }

    public static Path backupOf(Path dll) {
        return dll.resolveSibling(dll.getFileName() + BACKUP_SUFFIX);
    }

    /** Where a proxy emulator expects the original: {@code steam_api64.dll} becomes {@code steam_api64_o.dll}. */
    public static Path proxyBackupOf(Path dll) {
        String name = dll.getFileName().toString();
        return dll.resolveSibling(name.substring(0, name.length() - ".dll".length()) + PROXY_SUFFIX);
    }

    public static Path exeBackupOf(Path exe) {
        return exe.resolveSibling(exe.getFileName() + EXE_BACKUP_SUFFIX);
    }

    public static Path originalOf(Path backup) {
        String name = backup.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.endsWith(EXE_BACKUP_SUFFIX)) return backup.resolveSibling(name.substring(0, name.length() - EXE_BACKUP_SUFFIX.length()));
        if (lower.endsWith(PROXY_SUFFIX)) return backup.resolveSibling(name.substring(0, name.length() - PROXY_SUFFIX.length()) + ".dll");
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
