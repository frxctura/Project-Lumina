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
import com.javafxapp.javafxapp.unpack.SteamStub;
import com.javafxapp.javafxapp.unpack.Steamless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class CrackJob extends Job<CrackJob.Result> {

    public static final String DOWNLOAD = "Download", BACKUP = "Backup", SCAN = "Scan", UNPACK = "Unpack",
            PATCH = "Patch", VERIFY = "Verify", MOVE = "Move";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");

    /** @param unpackDrm remove SteamStub DRM from the game's executables (with Steamless) before patching */
    public record Request(Path gameDir, Emulator emulator, boolean backupDlls, String appId,
                          Optional<Path> backupRoot, Optional<Path> moveRoot, boolean unpackDrm) {

        public Request(Path gameDir, Emulator emulator, boolean backupDlls, String appId,
                       Optional<Path> backupRoot, Optional<Path> moveRoot) {
            this(gameDir, emulator, backupDlls, appId, backupRoot, moveRoot, false);
        }
    }

    /**
     * @param unpacked     executables whose SteamStub DRM was removed
     * @param unpackFailed executables Steamless could not unpack; they are left as they were and the patch carries on
     */
    public record Result(int patched, int upToDate, int verified, int failed, boolean rolledBack, Path location,
                         List<Path> dllBackups, Optional<Path> folderBackup, boolean antivirus,
                         int unpacked, int unpackFailed) {}

    /** @param stubExes executables with SteamStub DRM, found whether or not unpacking is switched on */
    public record Plan(List<PlannedDll> dlls, long gameSize, Optional<Path> folderBackup, Optional<Path> moveTo,
                       Optional<String> problem, List<Path> stubExes, boolean unpackDrm) {
        public long toPatch()  { return dlls.stream().filter(d -> !d.upToDate()).count(); }
        public long upToDate() { return dlls.stream().filter(PlannedDll::upToDate).count(); }
    }

    /** @param proxy the emulator loads the original, which is moved to steam_api(64)_o.dll */
    public record PlannedDll(Path dll, boolean upToDate, boolean newBackup, boolean keepsBackup, boolean proxy) {}

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
        steps.add(SCAN);
        if (request.unpackDrm()) steps.add(UNPACK);
        steps.addAll(List.of(PATCH, VERIFY));
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
        checkTargets(req);
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

        // look before copying anything: a game with nothing to change shouldn't be copied for nothing
        List<Path> dlls = FileOps.findFiles(gameDir, SteamApi::isDll);
        List<Path> stubs = req.unpackDrm() && !dlls.isEmpty() ? SteamStub.find(gameDir) : List.of();
        boolean changes = !stubs.isEmpty() || dlls.stream().anyMatch(d -> !isUpToDate(d));

        Optional<Path> folderBackup = Optional.empty();
        Files.createDirectories(AppPaths.emulators());
        scratch = Files.createTempDirectory(AppPaths.emulators(), "undo-");
        try {
            if (req.backupRoot().isPresent() && !changes) {
                log.info("Nothing to change, so the folder backup is skipped.");
            } else if (req.backupRoot().isPresent()) {
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
            if (dlls.isEmpty()) {
                log.error("No steam_api(64).dll found in " + gameDir);
                return new Result(0, 0, 0, 0, false, gameDir, List.of(), folderBackup, false, 0, 0);
            }

            List<Path> backups = new ArrayList<>();
            int unpacked = 0, unpackFailed = 0;
            if (steps.contains(UNPACK)) {
                int[] counts = unpackExecutables(gameDir, stubs, backups);
                unpacked = counts[0];
                unpackFailed = counts[1];
                checkCancelled();
            }

            step(PATCH, "Patching " + dlls.size() + " file(s) with " + req.emulator() + "...");
            int failed = 0, upToDate = 0;
            List<Path> written = new ArrayList<>();
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
                return new Result(0, upToDate, 0, failed, true, gameDir, List.of(), Optional.empty(), antivirus, 0, 0);
            }

            committed = true; // verified, nothing gets undone after this
            Path location = move(gameDir, backups);
            updateProgress(1, 1);
            updateMessage(written.isEmpty() && unpacked == 0 ? "Already up to date" : "Done");
            return new Result(written.size(), upToDate, verified, 0, false, location, backups, folderBackup, false,
                    unpacked, unpackFailed);
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

    // Unpacking SteamStub DRM

    /** Unpacks every wrapped executable. One that Steamless can't handle is left as it was; the patch carries on. */
    private int[] unpackExecutables(Path gameDir, List<Path> stubs, List<Path> backups) throws Exception {
        step(UNPACK, "Looking for SteamStub DRM...");
        if (stubs.isEmpty()) {
            log.info("No SteamStub DRM found in the game's executables.");
            return new int[2];
        }
        log.info(stubs.size() + " executable(s) have SteamStub DRM.");
        if (!Steamless.isInstalled()) {
            updateMessage("Downloading Steamless...");
            Steamless.install(log, slice(0.55, 0.57, "Downloading Steamless"));
        }

        int unpacked = 0, failed = 0;
        for (int i = 0; i < stubs.size(); i++) {
            checkCancelled();
            updateMessage("Unpacking " + stubs.get(i).getFileName() + "  (" + (i + 1) + " of " + stubs.size() + ")");
            if (unpack(gameDir, stubs.get(i), backups)) unpacked++;
            else failed++;
            updateProgress(0.57 + 0.03 * (i + 1) / stubs.size(), 1);
        }
        return new int[]{unpacked, failed};
    }

    private boolean unpack(Path gameDir, Path exe, List<Path> backups) throws InterruptedException {
        Path relative = gameDir.relativize(exe);
        Path bak = SteamApi.exeBackupOf(exe);
        boolean madeBak = !Files.exists(bak);
        Path unpacked = exe.resolveSibling(exe.getFileName() + ".unpacked.exe");
        Path before = bak;
        try {
            if (madeBak) {
                Files.copy(exe, bak, StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                // an older backup is kept as it is, so the undo gets its own copy of the current file
                before = Files.createTempFile(scratch, "exe-", ".bin");
                Files.copy(exe, before, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            unpacked = Steamless.unpack(exe);
            Files.move(unpacked, exe, StandardCopyOption.REPLACE_EXISTING);
            if (SteamStub.isProtected(exe)) throw new IOException("the executable still has SteamStub DRM afterwards");
        } catch (InterruptedException e) {
            abandonUnpack(before, exe, bak, madeBak, unpacked);
            throw e;
        } catch (IOException e) {
            abandonUnpack(before, exe, bak, madeBak, unpacked);
            log.error("Could not unpack " + relative + ": " + Errors.describe(e));
            return false;
        }

        Path original = before;
        if (madeBak) undo.push(() -> delete(bak));
        undo.push(() -> restore(original, exe));
        backups.add(bak);
        log.info("Unpacked " + relative + ", original kept as " + bak.getFileName());
        return true;
    }

    /** Puts {@code exe} back as it was and removes what this attempt created. */
    private void abandonUnpack(Path before, Path exe, Path bak, boolean madeBak, Path unpacked) {
        if (Files.exists(before)) restore(before, exe);
        if (madeBak) delete(bak);
        delete(unpacked);
    }

    // Patching

    private Optional<Path> patch(Path dll) throws IOException {
        return req.emulator().isProxy() ? patchProxy(dll) : patchReplace(dll);
    }

    /** Overwrites the DLL. The original is kept as steam_api(64).dll.bak when asked for. */
    private Optional<Path> patchReplace(Path dll) throws IOException {
        Optional<Path> backup = Optional.empty();
        if (req.backupDlls()) {
            Path bak = SteamApi.backupOf(dll);
            if (Files.exists(bak)) {
                log.info("Keeping existing backup " + req.gameDir().relativize(bak));
                backup = Optional.of(bak);
            } else if (PatchDetector.isEmulatorDll(dll)) {
                Path original = SteamApi.proxyBackupOf(dll);
                if (Files.exists(original)) {
                    log.info("The original is kept as " + req.gameDir().relativize(original));
                    backup = Optional.of(original);
                } else {
                    log.info("Already an emulator DLL, no original to back up: " + req.gameDir().relativize(dll));
                }
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

    /** The emulator takes the DLL's place and loads the original, which has to stay beside it as steam_api(64)_o.dll. */
    private Optional<Path> patchProxy(Path dll) throws IOException {
        Path original = SteamApi.proxyBackupOf(dll);
        if (Files.exists(original)) {
            log.info("Keeping existing original " + req.gameDir().relativize(original));
        } else {
            Path bak = SteamApi.backupOf(dll);
            Path source;
            if (Files.exists(bak)) source = bak; // an earlier patch kept the real DLL here
            else if (!PatchDetector.isEmulatorDll(dll)) source = dll;
            else throw new IOException(req.gameDir().relativize(dll) + " is already an emulator DLL and no original is kept,"
                        + " so " + req.emulator() + " would have nothing to load. Verify the game's files in Steam first.");
            remember(original);
            Files.copy(source, original, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Original kept as " + req.gameDir().relativize(original));
        }

        remember(dll);
        Files.copy(emulatorDll(dll), dll, StandardCopyOption.REPLACE_EXISTING);
        log.info("Patched " + req.gameDir().relativize(dll));
        return Optional.of(original);
    }

    private void writeAppId(Path dll) throws IOException {
        if (!req.emulator().writesAppId() || !SteamStore.isValidAppId(req.appId())) return;
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
            return PatchDetector.sameContent(dll, emulatorDll(dll))
                    && (!req.emulator().isProxy() || Files.exists(SteamApi.proxyBackupOf(dll)));
        } catch (IOException e) {
            return false;
        }
    }

    // Undo

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
            if (!PatchDetector.sameContent(dll, emulatorDll(dll))) {
                antivirus = true;
                log.error(relative + " changed after patching. Antivirus software may have altered it.");
                return false;
            }
            if (req.emulator().isProxy() && !Files.exists(SteamApi.proxyBackupOf(dll))) {
                antivirus = true;
                log.error(req.gameDir().relativize(SteamApi.proxyBackupOf(dll)) + " disappeared after patching. Antivirus software may have quarantined it.");
                return false;
            }
            return true;
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

    // Planning

    public static Plan plan(Request req) throws IOException {
        Path gameDir = req.gameDir();
        requireDirectory(gameDir);
        boolean installed = req.emulator().isInstalled();
        boolean proxy = req.emulator().isProxy();
        List<String> problems = new ArrayList<>();
        List<PlannedDll> dlls = new ArrayList<>();
        for (Path dll : FileOps.findFiles(gameDir, SteamApi::isDll)) {
            Path emulatorDll = req.emulator().dll(dll.getFileName().toString().toLowerCase());
            boolean hasOriginal = Files.exists(SteamApi.proxyBackupOf(dll));
            boolean upToDate = installed && PatchDetector.sameContent(dll, emulatorDll) && (!proxy || hasOriginal);
            boolean hasBak = Files.exists(SteamApi.backupOf(dll));
            if (proxy) {
                if (!upToDate && !hasOriginal && !hasBak && PatchDetector.isEmulatorDll(dll))
                    problems.add(gameDir.relativize(dll) + " is already an emulator DLL and no original is kept, so "
                            + req.emulator() + " has nothing to load. Verify the game's files in Steam first.");
                dlls.add(new PlannedDll(dll, upToDate, !upToDate && !hasOriginal, hasOriginal, true));
            } else {
                boolean newBak = req.backupDlls() && !upToDate && !hasBak && !PatchDetector.isEmulatorDll(dll);
                dlls.add(new PlannedDll(dll, upToDate, newBak, hasBak, false));
            }
        }
        String name = gameDir.getFileName().toString();
        long size = FileOps.totalSize(gameDir);
        List<Path> stubs = SteamStub.find(gameDir);
        // the job skips the folder copy when it has nothing to change, so the preview doesn't promise one
        boolean changes = dlls.stream().anyMatch(d -> !d.upToDate()) || (req.unpackDrm() && !stubs.isEmpty() && !dlls.isEmpty());
        Optional<Path> backup = changes ? req.backupRoot().map(root -> freeBackupTarget(root, name)) : Optional.empty();
        Optional<Path> moveTo = req.moveRoot().map(root -> root.resolve(name));

        if (moveTo.isPresent() && Files.exists(moveTo.get()))
            problems.add("The move target already exists, so the game will stay where it is: " + moveTo.get());
        try {
            checkTargets(req);
            checkSpace(req, size);
        } catch (IOException e) {
            problems.add(Errors.describe(e));
        }
        Optional<String> problem = problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
        return new Plan(dlls, size, backup, moveTo, problem, stubs, req.unpackDrm());
    }

    static Path freeBackupTarget(Path root, String name) {
        Path target = root.resolve(name);
        if (!Files.exists(target)) return target;
        String stamped = name + " (backup " + LocalDateTime.now().format(STAMP) + ")";
        target = root.resolve(stamped);
        for (int i = 2; Files.exists(target); i++) target = root.resolve(stamped + " " + i);
        return target;
    }

    /**
     * A backup or move folder inside the game folder would make the copy include itself, over and over, until the
     * path gets too long, and a game can't be moved into itself.
     */
    public static void checkTargets(Request req) throws IOException {
        Path game = req.gameDir().toAbsolutePath().normalize();
        for (var target : List.of(Map.entry("backup", req.backupRoot()), Map.entry("move", req.moveRoot()))) {
            if (target.getValue().isPresent() && target.getValue().get().toAbsolutePath().normalize().startsWith(game))
                throw new IOException("The " + target.getKey() + " folder " + target.getValue().get() + " is inside the game folder."
                        + " Pick a folder outside " + game + " in Settings.");
        }
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
