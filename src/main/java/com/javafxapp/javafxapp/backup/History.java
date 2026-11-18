package com.javafxapp.javafxapp.backup;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.config.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public final class History {

    private History() {}

    private static Path dir() {
        return AppPaths.home().resolve("history");
    }

    public static synchronized List<HistoryEntry> load() {
        if (!Files.isDirectory(dir())) return List.of();
        List<HistoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            files.filter(p -> p.toString().endsWith(".properties")).forEach(p -> read(p).ifPresent(entries::add));
        } catch (IOException e) {
            Log.error("Could not read history", e);
        }
        entries.sort(Comparator.comparing(HistoryEntry::time).reversed());
        return entries;
    }

    public static Optional<HistoryEntry> latestFor(Path gameDir) {
        return load().stream()
                .filter(e -> e.action() == HistoryEntry.Action.PATCH && e.gameDir().equals(gameDir))
                .findFirst();
    }

    public static synchronized void add(HistoryEntry entry) {
        Properties p = new Properties();
        p.setProperty("time", String.valueOf(entry.time().toEpochMilli()));
        p.setProperty("action", entry.action().name());
        p.setProperty("game", entry.game());
        p.setProperty("gameDir", entry.gameDir().toString());
        p.setProperty("emulator", entry.emulator());
        p.setProperty("files", String.valueOf(entry.files()));
        p.setProperty("failed", String.valueOf(entry.failed()));
        p.setProperty("unpacked", String.valueOf(entry.unpacked()));
        for (int i = 0; i < entry.dllBackups().size(); i++)
            p.setProperty("dllBackup." + i, entry.dllBackups().get(i).toString());
        entry.folderBackup().ifPresent(f -> p.setProperty("folderBackup", f.toString()));

        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve(entry.id() + ".properties"))) {
                p.store(w, "Lumina history entry");
            }
        } catch (IOException e) {
            Log.error("Could not save history entry", e);
        }
    }

    public static synchronized void remove(String id) {
        try {
            Files.deleteIfExists(dir().resolve(id + ".properties"));
        } catch (IOException e) {
            Log.error("Could not delete history entry", e);
        }
    }

    public static synchronized void clear() {
        FileOps.deleteQuietly(dir());
    }

    private static Optional<HistoryEntry> read(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
            List<Path> backups = new ArrayList<>();
            for (int i = 0; p.containsKey("dllBackup." + i); i++) backups.add(Path.of(p.getProperty("dllBackup." + i)));

            String name = file.getFileName().toString();
            return Optional.of(new HistoryEntry(
                    name.substring(0, name.length() - ".properties".length()),
                    Instant.ofEpochMilli(Long.parseLong(p.getProperty("time"))),
                    HistoryEntry.Action.valueOf(p.getProperty("action")),
                    p.getProperty("game", "?"),
                    Path.of(p.getProperty("gameDir")),
                    p.getProperty("emulator", ""),
                    Integer.parseInt(p.getProperty("files", "0")),
                    Integer.parseInt(p.getProperty("failed", "0")),
                    backups,
                    Optional.ofNullable(p.getProperty("folderBackup")).map(Path::of),
                    Integer.parseInt(p.getProperty("unpacked", "0")))); // entries from before unpacking existed have none
        } catch (Exception e) {
            Log.error("Skipping unreadable history entry " + file, e);
            return Optional.empty();
        }
    }
}
