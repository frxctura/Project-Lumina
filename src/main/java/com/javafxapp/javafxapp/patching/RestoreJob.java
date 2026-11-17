package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.StatusSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

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

        step(RESTORE, "Restoring " + backups.size() + " original DLL(s)...");
        int restored = 0;
        for (int i = 0; i < backups.size(); i++) {
            checkCancelled();
            Path bak = backups.get(i);
            Path original = SteamApi.originalOf(bak);
            try {
                Files.move(bak, original, StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored " + gameDir.relativize(original));
                restored++;
            } catch (IOException e) {
                log.error("Failed to restore " + gameDir.relativize(bak), e);
            }
            updateProgress(i + 1, backups.size());
        }
        updateMessage("Done");
        return restored;
    }
}
