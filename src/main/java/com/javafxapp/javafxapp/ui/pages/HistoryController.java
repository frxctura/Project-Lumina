package com.javafxapp.javafxapp.ui.pages;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.backup.BackupStats;
import com.javafxapp.javafxapp.backup.FolderRestoreJob;
import com.javafxapp.javafxapp.backup.History;
import com.javafxapp.javafxapp.backup.HistoryEntry;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.safety.DiskSpace;
import com.javafxapp.javafxapp.safety.ProcessGuard;
import com.javafxapp.javafxapp.ui.components.Toaster;
import com.javafxapp.javafxapp.ui.shell.AppShell;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.Prompt;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class HistoryController implements Page {

    private static final PseudoClass MISSING = PseudoClass.getPseudoClass("missing");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");
    private static final int STAGGER_MS = 45, MAX_STAGGERED = 10;
    private static final long GB = 1024L * 1024 * 1024;

    private record Rule(String label, int days, long keepBytes) {
        static Rule age(int days)         { return new Rule("Older than " + days + " days", days, -1); }
        static Rule size(int gigabytes)   { return new Rule("Beyond the newest " + gigabytes + " GB", -1, gigabytes * GB); }
    }

    private static final List<Rule> CLEANUP_RULES = List.of(
            Rule.age(7), Rule.age(30), Rule.age(90), Rule.size(10), Rule.size(25), Rule.size(50));
    private static final Rule DEFAULT_RULE = CLEANUP_RULES.get(1);

    private enum Filter {
        ALL("All"), PATCHED("Patched"), RESTORED("Restored"), WITH_BACKUPS("With backups"), MISSING("Missing");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        boolean test(HistoryEntry e, BackupStats s) {
            return switch (this) {
                case ALL -> true;
                case PATCHED -> e.action() == HistoryEntry.Action.PATCH;
                case RESTORED -> e.action() == HistoryEntry.Action.RESTORE;
                case WITH_BACKUPS -> e.action() == HistoryEntry.Action.PATCH && s.hasBackups();
                case MISSING -> s.anythingMissing(e);
            };
        }
    }

    private record Item(HistoryEntry entry, BackupStats stats) {}

    private record Cleanup(int deleted, long freed, List<String> failures) {}

    private final Map<String, EntryCard> cards = new LinkedHashMap<>();
    private AppShell shell;
    private Filter filter = Filter.ALL;
    private boolean busy;
    private int generation;

    @FXML private Label countLabel, summaryLabel, emptyLabel;
    @FXML private Button cleanupButton, clearButton, openBackupFolderButton;
    @FXML private TextField searchField;
    @FXML private HBox filterBar;
    @FXML private VBox cardBox;

    @FXML
    public void initialize() {
        ToggleGroup chips = new ToggleGroup();
        for (Filter f : Filter.values()) {
            ToggleButton chip = new ToggleButton(f.label);
            chip.getStyleClass().add("filter-chip");
            chip.setToggleGroup(chips);
            chip.setUserData(f);
            chip.setSelected(f == filter);
            filterBar.getChildren().add(chip);
        }
        chips.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);
                return;
            }
            filter = (Filter) now.getUserData();
            applyFilter(new HashSet<>(cards.values()));
        });
        searchField.textProperty().addListener((o, a, b) -> applyFilter(Set.of()));

        clearButton.setOnAction(e -> {
            if (Ui.prompt(shell.window()).title("Clear the whole history?")
                    .message("Only the list is cleared. Your backups stay where they are, and you can still restore from them by hand.")
                    .confirm("CLEAR HISTORY").destructive().ask()) {
                History.clear();
                reload(false);
            }
        });
        cleanupButton.setOnAction(e -> showCleanup());
        openBackupFolderButton.setOnAction(e -> Ui.openFolder(Path.of(AppSettings.getBackupGameDirPath())));
    }

    @Override
    public void attach(AppShell shell) {
        this.shell = shell;
    }

    @Override
    public void onShow() {
        reload(true);
    }

    private void reload(boolean animateAll) {
        int gen = ++generation;
        background(() -> History.load().stream().map(e -> new Item(e, BackupStats.peek(e))).toList(), items -> {
            if (gen != generation) return;
            render(items, animateAll);
            if (items.stream().anyMatch(i -> !i.stats().sizeKnown())) measure(gen, items);
        }, "Could not read history");
    }

    private void measure(int gen, List<Item> items) {
        background(() -> items.stream().map(i -> new Item(i.entry(), BackupStats.measure(i.entry()))).toList(), measured -> {
            if (gen != generation) return;
            measured.forEach(i -> Optional.ofNullable(cards.get(i.entry().id())).ifPresent(c -> c.update(i.entry(), i.stats())));
            refreshHeader();
        }, "Could not measure backups");
    }

    private void render(List<Item> items, boolean animateAll) {
        Map<String, EntryCard> next = new LinkedHashMap<>();
        Set<EntryCard> fresh = new HashSet<>();
        for (Item item : items) {
            EntryCard card = cards.get(item.entry().id());
            if (card == null) {
                card = new EntryCard();
                fresh.add(card);
            }
            card.update(item.entry(), item.stats());
            next.put(item.entry().id(), card);
        }
        cards.clear();
        cards.putAll(next);
        cardBox.getChildren().setAll(next.values());
        applyFilter(animateAll ? new HashSet<>(next.values()) : fresh);
    }

    private void applyFilter(Set<EntryCard> animate) {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (EntryCard card : cards.values()) {
            boolean match = filter.test(card.entry, card.stats) && (query.isEmpty()
                    || card.entry.game().toLowerCase(Locale.ROOT).contains(query)
                    || card.entry.gameDir().toString().toLowerCase(Locale.ROOT).contains(query));
            card.setManaged(match);
            card.setVisible(match);
            if (match && animate.contains(card))
                Ui.reveal(card, 260, Math.min(shown, MAX_STAGGERED) * STAGGER_MS);
            if (match) shown++;
        }
        emptyLabel.setText(cards.isEmpty()
                ? "Nothing here yet.\nEvery patch and restore shows up here, together with where its backups went."
                : "No entries match this filter.");
        emptyLabel.setVisible(shown == 0);
        refreshHeader();
    }

    private void refreshHeader() {
        countLabel.setText(cards.size() + (cards.size() == 1 ? " entry" : " entries"));
        clearButton.setDisable(cards.isEmpty() || busy);
        cleanupButton.setDisable(busy || cards.values().stream().noneMatch(c -> c.entry.ownsFolderBackup() && c.stats.folderExists()));

        Map<HistoryEntry, BackupStats> all = new LinkedHashMap<>();
        cards.values().forEach(c -> all.put(c.entry, c.stats));
        BackupStats.Totals t = BackupStats.total(all);
        summaryLabel.setText(t.backups() == 0 ? "No backups on disk"
                : t.backups() + (t.backups() == 1 ? " backup" : " backups")
                + "  ·  " + (t.complete() ? FileOps.formatSize(t.folderBytes()) : "measuring...") + " used by folder copies"
                + "  ·  " + FileOps.formatSize(t.bakBytes()) + " in .bak files");

        String backupDir = AppSettings.getBackupGameDirPath();
        openBackupFolderButton.setDisable(backupDir.isBlank() || !Files.isDirectory(Path.of(backupDir)));
        openBackupFolderButton.setTooltip(new Tooltip(backupDir.isBlank() ? "No backup folder set in Settings" : backupDir));
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        cards.values().forEach(EntryCard::rebuild);
        refreshHeader();
    }

    private void deleteFolderBackup(EntryCard card) {
        HistoryEntry entry = card.entry;
        Path folder = entry.folderBackup().orElseThrow();
        String size = card.stats.sizeKnown() ? FileOps.formatSize(card.stats.folderBytes()) : "unknown size";
        if (!Ui.prompt(shell.window()).title("Delete the folder backup of " + entry.game() + "?")
                .message("This frees " + size + ". The .bak files inside the game folder are kept, so you can still put the original DLLs back.")
                .details(List.of(folder.toString()))
                .confirm("DELETE BACKUP").destructive().width(520).ask()) return;

        setBusy(true);
        background(() -> {
            deleteBackupFolder(entry);
            HistoryEntry updated = entry.withoutFolderBackup();
            return new Item(updated, BackupStats.measure(updated));
        }, item -> {
            setBusy(false);
            card.update(item.entry(), item.stats());
            applyFilter(Set.of());
            shell.toast(Toaster.Kind.SUCCESS, "Deleted the folder backup of " + entry.game() + " (" + size + " freed).");
        }, "Could not delete the folder backup", () -> {
            setBusy(false);
            reload(false);
        });
    }

    private void showCleanup() {
        ToggleGroup rules = new ToggleGroup();
        FlowPane choices = new FlowPane();
        choices.getStyleClass().addAll("choices", "gap-2");
        for (Rule r : CLEANUP_RULES) {
            ToggleButton chip = new ToggleButton(r.label());
            chip.getStyleClass().add("filter-chip");
            chip.setUserData(r);
            chip.setToggleGroup(rules);
            chip.setSelected(r == DEFAULT_RULE);
            choices.getChildren().add(chip);
        }
        Label preview = new Label();
        preview.getStyleClass().add("prompt-result");

        Prompt dialog = Ui.prompt(shell.window())
                .title("Clean up folder backups")
                .message("Pick a rule, and you'll see what it frees before anything is deleted.")
                .extra(choices, preview)
                .note(".bak files are never deleted here, they're the only way back to the original DLLs.")
                .confirm("DELETE").destructive().width(500);
        Node deleteButton = dialog.confirmButton();

        List<HistoryEntry> candidates = new ArrayList<>();
        Runnable update = () -> {
            candidates.clear();
            Rule rule = rules.getSelectedToggle() == null ? DEFAULT_RULE : (Rule) rules.getSelectedToggle().getUserData();
            Instant cutoff = rule.days() < 0 ? Instant.MAX : Instant.now().minus(Duration.ofDays(rule.days()));
            Set<Path> folders = new HashSet<>();
            long bytes = 0, kept = 0;
            // newest first, so the size limit keeps the latest backups
            for (EntryCard c : cards.values()) {
                if (!c.entry.ownsFolderBackup() || !c.stats.folderExists() || !folders.add(c.entry.folderBackup().get())) continue;
                long size = Math.max(0, c.stats.folderBytes());
                boolean remove = rule.days() >= 0 ? c.entry.time().isBefore(cutoff) : kept + size > rule.keepBytes();
                if (remove) {
                    candidates.add(c.entry);
                    bytes += size;
                } else {
                    kept += size;
                }
            }
            preview.setText(candidates.isEmpty()
                    ? (rule.days() >= 0 ? "No folder backups are that old." : "Your folder backups already fit in that space.")
                    : candidates.size() + " folder backup(s)  ·  " + FileOps.formatSize(bytes) + " will be freed");
            deleteButton.setDisable(candidates.isEmpty());
        };
        rules.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
            else update.run();
        });
        update.run();

        if (!dialog.ask() || candidates.isEmpty()) return;
        List<HistoryEntry> targets = List.copyOf(candidates);
        setBusy(true);
        background(() -> {
            int deleted = 0;
            long freed = 0;
            List<String> failures = new ArrayList<>();
            for (HistoryEntry e : targets) {
                Path folder = e.folderBackup().get();
                try {
                    long size = FileOps.totalSize(folder);
                    deleteBackupFolder(e);
                    deleted++;
                    freed += size;
                } catch (IOException ex) {
                    Log.error("Could not delete " + folder, ex);
                    failures.add(e.game() + ": " + Errors.describe(ex));
                }
            }
            return new Cleanup(deleted, freed, failures);
        }, result -> {
            setBusy(false);
            reload(false);
            if (result.failures().isEmpty())
                shell.toast(Toaster.Kind.SUCCESS, "Deleted " + result.deleted() + " folder backup(s), "
                        + FileOps.formatSize(result.freed()) + " freed.");
            else
                shell.toast(Toaster.Kind.ERROR, result.failures().size() + " backup(s) could not be deleted. "
                        + result.failures().get(0));
        }, "Clean-up failed", () -> {
            setBusy(false);
            reload(false);
        });
    }

    private static synchronized void deleteBackupFolder(HistoryEntry entry) throws IOException {
        Path folder = entry.folderBackup().orElseThrow();
        try {
            FileOps.deleteRecursive(folder);
        } finally {
            BackupStats.forget(folder);
            History.add(entry.withoutFolderBackup());
        }
    }

    private void restoreFromFolder(EntryCard card) {
        HistoryEntry entry = card.entry;
        Path backup = entry.folderBackup().orElseThrow();
        Path gameDir = entry.gameDir();
        long size = card.stats.folderBytes();

        setBusy(true);
        background(() -> {
            if (Files.isDirectory(gameDir)) {
                List<ProcessGuard.RunningProcess> running = ProcessGuard.runningFrom(gameDir);
                if (!running.isEmpty())
                    throw new IOException("Close " + running.get(0).name() + " first: it is running from the game folder.");
            }
            DiskSpace.require(size >= 0 ? size : FileOps.totalSize(backup), gameDir, "restoring the folder backup");
            return true;
        }, ok -> confirmRestore(card, backup, gameDir), "Cannot restore", () -> setBusy(false));
    }

    private void confirmRestore(EntryCard card, Path backup, Path gameDir) {
        String name = gameDir.getFileName().toString();
        CheckBox keep = new CheckBox("Keep the current folder as \"" + name + " (before restore)\" next to it");
        keep.getStyleClass().add("app-check");
        keep.setWrapText(true);
        keep.setSelected(true);

        String size = card.stats.sizeKnown() ? "  (" + FileOps.formatSize(card.stats.folderBytes()) + ")" : "";
        boolean go = Ui.prompt(shell.window())
                .title("Restore the backup from " + Ui.fullTime(card.entry.time()) + "?")
                .message("Everything in the game folder is replaced by the backup, including the emulator DLLs and any saves"
                        + " or settings stored there since. The backup itself isn't changed.")
                .details(List.of("Game folder:  " + gameDir, "Backup:  " + backup + size))
                .extra(keep)
                .confirm("RESTORE").width(540)
                .ask();
        if (!go) {
            setBusy(false);
            return;
        }

        FolderRestoreJob job = new FolderRestoreJob(backup, gameDir, keep.isSelected(), StatusSink.NONE);
        setBusy(true);
        shell.setNavigationLocked(true);
        shell.bindStatus(job.messageProperty());
        card.track(job);

        job.setOnSucceeded(e -> {
            FolderRestoreJob.Result r = job.getValue();
            finishRestore(card);
            History.add(HistoryEntry.restoreFolder(card.entry.game(), r.gameDir(), r.files(), backup));
            reload(false);
            shell.setStatus("Folder restored");
            shell.toast(Toaster.Kind.SUCCESS, "Restored " + card.entry.game() + " from its folder backup."
                    + r.keptAs().map(p -> " The previous folder is at " + p.getFileName() + ".").orElse(""),
                    "OPEN FOLDER", () -> Ui.openFolder(r.gameDir()));
        });
        job.setOnFailed(e -> {
            finishRestore(card);
            Log.error("Folder restore failed", job.getException());
            shell.setStatus("Restore failed");
            shell.toast(Toaster.Kind.ERROR, "Restore failed, the game folder was not changed. " + Errors.describe(job.getException()));
        });
        job.setOnCancelled(e -> {
            finishRestore(card);
            shell.setStatus("Cancelled");
            shell.toast(Toaster.Kind.INFO, "Restore cancelled. The game folder was not changed.");
        });
        Ui.start(job, "folder-restore");
    }

    private void finishRestore(EntryCard card) {
        shell.unbindStatus();
        shell.setNavigationLocked(false);
        card.untrack();
        setBusy(false);
    }

    private interface IoSupplier<T> { T get() throws Exception; }

    private <T> void background(IoSupplier<T> work, Consumer<T> onDone, String failure) {
        background(work, onDone, failure, () -> {});
    }

    private <T> void background(IoSupplier<T> work, Consumer<T> onDone, String failure, Runnable onFailed) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> {
            Log.error(failure, task.getException());
            shell.toast(Toaster.Kind.ERROR, failure + ": " + Errors.describe(task.getException()));
            onFailed.run();
        });
        Ui.start(task, "history");
    }

    private static void addLocation(GridPane grid, int row, String key, String value, boolean exists) {
        Label k = new Label(key);
        k.getStyleClass().add("info-key");
        k.setMinWidth(Region.USE_PREF_SIZE);
        Label v = new Label(exists ? value : value + "  (missing)");
        v.getStyleClass().add("info-value");
        v.pseudoClassStateChanged(MISSING, !exists);
        v.setTooltip(new Tooltip(value));
        grid.add(k, 0, row);
        grid.add(v, 1, row);
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        b.setOnAction(ev -> action.run());
        return b;
    }

    private static String size(BackupStats s) {
        return s.sizeKnown() ? FileOps.formatSize(s.folderBytes()) : "measuring...";
    }

    private final class EntryCard extends VBox {

        private HistoryEntry entry;
        private BackupStats stats;
        private boolean running;
        private VBox progressBox;

        EntryCard() {
            getStyleClass().addAll("card", "history-card");
        }

        void update(HistoryEntry entry, BackupStats stats) {
            this.entry = entry;
            this.stats = stats;
            rebuild();
        }

        void rebuild() {
            List<Node> children = new ArrayList<>(List.of(header(), details(), locations()));
            if (running) children.add(progressBox);
            children.add(actions());
            getChildren().setAll(children);
            pseudoClassStateChanged(BUSY, running);
        }

        void track(FolderRestoreJob job) {
            Label step = new Label();
            step.getStyleClass().add("section-label");
            step.textProperty().bind(job.titleProperty());
            Button cancel = button("CANCEL", job::cancel);
            cancel.disableProperty().bind(job.titleProperty().isEqualTo(FolderRestoreJob.SWAP));
            HBox top = Layout.row("toolbar", step, Layout.spacer(), cancel);

            ProgressBar bar = new ProgressBar(0);
            bar.setMaxWidth(Double.MAX_VALUE);
            job.progressProperty().addListener((o, a, p) -> Ui.animateProgress(bar, p.doubleValue()));
            Label detail = new Label();
            detail.getStyleClass().add("progress-detail");
            detail.textProperty().bind(job.messageProperty());

            progressBox = Layout.column("progress-box", top, bar, detail);
            running = true;
            rebuild();
            Ui.reveal(progressBox, 220);
        }

        void untrack() {
            running = false;
            progressBox = null;
            rebuild();
        }

        private Node header() {
            boolean patch = entry.action() == HistoryEntry.Action.PATCH;
            Label badge = new Label(!patch ? "RESTORED" : entry.failed() > 0 ? "PARTIAL" : "PATCHED");
            badge.getStyleClass().addAll("badge", !patch ? "badge-info" : entry.failed() > 0 ? "badge-warn" : "badge-ok");
            Label name = new Label(entry.game());
            name.getStyleClass().add("emulator-name");
            Label time = new Label(Ui.relativeTime(entry.time()));
            time.getStyleClass().add("path-label");
            time.setTooltip(new Tooltip(Ui.fullTime(entry.time())));
            HBox header = Layout.row("toolbar", badge, name, Layout.spacer());
            if (patch && stats.hasBackups()) {
                long bytes = Math.max(0, stats.folderBytes()) + stats.bakBytes();
                Label size = new Label(stats.sizeKnown() ? FileOps.formatSize(bytes) : "measuring...");
                size.getStyleClass().add("size-label");
                size.setTooltip(new Tooltip("Disk space used by this entry's backups"));
                header.getChildren().add(size);
            }
            header.getChildren().add(time);
            return header;
        }

        private Node details() {
            String summary;
            if (entry.action() == HistoryEntry.Action.PATCH)
                summary = entry.emulator() + "  ·  " + entry.files() + " file(s) patched"
                        + (entry.failed() > 0 ? "  ·  " + entry.failed() + " failed" : "");
            else if (entry.isFolderRestore())
                summary = "Whole folder restored from backup  ·  " + entry.files() + " file(s)";
            else
                summary = entry.files() + " original DLL(s) restored";
            Label details = new Label(summary);
            details.getStyleClass().add("path-label");
            return details;
        }

        private Node locations() {
            GridPane grid = new GridPane();
            grid.getStyleClass().add("kv-grid");
            int row = 0;
            addLocation(grid, row++, "Game", entry.gameDir().toString(), stats.gameExists());
            if (entry.action() == HistoryEntry.Action.PATCH) {
                int listed = entry.dllBackups().size();
                String baks = listed == 0 ? "Not kept (backup option was off)"
                        : listed + " .bak file(s) next to the original DLLs"
                        + (stats.baksPresent() < listed ? "  ·  " + stats.baksPresent() + " still present" : "")
                        + (stats.baksPresent() > 0 ? "  ·  " + FileOps.formatSize(stats.bakBytes()) : "");
                addLocation(grid, row++, "DLL backups", baks, listed == 0 || stats.baksPresent() > 0);
                if (entry.folderBackup().isPresent())
                    addLocation(grid, row, "Folder copy", entry.folderBackup().get()
                            + (stats.folderExists() ? "  ·  " + size(stats) : ""), stats.folderExists());
            } else if (entry.isFolderRestore()) {
                addLocation(grid, row, "Restored from", entry.folderBackup().get().toString(), stats.folderExists());
            }
            return grid;
        }

        private Node actions() {
            FlowPane left = new FlowPane();
            left.getStyleClass().add("gap-2");
            left.setAlignment(Pos.CENTER_LEFT);
            if (stats.gameExists()) {
                left.getChildren().add(button("OPEN IN LUMINA", () -> shell.openGame(entry.gameDir())));
                left.getChildren().add(button("GAME FOLDER", () -> Ui.openFolder(entry.gameDir())));
            }
            if (stats.baksPresent() > 0)
                entry.dllBackups().stream().filter(Files::exists).findFirst()
                        .ifPresent(bak -> left.getChildren().add(button("SHOW .BAK", () -> Ui.showInExplorer(bak))));
            if (stats.folderExists())
                left.getChildren().add(button("FOLDER BACKUP", () -> Ui.openFolder(entry.folderBackup().get())));
            if (entry.ownsFolderBackup() && stats.folderExists()) {
                Button restore = button("RESTORE FROM FOLDER BACKUP", () -> restoreFromFolder(this));
                restore.setTooltip(new Tooltip("Replace the game folder with this full copy"));
                Button delete = button("DELETE FOLDER BACKUP", () -> deleteFolderBackup(this));
                delete.getStyleClass().add("danger-text");
                delete.setTooltip(new Tooltip("Free " + size(stats) + ". The .bak files are kept."));
                restore.setDisable(busy);
                delete.setDisable(busy);
                left.getChildren().addAll(restore, delete);
            }
            Button forget = button("FORGET", () -> {
                History.remove(entry.id());
                reload(false);
            });
            forget.setTooltip(new Tooltip("Remove this entry. Backup files stay where they are."));
            forget.setDisable(running || busy);
            HBox.setHgrow(left, Priority.ALWAYS);
            HBox actions = Layout.row("toolbar", left, forget);
            actions.setAlignment(Pos.BOTTOM_LEFT);
            return actions;
        }
    }
}
