package com.javafxapp.javafxapp.ui.pages;

import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.library.CoverCache;
import com.javafxapp.javafxapp.library.SteamLibrary;
import com.javafxapp.javafxapp.patching.BatchJob.State;
import com.javafxapp.javafxapp.patching.BatchJob;
import com.javafxapp.javafxapp.patching.CrackJob;
import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.ui.components.GameTile;
import com.javafxapp.javafxapp.ui.components.QueueRow;
import com.javafxapp.javafxapp.ui.components.Toaster;
import com.javafxapp.javafxapp.ui.dialogs.DefenderPrompt;
import com.javafxapp.javafxapp.ui.shell.AppShell;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class LibraryController implements Page {

    private static final int STAGGER_MS = 30, MAX_STAGGER_MS = 450;

    private record Inspection(PatchDetector.Report report, long size) {}

    private final ExecutorService inspector = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "library-inspect");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService coverLoader = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "library-covers");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, SteamLibrary.Game> extras = new LinkedHashMap<>();
    private final Map<Path, GameTile> tiles = new HashMap<>();
    private final Map<String, Image> covers = new HashMap<>();
    private final Map<Path, QueueRow> queue = new LinkedHashMap<>();

    private AppShell shell;
    private SteamLibrary.Scan scan;
    private boolean scanning;
    private BatchJob batch;
    private List<QueueRow> batchRows = List.of();
    private CrackJob currentJob;
    private int finished;

    @FXML private Label countLabel, emptyIcon, emptyTitle, emptyText, queueCountLabel, batchSettings, batchCount, batchPercent;
    @FXML private TextField searchField;
    @FXML private Button refreshButton, addFolderButton, emptyAction, clearQueueButton, defenderButton, patchButton;
    @FXML private FlowPane libraryBar, grid;
    @FXML private VBox emptyState, queuePanel, queueList, batchProgressBox;
    @FXML private ProgressBar batchProgress;

    @FXML
    public void initialize() {
        searchField.textProperty().addListener((o, a, v) -> render());
        refreshButton.setOnAction(e -> rescan());
        addFolderButton.setOnAction(e -> chooseFolder());
        emptyAction.setText("ADD FOLDER…");
        emptyAction.setOnAction(e -> chooseFolder());
        clearQueueButton.setOnAction(e -> clearQueue());
        defenderButton.setOnAction(e -> DefenderPrompt.addFor(shell, List.copyOf(queue.keySet())));
        defenderButton.setTooltip(new Tooltip("Excludes the queued games' folders and the emulator folder from Microsoft Defender."));
        patchButton.setOnAction(e -> { if (batch != null) stopBatch(); else startBatch(); });
    }

    @Override
    public void attach(AppShell shell) {
        this.shell = shell;
    }

    @Override
    public void onShow() {
        if (scan == null && !scanning) rescan();
        updateBatchSettings();
    }

    @Override
    public void onClose() {
        if (batch != null) batch.stop();
        inspector.shutdownNow();
        coverLoader.shutdownNow();
    }

    public void enqueue(List<Path> dirs) {
        int added = 0;
        for (Path dir : dirs) {
            if (queue.containsKey(dir) || !Files.isDirectory(dir)) continue;
            addToQueue(knownGame(dir).orElseGet(() -> addExtra(dir)));
            added++;
        }
        render();
        if (added > 0) shell.toast(Toaster.Kind.INFO, "Added " + added + " game(s) to the batch queue.");
        else shell.toast(Toaster.Kind.INFO, "Those folders are already in the queue.");
    }

    private void rescan() {
        scanning = true;
        refreshButton.setDisable(true);
        countLabel.setText("Scanning…");
        Task<SteamLibrary.Scan> task = new Task<>() {
            @Override
            protected SteamLibrary.Scan call() {
                return SteamLibrary.scan();
            }
        };
        task.setOnSucceeded(e -> scanned(task.getValue()));
        task.setOnFailed(e -> {
            Log.error("Library scan failed", task.getException());
            shell.toast(Toaster.Kind.ERROR, "Could not read your Steam library: " + Errors.describe(task.getException()));
            scanned(new SteamLibrary.Scan(Optional.empty(), List.of(), List.of()));
        });
        Ui.start(task, "library-scan");
    }

    private void scanned(SteamLibrary.Scan result) {
        scanning = false;
        refreshButton.setDisable(batch != null);
        scan = result;
        tiles.clear();
        for (QueueRow row : queue.values()) {
            knownGame(row.dir).filter(g -> !g.appId().isEmpty()).ifPresent(g -> {
                row.setName(g.name());
                row.setAppId(g.appId());
            });
        }
        renderLibraries();
        render();
    }

    private void renderLibraries() {
        libraryBar.getChildren().clear();
        for (SteamLibrary.Library lib : scan.libraries()) {
            long count = scan.games().stream().filter(g -> g.dir().startsWith(lib.path())).count();
            Label chip = new Label(lib.path() + "  ·  " + (lib.online() ? count + (count == 1 ? " game" : " games") : "offline"));
            chip.getStyleClass().addAll("badge", lib.online() ? "badge-off" : "badge-warn");
            chip.setTooltip(new Tooltip(lib.online() ? "Steam library folder" : "This drive is not connected, so its games are hidden."));
            libraryBar.getChildren().add(chip);
        }
        libraryBar.setVisible(!libraryBar.getChildren().isEmpty());
        libraryBar.setManaged(libraryBar.isVisible());
    }

    private void render() {
        if (scan == null) return;
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<SteamLibrary.Game> all = allGames();
        List<SteamLibrary.Game> shown = all.stream()
                .filter(g -> g.name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        countLabel.setText(all.size() + (all.size() == 1 ? " game" : " games"));

        List<GameTile> nodes = new ArrayList<>();
        int fresh = 0;
        for (SteamLibrary.Game game : shown) {
            GameTile tile = tiles.get(game.dir());
            if (tile == null) {
                tile = createTile(game);
                Ui.reveal(tile, 260, Math.min(fresh++ * STAGGER_MS, MAX_STAGGER_MS));
            }
            nodes.add(tile);
        }
        grid.getChildren().setAll(nodes);
        updateEmptyState(all.isEmpty(), shown.isEmpty(), query);
    }

    private void updateEmptyState(boolean noGames, boolean noneShown, String query) {
        if (!noneShown) {
            emptyState.setVisible(false);
            return;
        }
        boolean steamMissing = scan.steam().isEmpty();
        long offline = scan.libraries().stream().filter(l -> !l.online()).count();
        if (!noGames) {
            showEmpty("⌕", "No matches", "No game name contains \"" + query + "\".", false);
        } else if (steamMissing) {
            showEmpty("⚠", "Steam not found",
                    "Lumina could not find a Steam installation. Add game folders by hand, or drop several folders on Home to queue them.", true);
        } else {
            showEmpty("∅", "No games installed",
                    "None of your Steam library folders contain installed games"
                            + (offline > 0 ? ", and " + offline + " library folder(s) are offline." : ".")
                            + " You can still add game folders by hand.", true);
        }
    }

    private void showEmpty(String icon, String title, String text, boolean action) {
        emptyIcon.setText(icon);
        emptyTitle.setText(title);
        emptyText.setText(text);
        emptyAction.setVisible(action);
        emptyAction.setManaged(action);
        if (!emptyState.isVisible()) Ui.reveal(emptyState, 280);
    }

    private List<SteamLibrary.Game> allGames() {
        Set<Path> steamDirs = new HashSet<>();
        scan.games().forEach(g -> steamDirs.add(g.dir()));
        return Stream.concat(scan.games().stream(), extras.values().stream().filter(g -> !steamDirs.contains(g.dir()))).toList();
    }

    private Optional<SteamLibrary.Game> knownGame(Path dir) {
        Stream<SteamLibrary.Game> steamGames = scan == null ? Stream.empty() : scan.games().stream();
        return Stream.concat(steamGames, extras.values().stream()).filter(g -> g.dir().equals(dir)).findFirst();
    }

    private SteamLibrary.Game addExtra(Path dir) {
        SteamLibrary.Game game = SteamLibrary.folder(dir);
        extras.put(dir, game);
        return game;
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add a game folder");
        File dir = chooser.showDialog(shell.window());
        if (dir == null) return;
        Path path = dir.toPath();
        if (knownGame(path).isPresent()) {
            shell.toast(Toaster.Kind.INFO, "That folder is already in the library.");
            return;
        }
        addExtra(path);
        searchField.clear();
        render();
    }

    private GameTile createTile(SteamLibrary.Game game) {
        Optional<String> url = game.cover();
        boolean remote = url.filter(u -> u.startsWith("http")).isPresent();
        Image cover = url.map(u -> covers.get(game.appId()))
                .or(() -> url.filter(u -> !remote).map(u -> covers.computeIfAbsent(u, k -> new Image(k, true))))
                .orElse(null);
        GameTile tile = new GameTile(game, cover, () -> toggle(game), () -> {
            if (batch == null) shell.openGame(game.dir());
        });
        tile.setSelected(queue.containsKey(game.dir()));
        tiles.put(game.dir(), tile);
        if (cover == null && remote) fetchCover(game, url.get(), tile);
        inspect(game, tile);
        return tile;
    }

    private void fetchCover(SteamLibrary.Game game, String url, GameTile tile) {
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return CoverCache.fetch(game.appId(), url);
            }
        };
        task.setOnSucceeded(e -> {
            Image image = new Image(task.getValue().toUri().toString(), true);
            covers.put(game.appId(), image);
            if (tiles.get(game.dir()) == tile) tile.setCover(image);
        });
        task.setOnFailed(e -> Log.error("Could not download the cover of " + game.name(), task.getException()));
        coverLoader.execute(task);
    }

    private void inspect(SteamLibrary.Game game, GameTile tile) {
        Task<Inspection> task = new Task<>() {
            @Override
            protected Inspection call() throws Exception {
                PatchDetector.Report report = PatchDetector.inspect(game.dir());
                return new Inspection(report, game.size() >= 0 ? game.size() : FileOps.totalSize(game.dir()));
            }
        };
        task.setOnSucceeded(e -> {
            if (tiles.get(game.dir()) != tile) return;
            tile.showReport(task.getValue().report());
            tile.setSize(task.getValue().size());
        });
        task.setOnFailed(e -> {
            if (tiles.get(game.dir()) == tile) tile.showError(Errors.describe(task.getException()));
        });
        inspector.execute(task);
    }

    private void toggle(SteamLibrary.Game game) {
        if (queue.containsKey(game.dir())) removeFromQueue(game.dir());
        else addToQueue(game);
    }

    private void addToQueue(SteamLibrary.Game game) {
        Path dir = game.dir();
        QueueRow row = new QueueRow(dir, game.name(), game.appId(), () -> removeFromQueue(dir));
        queue.put(dir, row);
        queueList.getChildren().add(row);
        Ui.slideIn(row, 20, 0, 220, 0);
        syncSelection(dir);
        updateQueuePanel();
    }

    private void removeFromQueue(Path dir) {
        QueueRow row = queue.get(dir);
        if (row == null || (batch != null && batchRows.contains(row))) return;
        queue.remove(dir);
        queueList.getChildren().remove(row);
        syncSelection(dir);
        updateQueuePanel();
    }

    private void clearQueue() {
        List.copyOf(queue.keySet()).forEach(this::removeFromQueue);
    }

    private void syncSelection(Path dir) {
        GameTile tile = tiles.get(dir);
        if (tile != null) tile.setSelected(queue.containsKey(dir));
    }

    private void updateQueuePanel() {
        boolean show = !queue.isEmpty() || batch != null;
        if (show && !queuePanel.isVisible()) {
            queuePanel.setManaged(true);
            Ui.slideIn(queuePanel, 30, 0, 260, 0);
        } else if (!show && queuePanel.isVisible()) {
            queuePanel.setVisible(false);
            queuePanel.setManaged(false);
        }
        queueCountLabel.setText(String.valueOf(queue.size()));
        clearQueueButton.setDisable(batch != null && queue.size() == batchRows.size());
        defenderButton.setDisable(batch != null);
        if (batch != null) return;
        long pending = queue.values().stream().filter(QueueRow::isPending).count();
        long retries = queue.values().stream().filter(QueueRow::isRetry).count();
        String games = pending == 1 ? " GAME" : " GAMES";
        patchButton.setText(pending == 0 ? "ALL DONE" : (retries == pending ? "RETRY " : "PATCH ") + pending + games);
        patchButton.setDisable(pending == 0);
    }

    private void updateBatchSettings() {
        String text = AppSettings.getEmulator() + "  ·  " + (AppSettings.isBackupDlls() ? ".bak backups" : "no .bak backups");
        if (AppSettings.backupTarget().isPresent()) text += "  ·  full folder copy";
        if (AppSettings.moveTarget().isPresent()) text += "  ·  move afterwards";
        batchSettings.setText(text);
        batchSettings.setTooltip(new Tooltip("Batch patching uses the options from Settings."));
    }

    private void startBatch() {
        List<QueueRow> rows = queue.values().stream().filter(QueueRow::isPending).toList();
        if (rows.isEmpty()) return;
        Optional<String> invalid = AppSettings.validate();
        if (invalid.isPresent()) {
            shell.toast(Toaster.Kind.ERROR, invalid.get().replace('\n', ' '), "Open settings", () -> shell.show(AppShell.View.SETTINGS));
            return;
        }
        var settings = new BatchJob.Settings(AppSettings.getEmulator(), AppSettings.isBackupDlls(),
                AppSettings.backupTarget(), AppSettings.moveTarget());
        patchButton.setDisable(true);
        DefenderPrompt.offer(shell, settings.emulator(), rows.stream().map(r -> r.dir).toList(), () -> {
            patchButton.setDisable(false);
            confirmAndRun(rows, settings);
        });
    }

    private void confirmAndRun(List<QueueRow> rows, BatchJob.Settings settings) {
        if (!AppSettings.isPreviewBeforePatch()) {
            if (Ui.prompt(shell.window())
                    .title("Patch " + games(rows.size()) + " with " + settings.emulator() + "?")
                    .message("Running games are patched at the end, once they close.")
                    .confirm("PATCH").ask()) runBatch(rows, settings);
            return;
        }
        previewBatch(rows, settings);
    }

    private record GamePlan(QueueRow row, Optional<CrackJob.Plan> plan, String error) {}

    private void previewBatch(List<QueueRow> rows, BatchJob.Settings settings) {
        patchButton.setDisable(true);
        patchButton.setText("CHECKING…");
        Task<List<GamePlan>> task = new Task<>() {
            @Override
            protected List<GamePlan> call() {
                List<GamePlan> plans = new ArrayList<>();
                for (QueueRow r : rows) {
                    var req = new CrackJob.Request(r.dir, settings.emulator(), settings.backupDlls(), r.appId(),
                            settings.backupRoot(), settings.moveRoot());
                    try {
                        plans.add(new GamePlan(r, Optional.of(CrackJob.plan(req)), ""));
                    } catch (Exception e) {
                        plans.add(new GamePlan(r, Optional.empty(), Errors.describe(e)));
                    }
                }
                return plans;
            }
        };
        task.setOnSucceeded(e -> {
            updateQueuePanel();
            if (confirmBatch(task.getValue(), settings)) runBatch(rows, settings);
        });
        task.setOnFailed(e -> {
            updateQueuePanel();
            shell.toast(Toaster.Kind.ERROR, "Could not check the queued games: " + Errors.describe(task.getException()));
        });
        Ui.start(task, "batch-preview");
    }

    private boolean confirmBatch(List<GamePlan> plans, BatchJob.Settings settings) {
        List<String> lines = new ArrayList<>();
        long totalSize = 0, toPatch = 0;
        for (GamePlan p : plans) {
            String line = p.row().name() + "  ·  ";
            if (p.plan().isEmpty()) {
                line += "can't check it: " + p.error();
            } else {
                CrackJob.Plan plan = p.plan().get();
                totalSize += plan.gameSize();
                toPatch += plan.toPatch();
                if (plan.dlls().isEmpty()) line += "no steam_api DLLs, skipped";
                else if (plan.toPatch() == 0) line += "already up to date";
                else line += plan.toPatch() + " DLL(s)" + (plan.upToDate() > 0 ? ", " + plan.upToDate() + " already up to date" : "");
                if (plan.problem().isPresent()) line += "\n⚠ " + plan.problem().get();
            }
            lines.add(line);
        }

        String message = toPatch + " DLL(s) will be replaced. "
                + (settings.backupDlls() ? "The originals are kept as .bak files." : "The originals are NOT kept, .bak is off.");
        if (settings.backupRoot().isPresent())
            message += " Each game is copied to " + settings.backupRoot().get() + " first (" + FileOps.formatSize(totalSize) + " in total).";
        if (settings.moveRoot().isPresent()) message += " Afterwards they're moved to " + settings.moveRoot().get() + ".";

        return Ui.prompt(shell.window())
                .title("Patch " + games(plans.size()) + " with " + settings.emulator() + "?")
                .message(message)
                .details(lines)
                .note("Running games are patched at the end, once they close.")
                .confirm("PATCH").width(520)
                .ask();
    }

    private static String games(long n) {
        return n == 1 ? "1 game" : n + " games";
    }

    private void runBatch(List<QueueRow> rows, BatchJob.Settings settings) {
        updateBatchSettings();
        List<BatchJob.Item> items = rows.stream().map(r -> new BatchJob.Item(r.dir, r.name(), r.appId())).toList();
        batch = new BatchJob(items, settings, (i, state, detail, job) -> itemUpdated(rows.get(i), state, detail, job), StatusSink.NONE);
        batchRows = rows;
        finished = 0;
        rows.forEach(r -> {
            r.setLocked(true);
            r.setState(State.QUEUED, "Waiting");
        });

        BatchJob job = batch;
        job.setOnSucceeded(e -> finishBatch(job.getValue()));
        job.setOnFailed(e -> {
            Log.error("Batch failed", job.getException());
            shell.toast(Toaster.Kind.ERROR, "Batch stopped: " + Errors.describe(job.getException()));
            finishBatch(null);
        });
        setBusy(true);
        Ui.start(job, "batch");
    }

    private void itemUpdated(QueueRow row, State state, String detail, CrackJob job) {
        if (state == State.QUEUED) {
            row.setWaiting(detail);
        } else if (state == State.RUNNING && job != null) {
            row.run(job);
            currentJob = job;
            job.progressProperty().addListener((o, a, p) -> { if (job == currentJob) updateOverall(p.doubleValue()); });
        } else {
            row.setState(state, detail);
            currentJob = null;
            finished++;
            updateOverall(0);
        }
    }

    private void updateOverall(double current) {
        int total = batchRows.size();
        double value = (finished + Math.max(0, current)) / total;
        Ui.animateProgress(batchProgress, value);
        batchPercent.setText(Math.round(value * 100) + "%");
        batchCount.setText(finished + " of " + total + " done");
    }

    private void stopBatch() {
        if (batch == null || !Ui.prompt(shell.window())
                .title("Stop the batch?")
                .message("The game being patched right now is put back how it was. Games that already finished stay patched.")
                .confirm("STOP").cancel("KEEP GOING").destructive()
                .ask()) return;
        batch.stop();
        patchButton.setText("STOPPING…");
        patchButton.setDisable(true);
    }

    private void finishBatch(BatchJob.Summary summary) {
        batch = null;
        currentJob = null;
        batchRows.forEach(r -> {
            r.setLocked(false);
            if (r.state() == State.RUNNING) r.setState(State.CANCELLED, "Cancelled");
            GameTile tile = tiles.get(r.dir);
            if (tile != null) knownGame(r.dir).ifPresent(g -> inspect(g, tile));
        });
        List<Path> moved = batchRows.stream().map(r -> r.dir).filter(d -> !Files.exists(d)).toList();
        moved.forEach(d -> {
            removeFromQueue(d);
            extras.remove(d);
        });
        setBusy(false);
        if (!moved.isEmpty() && !scanning) rescan();
        if (summary == null) return;

        String text = summary.done() + " patched"
                + (summary.failed() > 0 ? ", " + summary.failed() + " failed" : "")
                + (summary.skipped() > 0 ? ", " + summary.skipped() + " skipped" : "");
        shell.setStatus("Batch: " + text);
        if (summary.stopped()) shell.toast(Toaster.Kind.INFO, "Batch stopped. " + text + ".");
        else if (summary.failed() > 0) shell.toast(Toaster.Kind.ERROR, "Batch finished: " + text + ". See the queue for details.");
        else if (summary.done() > 0)
            shell.toast(Toaster.Kind.SUCCESS, "Batch finished: " + text + ".", "Show history", () -> shell.show(AppShell.View.HISTORY));
        else shell.toast(Toaster.Kind.INFO, "Batch finished: " + text + ".");
    }

    private void setBusy(boolean busy) {
        shell.setNavigationLocked(busy);
        if (busy) {
            shell.bindStatus(batch.messageProperty());
            batchProgress.setProgress(0);
            updateOverall(0);
            batchProgressBox.setManaged(true);
            Ui.reveal(batchProgressBox, 220);
        } else {
            shell.unbindStatus();
        }
        refreshButton.setDisable(busy || scanning);
        patchButton.setDisable(false);
        patchButton.getStyleClass().removeAll("accent", "danger");
        patchButton.getStyleClass().add(busy ? "danger" : "accent");
        if (busy) patchButton.setText("STOP");
        updateQueuePanel();
    }
}
