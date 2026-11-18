package com.javafxapp.javafxapp.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** @param dllBackups every file the original was kept in: .bak DLLs, _o.dll proxy originals and unpacked-exe backups
 *  @param unpacked   executables whose SteamStub DRM was removed in a patch */
public record HistoryEntry(String id, Instant time, Action action, String game, Path gameDir, String emulator,
                           int files, int failed, List<Path> dllBackups, Optional<Path> folderBackup, int unpacked) {

    public enum Action { PATCH, RESTORE }

    public static HistoryEntry patch(String game, Path location, String emulator, int files, int failed,
                                     List<Path> dllBackups, Optional<Path> folderBackup) {
        return patch(game, location, emulator, files, failed, 0, dllBackups, folderBackup);
    }

    public static HistoryEntry patch(String game, Path location, String emulator, int files, int failed, int unpacked,
                                     List<Path> dllBackups, Optional<Path> folderBackup) {
        return new HistoryEntry(UUID.randomUUID().toString(), Instant.now(), Action.PATCH, game, location,
                emulator, files, failed, List.copyOf(dllBackups), folderBackup, unpacked);
    }

    public static HistoryEntry restore(String game, Path location, int files) {
        return new HistoryEntry(UUID.randomUUID().toString(), Instant.now(), Action.RESTORE, game, location,
                "", files, 0, List.of(), Optional.empty(), 0);
    }

    public static HistoryEntry restoreFolder(String game, Path location, int files, Path source) {
        return new HistoryEntry(UUID.randomUUID().toString(), Instant.now(), Action.RESTORE, game, location,
                "", files, 0, List.of(), Optional.of(source), 0);
    }

    public HistoryEntry withoutFolderBackup() {
        return new HistoryEntry(id, time, action, game, gameDir, emulator, files, failed, dllBackups, Optional.empty(), unpacked);
    }

    public boolean ownsFolderBackup() {
        return action == Action.PATCH && folderBackup.isPresent();
    }

    public boolean isFolderRestore() {
        return action == Action.RESTORE && folderBackup.isPresent();
    }

    public long existingDllBackups() {
        return dllBackups.stream().filter(Files::exists).count();
    }

    public boolean folderBackupExists() {
        return folderBackup.map(Files::isDirectory).orElse(false);
    }

    public boolean gameDirExists() {
        return Files.isDirectory(gameDir);
    }
}
