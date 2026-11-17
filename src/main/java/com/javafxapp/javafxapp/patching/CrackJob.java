package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorInstaller;
import com.javafxapp.javafxapp.library.SteamStore;
import com.javafxapp.javafxapp.safety.DiskSpace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class CrackJob extends Job<CrackJob.Result> {

    public static final String DOWNLOAD = "Download", BACKUP = "Backup", SCAN = "Scan", PATCH = "Patch",
            VERIFY = "Verify", MOVE = "Move";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");

    public record Request(Path gameDir, Emulator emulator, boolean backupDlls, String appId,
                          Optional<Path> backupRoot, Optional<Path> moveRoot) {}

    public record Result(int patched, int upToDate, int verified, int failed, boolean rolledBack, Path location,
                         List<Path> dllBackups, Optional<Path> folderBackup, boolean antivirus) {}

    public record Plan(List<PlannedDll> dlls, long gameSize, Optional<Path> folderBackup, Optional<Path> moveTo,
                       Optional<String> problem) {
        public long toPatch()  { return dlls.stream().filter(d -> !d.upToDate()).count(); }
        public long upToDate() { return dlls.stream().filter(PlannedDll::upToDate).count(); }
    }

    public record PlannedDll(Path dll, boolean upToDate, boolean newBackup, boolean keepsBackup) {}

    private final Request req;
    private final List<String> steps = new ArrayList<>();
    private final Deque<Runnable> undo = new ArrayDeque<>();
    private final Set<Path> remembered = new HashSet<>();
    private Path scratch;
    private boolean committed, antivirus;

    public CrackJob(Request request, StatusSink log) {
        super(log);
        this.req = request;
        if (!request.emulator().isInstalled()) steps.add(DOWNLOAD);
        if (request.backupRoot().isPresent()) steps.add(BACKUP);
        steps.addAll(List.of(SCAN, PATCH, VERIFY));
        if (request.moveRoot().isPresent()) steps.add(MOVE);
    }

    @Override
    public List<String> steps() {
        return steps;
    }

    @Override
    protected Result call() throws Exception {
        Path gameDir = req.gameDir();
        requireDirectory(gameDir);
        updateProgress(-1, 1);
        if (req.backupRoot().isPresent() || req.moveRoot().isPresent()) {
            updateMessage("Checking free space...");
            checkSpace(req, FileOps.totalSize(gameDir));
        }

        if (steps.contains(DOWNLOAD)) {
            step(DOWNLOAD, "Downloading " + req.emulator() + "...");
            EmulatorInstaller.install(req.emulator(), log, slice(0, 0.25, "Downloading " + req.emulator()));
        }
        checkCancelled();

        Optional<Path> folderBackup = Optional.empty();
        Files.createDirectories(AppPaths.emulators());
        scratch = Files.createTempDirectory(AppPaths.emulators(), "undo-");
        try {
            if (req.backupRoot().isPresent()) {
                Path target = freeBackupTarget(req.backupRoot().get(), gameDir.getFileName().toString());
                step(BACKUP, "Backing up game directory...");
                try {
                    FileOps.copyDirectory(gameDir, target, slice(0.25, 0.6, "Backing up"));
                } catch (IOException e) {
                    FileOps.deleteQuietly(target);
                    throw e;
                }
                folderBackup = Optional.of(target);
                log.info("Game directory backed up to " + target);
            }
            checkCancelled();

            step(SCAN, "Scanning for steam_api DLLs...");
            List<Path> dlls = FileOps.findFiles(gameDir, SteamApi::isDll);
            if (dlls.isEmpty()) {
                log.error("No steam_api(64).dll found in " + gameDir);
                return new Result(0, 0, 0, 0, false, gameDir, List.of(), folderBackup, false);
            }

            step(PATCH, "Patching " + dlls.size() + " file(s) with " + req.emulator() + "...");
            int failed = 0, upToDate = 0;
            List<Path> written = new ArrayList<>();
            List<Path> backups = new ArrayList<>();
            for (int i = 0; i < dlls.size(); i++) {
                checkCancelled();
                Path dll = dlls.get(i);
                try {
                    if (isUpToDate(dll)) {
                        upToDate++;
                        writeAppId(dll);
                        log.info("Already up to date: " + gameDir.relativize(dll));
                    } else {
                        patch(dll).ifPresent(backups::add);
                        written.add(dll);
                    }
                } catch (IOException e) {
                    failed++;
                    log.error("Failed to patch " + gameDir.relativize(dll), e);
                }
                updateProgress(0.6 + 0.2 * (i + 1) / dlls.size(), 1);
                updateMessage("Patched " + (i + 1) + " of " + dlls.size());
            }

            int verified = 0;
            if (!written.isEmpty()) {
                step(VERIFY, "Verifying " + written.size() + " patched file(s)...");
                for (int i = 0; i < written.size(); i++) {
                    checkCancelled();
                    if (verify(written.get(i))) verified++;
                    else failed++;
                    updateProgress(0.8 + 0.1 * (i + 1) / written.size(), 1);
                    updateMessage("Verified " + (i + 1) + " of " + written.size());
                }
                if (verified > 0) log.info("Verified " + verified + " of " + written.size() + " patched file(s).");
            }

            if (failed > 0) {
                rollBack(folderBackup);
                updateProgress(1, 1);
                updateMessage("Failed, changes undone");
                return new Result(0, upToDate, 0, failed, true, gameDir, List.of(), Optional.empty(), antivirus);
            }

            committed = true; // verified, nothing gets undone after this
            Path location = move(gameDir, backups);
            updateProgress(1, 1);
            updateMessage(written.isEmpty() ? "Already up to date" : "Done");
            return new Result(written.size(), upToDate, verified, 0, false, location, backups, folderBackup, false);
        } catch (Exception e) {
            if (!committed) rollBack(folderBackup);
            throw e;
        } finally {
            FileOps.deleteQuietly(scratch);
        }
    }

    private Path move(Path gameDir, List<Path> backups) throws IOException {
        if (req.moveRoot().isEmpty()) return gameDir;
        Path target = req.moveRoot().get().resolve(gameDir.getFileName().toString());
        step(MOVE, "Moving game directory...");
        try {
            FileOps.moveDirectory(gameDir, target, slice(0.9, 1, "Moving"));
        } catch (IOException e) {
            // already patched, keep the result so History still has the backups
            log.error("Move failed: " + Errors.describe(e));
        }
        if (!Files.exists(gameDir) && Files.isDirectory(target)) {
            backups.replaceAll(b -> target.resolve(gameDir.relativize(b).toString()));
            log.info("Moved to " + target);
            return target;
        }
        if (Files.isDirectory(target) && !Files.isSameFile(target, gameDir))
            log.error("The game may be split between " + gameDir + " and " + target);
        return gameDir;
    }

    private Optional<Path> patch(Path dll) throws IOException {
        Optional<Path> backup = Optional.empty();
        if (req.backupDlls()) {
            Path bak = SteamApi.backupOf(dll);
            if (Files.exists(bak)) {
                log.info("Keeping existing backup " + req.gameDir().relativize(bak));
                backup = Optional.of(bak);
            } else if (PatchDetector.isEmulatorDll(dll)) {
                log.info("Already an emulator DLL, no original to back up: " + req.gameDir().relativize(dll));
            } else {
                remember(bak);
                Files.copy(dll, bak, StandardCopyOption.COPY_ATTRIBUTES);
                backup = Optional.of(bak);
            }
        }

        remember(dll);
        Files.copy(emulatorDll(dll), dll, StandardCopyOption.REPLACE_EXISTING);
        writeAppId(dll);
        log.info("Patched " + req.gameDir().relativize(dll));
        return backup;
    }

    private void writeAppId(Path dll) throws IOException {
        if (!SteamStore.isValidAppId(req.appId())) return;
        Path appIdFile = dll.resolveSibling(SteamApi.APPID_FILE);
        remember(appIdFile);
        Files.writeString(appIdFile, req.appId());
        if (req.emulator().usesSteamSettings()) {
            Path settings = Files.createDirectories(dll.resolveSibling("steam_settings"));
            Path settingsAppId = settings.resolve(SteamApi.APPID_FILE);
            remember(settingsAppId);
            Files.writeString(settingsAppId, req.appId());
        }
    }

    private boolean isUpToDate(Path dll) {
        try {
            return PatchDetector.sameContent(dll, emulatorDll(dll));
        } catch (IOException e) {
            return false;
        }
    }

    private void remember(Path file) throws IOException {
        if (!remembered.add(file)) return;
        if (Files.exists(file)) {
            Path copy = scratch.resolve(String.valueOf(remembered.size()));
            Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
            undo.push(() -> restore(copy, file));
        } else {
            undo.push(() -> delete(file));
        }
    }

    private void rollBack(Optional<Path> folderBackup) {
        if (undo.isEmpty() && folderBackup.isEmpty()) return;
        boolean interrupted = Thread.interrupted(); // a cancel mustn't stop the undo halfway
        try {
            int changes = undo.size();
            while (!undo.isEmpty()) undo.pop().run();
            if (changes > 0) log.info("Undid " + changes + " change(s). The game folder is as it was before.");
            folderBackup.ifPresent(f -> {
                FileOps.deleteQuietly(f);
                log.info("Removed the folder backup made for this attempt: " + f);
            });
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void restore(Path copy, Path file) {
        try {
            Files.copy(copy, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            log.error("Could not put back " + req.gameDir().relativize(file) + ". A copy is in " + copy, e);
            Log.error("Undo failed for " + file, e);
        }
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Could not remove " + req.gameDir().relativize(file), e);
        }
    }

    // antivirus sometimes quarantines or rewrites the dll right after it's written
    private boolean verify(Path dll) {
        Path relative = req.gameDir().relativize(dll);
        try {
            if (PatchDetector.sameContent(dll, emulatorDll(dll))) return true;
            antivirus = true;
            log.error(relative + " changed after patching. Antivirus software may have altered it.");
        } catch (NoSuchFileException e) {
            antivirus = true;
            log.error(relative + " disappeared after patching. Antivirus software may have quarantined it.");
        } catch (IOException e) {
            log.error("Could not verify " + relative, e);
        }
        return false;
    }

    private Path emulatorDll(Path dll) {
        return req.emulator().dll(dll.getFileName().toString().toLowerCase());
    }

    public static Plan plan(Request req) throws IOException {
        Path gameDir = req.gameDir();
        requireDirectory(gameDir);
        boolean installed = req.emulator().isInstalled();
        List<PlannedDll> dlls = new ArrayList<>();
        for (Path dll : FileOps.findFiles(gameDir, SteamApi::isDll)) {
            Path emulatorDll = req.emulator().dll(dll.getFileName().toString().toLowerCase());
            boolean upToDate = installed && PatchDetector.sameContent(dll, emulatorDll);
            boolean hasBak = Files.exists(SteamApi.backupOf(dll));
            boolean newBak = req.backupDlls() && !upToDate && !hasBak && !PatchDetector.isEmulatorDll(dll);
            dlls.add(new PlannedDll(dll, upToDate, newBak, hasBak));
        }
        String name = gameDir.getFileName().toString();
        long size = FileOps.totalSize(gameDir);
        Optional<Path> backup = req.backupRoot().map(root -> freeBackupTarget(root, name));
        Optional<Path> moveTo = req.moveRoot().map(root -> root.resolve(name));

        Optional<String> problem = Optional.empty();
        if (moveTo.isPresent() && Files.exists(moveTo.get()))
            problem = Optional.of("The move target already exists, so the game will stay where it is: " + moveTo.get());
        try {
            checkSpace(req, size);
        } catch (IOException e) {
            problem = Optional.of(Errors.describe(e));
        }
        return new Plan(dlls, size, backup, moveTo, problem);
    }

    static Path freeBackupTarget(Path root, String name) {
        Path target = root.resolve(name);
        if (!Files.exists(target)) return target;
        String stamped = name + " (backup " + LocalDateTime.now().format(STAMP) + ")";
        target = root.resolve(stamped);
        for (int i = 2; Files.exists(target); i++) target = root.resolve(stamped + " " + i);
        return target;
    }

    public static void checkSpace(Request req, long gameSize) throws IOException {
        Optional<Path> move = req.moveRoot().filter(m -> !DiskSpace.sameDrive(req.gameDir(), m));
        Optional<Path> backup = req.backupRoot();
        boolean sharedDrive = backup.isPresent() && move.isPresent() && DiskSpace.sameDrive(backup.get(), move.get());
        if (sharedDrive) {
            DiskSpace.require(2 * gameSize, backup.get(), "the folder backup and the move");
            return;
        }
        if (backup.isPresent()) DiskSpace.require(gameSize, backup.get(), "the folder backup");
        if (move.isPresent()) DiskSpace.require(gameSize, move.get(), "moving the game");
    }
}
