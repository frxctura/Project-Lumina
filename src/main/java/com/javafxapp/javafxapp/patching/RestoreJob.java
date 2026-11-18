package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.StatusSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RestoreJob extends Job<Integer> {

    public static final String SCAN = "Scan", RESTORE = "Restore";

    private final Path gameDir;

    public RestoreJob(Path gameDir, StatusSink log) {
        super(log);
        this.gameDir = gameDir;
    }

    @Override
    public List<String> steps() {
        return List.of(SCAN, RESTORE);
    }

    @Override
    protected Integer call() throws Exception {
        requireDirectory(gameDir);
        step(SCAN, "Looking for backups...");
        List<Path> backups = FileOps.findFiles(gameDir, SteamApi::isBackup);

        // one file can have several backups (steam_api64.dll.bak and steam_api64_o.dll): restore it once
        Map<Path, List<Path>> byOriginal = new LinkedHashMap<>();
        for (Path bak : backups) byOriginal.computeIfAbsent(SteamApi.originalOf(bak), k -> new ArrayList<>()).add(bak);

        step(RESTORE, "Restoring " + byOriginal.size() + " original file(s)...");
        int restored = 0, done = 0;
        for (Map.Entry<Path, List<Path>> item : byOriginal.entrySet()) {
            checkCancelled();
            Path original = item.getKey();
            List<Path> candidates = item.getValue();
            candidates.sort(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase().endsWith(SteamApi.PROXY_SUFFIX)));
            Path bak = candidates.get(0);
            try {
                Files.move(bak, original, StandardCopyOption.REPLACE_EXISTING);
                for (Path extra : candidates.subList(1, candidates.size())) Files.deleteIfExists(extra);
                log.info("Restored " + gameDir.relativize(original));
                restored++;
            } catch (IOException e) {
                log.error("Failed to restore " + gameDir.relativize(bak), e);
            }
            updateProgress(++done, byOriginal.size());
        }
        updateMessage("Done");
        return restored;
    }
}
