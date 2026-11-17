package com.javafxapp.javafxapp.backup;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.Progress;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.safety.DiskSpace;
import com.javafxapp.javafxapp.safety.ProcessGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class FolderRestoreJob extends Job<FolderRestoreJob.Result> {

    public static final String CHECK = "Check", COPY = "Copy", SWAP = "Swap";

    public record Result(Path gameDir, int files, Optional<Path> keptAs) {}

    private final Path backup, gameDir;
    private final boolean keepCurrent;

    public FolderRestoreJob(Path backup, Path gameDir, boolean keepCurrent, StatusSink log) {
        super(log);
        this.backup = backup;
        this.gameDir = gameDir;
        this.keepCurrent = keepCurrent;
    }

    @Override
    public List<String> steps() {
        return List.of(CHECK, COPY, SWAP);
    }

    @Override
    protected Result call() throws Exception {
        step(CHECK, "Checking backup and free space...");
        requireDirectory(backup);
        requireNotRunning(gameDir);
        long size = FileOps.totalSize(backup);
        DiskSpace.require(size, parentOf(gameDir), "restoring the folder backup");
        int files = FileOps.findFiles(backup, name -> true).size();
        checkCancelled();

        step(COPY, "Copying backup...");
        Path staged = stage(backup, gameDir, slice(0, 0.95, "Copying backup"));
        Optional<Path> keptAs;
        try {
            checkCancelled();
            step(SWAP, "Swapping folders...");
            requireNotRunning(gameDir);
            keptAs = swap(staged, gameDir, keepCurrent);
        } catch (Exception e) {
            FileOps.deleteQuietly(staged);
            throw e;
        }
        updateProgress(1, 1);
        log.info("Restored " + gameDir + " from " + backup);
        return new Result(gameDir, files, keptAs);
    }

    public static Path stage(Path backup, Path gameDir, Progress progress) throws IOException {
        Path parent = parentOf(gameDir);
        Files.createDirectories(parent);
        Path staged = unique(parent, gameDir.getFileName() + ".lumina-restore");
        try {
            FileOps.copyDirectory(backup, staged, progress);
        } catch (IOException e) {
            FileOps.deleteQuietly(staged);
            throw e;
        }
        return staged;
    }

    // both renames are in the same parent, if the second fails the first is undone
    public static Optional<Path> swap(Path staged, Path gameDir, boolean keepCurrent) throws IOException {
        Path parent = parentOf(gameDir);
        String name = gameDir.getFileName().toString();
        Path aside = null;
        try {
            if (Files.exists(gameDir)) {
                aside = keepCurrent ? unique(parent, name + " (before restore)") : unique(parent, name + ".lumina-old");
                Files.move(gameDir, aside);
            }
            Files.move(staged, gameDir);
        } catch (IOException e) {
            if (aside != null && Files.exists(aside) && !Files.exists(gameDir)) Files.move(aside, gameDir);
            FileOps.deleteQuietly(staged);
            throw e;
        }
        if (aside == null) return Optional.empty();
        if (keepCurrent) return Optional.of(aside);
        FileOps.deleteQuietly(aside);
        return Optional.empty();
    }

    private static void requireNotRunning(Path gameDir) throws IOException {
        if (!Files.isDirectory(gameDir)) return;
        List<ProcessGuard.RunningProcess> running = ProcessGuard.runningFrom(gameDir);
        if (!running.isEmpty()) {
            throw new IOException("Close the game first: "
                    + running.stream().map(ProcessGuard.RunningProcess::name).distinct().collect(Collectors.joining(", "))
                    + " is running from this folder.");
        }
    }

    private static Path parentOf(Path gameDir) throws IOException {
        Path parent = gameDir.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Cannot restore over a drive root: " + gameDir);
        return parent;
    }

    private static Path unique(Path parent, String name) {
        Path candidate = parent.resolve(name);
        for (int i = 2; Files.exists(candidate); i++) candidate = parent.resolve(name.endsWith(")")
                ? name.substring(0, name.length() - 1) + " " + i + ")"
                : name + "-" + i);
        return candidate;
    }
}
