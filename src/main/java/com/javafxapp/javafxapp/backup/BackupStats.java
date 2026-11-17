package com.javafxapp.javafxapp.backup;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public record BackupStats(boolean gameExists, boolean folderExists, long folderBytes, int baksPresent, long bakBytes) {

    // backups never change once written, and walking a big one is slow
    private static final Map<Path, Long> FOLDER_SIZES = new ConcurrentHashMap<>();

    public record Totals(int backups, long folderBytes, long bakBytes, boolean complete) {}

    public static BackupStats measure(HistoryEntry e) {
        return measure(e, true);
    }

    public static BackupStats peek(HistoryEntry e) {
        return measure(e, false);
    }

    private static BackupStats measure(HistoryEntry e, boolean walk) {
        boolean folderExists = e.folderBackupExists();
        long folderBytes = 0;
        if (folderExists) folderBytes = walk ? folderSize(e.folderBackup().get()) : FOLDER_SIZES.getOrDefault(e.folderBackup().get(), -1L);
        else e.folderBackup().ifPresent(FOLDER_SIZES::remove);

        int baks = 0;
        long bakBytes = 0;
        for (Path bak : e.dllBackups()) {
            if (!Files.isRegularFile(bak)) continue;
            bakBytes += bak.toFile().length();
            baks++;
        }
        return new BackupStats(e.gameDirExists(), folderExists, folderBytes, baks, bakBytes);
    }

    public static void forget(Path folder) {
        FOLDER_SIZES.remove(folder);
    }

    public static Totals total(Map<HistoryEntry, BackupStats> stats) {
        Set<Path> folders = new HashSet<>();
        int backups = 0;
        long folderBytes = 0, bakBytes = 0;
        boolean complete = true;
        for (var item : stats.entrySet()) {
            HistoryEntry e = item.getKey();
            BackupStats s = item.getValue();
            if (e.action() != HistoryEntry.Action.PATCH) continue;
            boolean counted = false;
            if (s.folderExists() && folders.add(e.folderBackup().get())) {
                if (s.sizeKnown()) folderBytes += s.folderBytes();
                else complete = false;
                counted = true;
            }
            if (s.baksPresent() > 0) {
                bakBytes += s.bakBytes();
                counted = true;
            }
            if (counted) backups++;
        }
        return new Totals(backups, folderBytes, bakBytes, complete);
    }

    public boolean sizeKnown() {
        return folderBytes >= 0;
    }

    public boolean hasBackups() {
        return folderExists || baksPresent > 0;
    }

    public boolean anythingMissing(HistoryEntry e) {
        return !gameExists
                || (!e.dllBackups().isEmpty() && baksPresent == 0)
                || (e.folderBackup().isPresent() && !folderExists);
    }

    private static long folderSize(Path folder) {
        Long cached = FOLDER_SIZES.get(folder);
        if (cached != null) return cached;
        try {
            long size = FileOps.totalSize(folder);
            FOLDER_SIZES.put(folder, size);
            return size;
        } catch (IOException e) {
            Log.error("Could not measure " + folder, e);
            return 0;
        }
    }
}
