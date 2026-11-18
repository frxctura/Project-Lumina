package com.javafxapp.javafxapp.ui.pages;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.backup.History;
import com.javafxapp.javafxapp.backup.HistoryEntry;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Job;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorConfig;
import com.javafxapp.javafxapp.library.GameInfo;
import com.javafxapp.javafxapp.library.SteamStore;
import com.javafxapp.javafxapp.patching.CrackJob;
import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.patching.RestoreJob;
import com.javafxapp.javafxapp.patching.SteamApi;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.safety.DefenderExclusion;
import com.javafxapp.javafxapp.safety.ProcessGuard;
import com.javafxapp.javafxapp.ui.components.Icon;
import com.javafxapp.javafxapp.ui.components.StepIndicator;
import com.javafxapp.javafxapp.ui.components.Toaster;
import com.javafxapp.javafxapp.ui.dialogs.DefenderPrompt;
import com.javafxapp.javafxapp.ui.dialogs.EmulatorSettingsDialog;
import com.javafxapp.javafxapp.ui.shell.AppShell;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.Theme;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class MainController implements Page {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final int MAX_LOG_LINES = 500;

    private AppShell shell;
    private Path gameDirectory;
    private GameInfo gameInfo;
    private Optional<HistoryEntry> lastPatch = Optional.empty();
    private Job<?> runningJob;
    private Timeline waitTimer;
    private boolean checkingProcesses, planning;
    private final Image iconOnDark = Ui.image("icon.png"), iconOnLight = Ui.image("icon-dark.png");
    private final PauseTransition appIdDebounce = new PauseTransition(Duration.millis(600));

    @FXML private HBox root;
    @FXML private VBox selectView, readyView, dropZone, recentBox, recentList, progressBox;
    @FXML private Button emulatorSettingsButton, selectDirButton, changeDirButton, backButton, restoreButton, openFolderButton, crackButton;
    @FXML private Label readyTitle, patchBadge, percentLabel, progressDetail;
    @FXML private ComboBox<Emulator> emulatorBox;
    @FXML private TextField appIdField;
    @FXML private CheckBox backupCheckBox, unpackCheckBox;
    private boolean syncingBackup;
    @FXML private StepIndicator steps;
    @FXML private ProgressBar progressBar;
    @FXML private ListView<String> logList;
    @FXML private ImageView coverIcon, coverHeader;
    @FXML private Label gameName, gameSize, fileCount, dllCount, gameExecutable, lastModified, gamePath, backupInfo, stubInfo;
    @FXML private Hyperlink backupLink;
    @FXML private VBox resultCard, infoPanel;
    @FXML private StackPane dropIcon;
    @FXML private Label emulatorLabel, appIdLabel;

    private Image placeholder() {
        return Theme.isLight() ? iconOnLight : iconOnDark;
    }

    @FXML
    public void initialize() {
        Theme.bindImage(coverIcon, iconOnDark, iconOnLight);

        emulatorBox.getItems().setAll(Emulator.values());
        emulatorBox.valueProperty().addListener((o, a, v) -> {
            if (v != null) AppSettings.setEmulator(v);
            applyBackupOption(isBusy());
            updateButtons();
        });
        emulatorBox.setTooltip(new Tooltip("Which Steam emulator to install into the game"));
        emulatorSettingsButton.setTooltip(new Tooltip("Emulator settings for this game: username, language, DLC…"));
        emulatorSettingsButton.setOnAction(e -> openEmulatorSettings());
        emulatorSettingsButton.setGraphic(Icon.SETTINGS.create(16));
        emulatorSettingsButton.setAccessibleText("Emulator settings for this game");
        emulatorLabel.setLabelFor(emulatorBox);
        appIdLabel.setLabelFor(appIdField);
        dropIcon.getChildren().setAll(Icon.ARROW_DOWNLOAD.create(36));
        logList.setAccessibleText("Activity log");

        backupCheckBox.setSelected(AppSettings.isBackupDlls());
        backupCheckBox.selectedProperty().addListener((o, a, v) -> { if (!syncingBackup) AppSettings.setBackupDlls(v); });
        unpackCheckBox.setSelected(AppSettings.isUnpackDrm());
        unpackCheckBox.selectedProperty().addListener((o, a, v) -> AppSettings.setUnpackDrm(v));
        unpackCheckBox.setTooltip(new Tooltip("Some games wrap their .exe in Steam's SteamStub DRM. Steamless removes it, only from "
                + "executables that have it. The original is kept as <name>.exe.steamstub.bak."));

        appIdField.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,10}") ? c : null));
        appIdField.setTooltip(new Tooltip("Steam App ID, written to steam_appid.txt and used to show store info"));
        appIdDebounce.setOnFinished(e -> lookupStore(appIdField.getText()));
        appIdField.textProperty().addListener((o, a, v) -> appIdDebounce.playFromStart());

        selectDirButton.setOnAction(e -> chooseDirectory());
        changeDirButton.setOnAction(e -> chooseDirectory());
        backButton.setOnAction(e -> resetToSelect());
        crackButton.setOnAction(e -> { if (isBusy()) cancelJob(); else startCrack(); });
        restoreButton.setOnAction(e -> startRestore());
        openFolderButton.setOnAction(e -> { if (gameDirectory != null) Ui.openFolder(gameDirectory); });
        backupLink.setOnAction(e -> openBackupLocation());

        logList.setPlaceholder(new Label("Activity will appear here"));
        logList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                pseudoClassStateChanged(ERROR, !empty && item.contains("✖"));
            }
        });

        backupLink.managedProperty().bind(backupLink.visibleProperty());
        patchBadge.managedProperty().bind(patchBadge.visibleProperty());
        root.setOnDragOver(this::onDragOver);
        root.setOnDragExited(e -> setDragOver(false));
        root.setOnDragDropped(this::onDragDropped);
        root.sceneProperty().addListener((o, a, scene) -> { if (scene != null) installShortcuts(scene); });

        readyView.setVisible(false);
        progressBox.setVisible(false);
        progressBox.setManaged(false);
        clearGameInfo();
    }

    @Override
    public void attach(AppShell shell) {
        this.shell = shell;
    }

    @Override
    public void onShow() {
        emulatorBox.setValue(AppSettings.getEmulator());
        unpackCheckBox.setSelected(AppSettings.isUnpackDrm());
        applyBackupOption(isBusy());
        if (gameDirectory == null) {
            Ui.reveal(selectView, 300);
            showRecent();
        }
    }

    @Override
    public void onClose() {
        if (waitTimer != null) waitTimer.stop();
        if (runningJob != null) runningJob.cancel();
    }

    private void installShortcuts(javafx.scene.Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!isBusy() && root.isVisible()) chooseDirectory();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!isBusy() && root.isVisible() && gameDirectory != null) startCrack();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), () -> {
            if (!root.isVisible()) return;
            if (isBusy()) cancelJob();
            else if (gameDirectory != null) resetToSelect();
        });
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Game Directory");
        Path initial = gameDirectory != null ? gameDirectory.getParent()
                : AppSettings.getRecentGames().stream().findFirst().map(Path::getParent).orElse(null);
        if (initial != null && Files.isDirectory(initial)) chooser.setInitialDirectory(initial.toFile());
        File selected = chooser.showDialog(shell.window());
        if (selected != null) selectDirectory(selected.toPath());
    }

    private void showRecent() {
        List<Path> recent = AppSettings.getRecentGames();
        recentBox.setVisible(!recent.isEmpty());
        recentList.getChildren().clear();
        for (int i = 0; i < recent.size(); i++) {
            Path dir = recent.get(i);
            Label name = new Label(dir.getFileName().toString());
            name.getStyleClass().add("recent-name");
            Label path = new Label(dir.getParent() == null ? "" : dir.getParent().toString());
            path.getStyleClass().add("recent-path");
            Button card = new Button();
            card.setGraphic(Layout.column("stack", name, path));
            card.getStyleClass().addAll("card", "compact", "recent-card");
            card.setOnAction(e -> selectDirectory(dir));
            recentList.getChildren().add(card);
            Ui.reveal(card, 260, 120 + i * 60);
        }
    }

    private void onDragOver(DragEvent e) {
        if (!isBusy() && !droppedFolders(e).isEmpty()) {
            e.acceptTransferModes(TransferMode.LINK);
            setDragOver(true);
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        setDragOver(false);
        List<Path> folders = droppedFolders(e);
        boolean ok = !isBusy() && !folders.isEmpty();
        if (ok && folders.size() == 1) selectDirectory(folders.get(0));
        else if (ok) shell.enqueueBatch(folders);
        e.setDropCompleted(ok);
        e.consume();
    }

    private static List<Path> droppedFolders(DragEvent e) {
        return e.getDragboard().getFiles().stream().filter(File::isDirectory).map(File::toPath).toList();
    }

    private void setDragOver(boolean over) {
        dropZone.pseudoClassStateChanged(DRAG_OVER, over);
        readyView.pseudoClassStateChanged(DRAG_OVER, over);
    }

    /** The game folder open on this page, or null. */
    public Path currentGame() {
        return gameDirectory;
    }

    public void selectDirectory(Path dir) {
        if (isBusy()) return;
        if (!Files.isDirectory(dir)) {
            shell.toast(Toaster.Kind.ERROR, "Folder not found:\n" + dir);
            return;
        }
        boolean firstSelection = gameDirectory == null;
        gameDirectory = dir;
        gameInfo = null;
        AppSettings.addRecentGame(dir);
        hideResult();
        if (!infoPanel.isVisible()) {
            infoPanel.setManaged(true);
            Ui.reveal(infoPanel, 260);
        }

        readyTitle.setText(dir.getFileName().toString());
        patchBadge.setVisible(false);
        coverHeader.setImage(null);
        appIdField.clear();
        logList.getItems().clear();
        hideProgress();
        log("Selected " + dir, false);
        shell.setStatus("Loaded " + dir.getFileName());
        if (firstSelection) Ui.swap(selectView, readyView);
        else Ui.pop(readyTitle);
        refreshGameInfo();
    }

    private void resetToSelect() {
        if (isBusy()) return;
        gameDirectory = null;
        gameInfo = null;
        clearGameInfo();
        hideResult();
        infoPanel.setVisible(false);
        infoPanel.setManaged(false);
        shell.setStatus("Ready");
        Ui.swap(readyView, selectView);
        showRecent();
    }

    private void clearGameInfo() {
        showCover(placeholder(), null);
        gameName.setText("No game selected");
        for (Label l : List.of(gameSize, fileCount, dllCount, gameExecutable, stubInfo, lastModified, gamePath, backupInfo)) l.setText("—");
        backupLink.setVisible(false);
        patchBadge.setVisible(false);
        updateButtons();
    }

    private void refreshGameInfo() {
        Path dir = gameDirectory;
        gameName.setText(dir.getFileName().toString());
        gamePath.setText(dir.toString());
        for (Label l : List.of(gameSize, fileCount, dllCount, gameExecutable, stubInfo, lastModified, backupInfo)) l.setText("…");

        record Loaded(GameInfo info, Optional<HistoryEntry> patch, Image icon) {}
        Task<Loaded> task = new Task<>() {
            @Override
            protected Loaded call() throws Exception {
                GameInfo info = GameInfo.scan(dir);
                return new Loaded(info, History.latestFor(dir), info.executable().map(GameInfo::loadIcon).orElse(null));
            }
        };
        task.setOnSucceeded(e -> {
            if (!dir.equals(gameDirectory)) return;
            Loaded loaded = task.getValue();
            lastPatch = loaded.patch();
            showGameInfo(loaded.info(), loaded.icon());
        });
        task.setOnFailed(e -> log("Could not read folder: " + Errors.describe(task.getException()), true));
        Ui.start(task, "game-info");
    }

    private void showGameInfo(GameInfo info, Image icon) {
        boolean firstLoad = gameInfo == null;
        gameInfo = info;
        if (firstLoad) {
            Ui.countUp(gameSize, info.totalSize(), FileOps::formatSize);
            Ui.countUp(fileCount, info.fileCount(), n -> String.format("%,d", n));
        } else {
            gameSize.setText(FileOps.formatSize(info.totalSize()));
            fileCount.setText(String.format("%,d", info.fileCount()));
        }
        showPatchState(info.patch());
        gameExecutable.setText(info.executable().map(p -> p.getFileName().toString()).orElse("None found"));
        lastModified.setText(info.lastModified());
        showBackupInfo();
        stubInfo.setText(info.stubCount() == 0 ? "None" : info.stubCount() + (info.stubCount() == 1 ? " exe has DRM" : " exes have DRM"));

        if (coverHeader.getImage() == null) showCover(icon != null ? icon : placeholder(), null);
        if (appIdField.getText().isBlank()) info.appId().ifPresent(id -> {
            appIdField.setText(id);
            log("Detected Steam App ID " + id, false);
        });
        if (firstLoad && info.dllCount() == 0) log("No steam_api DLLs found in this folder.", true);
        if (firstLoad && info.backupCount() > 0) log(info.backupCount() + " backup file(s) found, this game was patched before.", false);
        if (firstLoad && info.stubCount() > 0)
            log("SteamStub DRM found in " + info.stubCount() + " executable(s)."
                    + (AppSettings.isUnpackDrm() ? " It will be removed when patching." : " Tick \"Unpack SteamStub DRM\" to remove it when patching."), false);
        updateButtons();
    }

    private void showPatchState(PatchDetector.Report report) {
        String dlls = report.dlls().size() + " DLL(s)";
        String emulator = report.emulator().orElse("");
        long patched = report.dlls().stream().filter(PatchDetector.DllStatus::patched).count();
        switch (report.state()) {
            case NO_DLLS -> {
                patchBadge.setVisible(false);
                dllCount.setText("None found");
            }
            case ORIGINAL -> {
                showBadge("ORIGINAL", "badge-off");
                dllCount.setText(dlls + " · original");
            }
            case PATCHED -> {
                showBadge("PATCHED · " + emulator, "badge-ok");
                dllCount.setText(dlls + " · patched with " + emulator);
            }
            case MIXED -> {
                showBadge("MIXED", "badge-warn");
                dllCount.setText(dlls + " · " + patched + " patched");
            }
        }
    }

    private void showBadge(String text, String style) {
        boolean changed = !patchBadge.isVisible() || !text.equals(patchBadge.getText());
        patchBadge.setText(text);
        patchBadge.getStyleClass().removeAll("badge-ok", "badge-warn", "badge-off");
        patchBadge.getStyleClass().add(style);
        if (changed) Ui.reveal(patchBadge, 250);
    }

    private boolean alreadyPatchedWith(Emulator emulator) {
        if (gameInfo == null || emulator == null || gameInfo.patch().state() != PatchDetector.State.PATCHED) return false;
        return gameInfo.patch().dlls().stream().allMatch(d -> d.emulator().filter(emulator.toString()::equals).isPresent());
    }

    private void showBackupInfo() {
        int baks = gameInfo.backupCount();
        boolean folder = lastPatch.map(HistoryEntry::folderBackupExists).orElse(false);
        String text = baks == 0 && !folder ? "None"
                : (baks > 0 ? baks + (baks == 1 ? " file beside the originals" : " files beside the originals") : "")
                + (baks > 0 && folder ? "\n" : "") + (folder ? "Full folder copy" : "");
        backupInfo.setText(text);
        backupLink.setVisible(baks > 0 || folder);
        backupLink.setText(folder ? "Open folder backup" : "Show backup file");
    }

    private void openBackupLocation() {
        if (lastPatch.map(HistoryEntry::folderBackupExists).orElse(false)) {
            Ui.openFolder(lastPatch.get().folderBackup().get());
            return;
        }
        Ui.start(new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                FileOps.findFiles(gameDirectory, SteamApi::isBackup).stream().findFirst().ifPresent(Ui::showInExplorer);
                return null;
            }
        }, "find-backup");
    }

    private void lookupStore(String appId) {
        if (!SteamStore.isValidAppId(appId) || gameDirectory == null) {
            if (coverHeader.getImage() != null) showCover(coverIcon.getImage(), null);
            return;
        }
        Path dir = gameDirectory;
        Task<SteamStore.StoreInfo> task = new Task<>() {
            @Override
            protected SteamStore.StoreInfo call() {
                return SteamStore.lookup(appId).orElse(null);
            }
        };
        task.setOnSucceeded(e -> {
            SteamStore.StoreInfo store = task.getValue();
            if (store == null || !dir.equals(gameDirectory) || !appId.equals(appIdField.getText())) return;
            gameName.setText(store.name());
            Ui.pop(gameName);
            if (store.headerImageUrl() == null) return;
            Image header = new Image(store.headerImageUrl(), true);
            header.progressProperty().addListener((o, a, p) -> {
                if (p.doubleValue() >= 1 && !header.isError() && dir.equals(gameDirectory)) showCover(null, header);
            });
        });
        Ui.start(task, "store-lookup");
    }

    private void showCover(Image icon, Image header) {
        boolean hasHeader = header != null;
        coverHeader.setImage(header);
        coverHeader.setVisible(hasHeader);
        coverHeader.setManaged(hasHeader);
        if (icon != null) coverIcon.setImage(icon);
        coverIcon.setVisible(!hasHeader);
        coverIcon.setManaged(!hasHeader);
        Ui.reveal(hasHeader ? coverHeader : coverIcon, 350);
    }

    private void startCrack() {
        if (isBusy() || gameDirectory == null) return;
        var invalid = AppSettings.validate();
        if (invalid.isPresent()) {
            shell.toast(Toaster.Kind.ERROR, invalid.get().replace('\n', ' '), "Open settings", () -> shell.show(AppShell.View.SETTINGS));
            return;
        }

        var request = new CrackJob.Request(gameDirectory, emulatorBox.getValue(), backupCheckBox.isSelected(),
                appIdField.getText().trim(), AppSettings.backupTarget(), AppSettings.moveTarget(), AppSettings.isUnpackDrm());
        if (gameInfo != null) {
            try {
                CrackJob.checkSpace(request, gameInfo.totalSize());
            } catch (IOException e) {
                log(Errors.describe(e), true);
                shell.toast(Toaster.Kind.ERROR, Errors.describe(e), "Open settings", () -> shell.show(AppShell.View.SETTINGS));
                return;
            }
        }
        if (alreadyPatchedWith(request.emulator()))
            log("Already patched with " + request.emulator() + ". DLLs that match are left as they are.", false);
        String game = gameName.getText();
        Runnable start = () -> whenGameClosed(request.gameDir(), () -> runCrack(request, game));
        Runnable next = () -> {
            planning = false;
            setBusy(false);
            if (AppSettings.isPreviewBeforePatch()) preview(request, start);
            else start.run();
        };
        planning = true;
        setBusy(true);
        DefenderPrompt.offer(shell, request.emulator(), List.of(request.gameDir()), request.unpackDrm(), next);
    }

    private void preview(CrackJob.Request request, Runnable start) {
        planning = true;
        setBusy(true);
        Task<CrackJob.Plan> task = new Task<>() {
            @Override
            protected CrackJob.Plan call() throws Exception {
                return CrackJob.plan(request);
            }
        };
        task.setOnSucceeded(e -> {
            planning = false;
            setBusy(false);
            if (confirmPlan(request, task.getValue())) start.run();
        });
        task.setOnFailed(e -> {
            planning = false;
            setBusy(false);
            log("Could not check the game folder: " + Errors.describe(task.getException()), true);
            shell.toast(Toaster.Kind.ERROR, Errors.describe(task.getException()));
        });
        Ui.start(task, "patch-preview");
    }

    private boolean confirmPlan(CrackJob.Request request, CrackJob.Plan plan) {
        Path dir = request.gameDir();
        List<String> files = new ArrayList<>();
        for (CrackJob.PlannedDll d : plan.dlls()) {
            String what;
            if (d.upToDate()) what = "already up to date, left alone";
            else if (d.proxy()) what = "replaced by " + request.emulator() + (d.newBackup() ? ", original moved to " + SteamApi.proxyBackupOf(d.dll()).getFileName()
                    : ", existing " + SteamApi.proxyBackupOf(d.dll()).getFileName() + " kept");
            else what = "replaced" + (d.newBackup() ? ", original kept as .bak"
                    : d.keepsBackup() ? ", existing .bak kept" : request.backupDlls() ? "" : ", no backup");
            files.add(dir.relativize(d.dll()) + "  ·  " + what);
        }
        // a folder without steam_api DLLs is skipped whole, executables included
        List<Path> stubs = plan.dlls().isEmpty() ? List.of() : plan.stubExes();
        boolean unpack = plan.unpackDrm() && !stubs.isEmpty();
        for (Path exe : stubs) {
            files.add(dir.relativize(exe) + "  ·  " + (plan.unpackDrm() ? "SteamStub DRM removed, original kept as " + SteamApi.exeBackupOf(exe).getFileName()
                    : "has SteamStub DRM, left as it is (turn on unpacking to remove it)"));
        }

        List<String> steps = new ArrayList<>();
        plan.folderBackup().ifPresent(p -> steps.add("The whole folder (" + FileOps.formatSize(plan.gameSize()) + ") is copied to " + p + " first."));
        if (request.emulator().writesAppId() && SteamStore.isValidAppId(request.appId())) steps.add("App ID " + request.appId() + " is written to steam_appid.txt.");
        plan.moveTo().ifPresent(p -> steps.add("Afterwards the game is moved to " + p + "."));
        plan.problem().ifPresent(p -> steps.add("⚠ " + p));

        String exes = stubs.size() + (stubs.size() == 1 ? " exe" : " exes");
        String title = plan.dlls().isEmpty() ? "No steam_api DLLs in this folder"
                : plan.toPatch() == 0 && unpack ? "Unpack SteamStub DRM from " + exes + "?"
                : plan.toPatch() == 0 ? "Already patched with " + request.emulator()
                : "Patch " + plan.toPatch() + " DLL(s)" + (unpack ? " and unpack " + exes : "") + " with " + request.emulator() + "?";
        String message = plan.dlls().isEmpty() ? "There's nothing here for Lumina to patch."
                : plan.toPatch() == 0 && !unpack ? "Every DLL already matches, so nothing is replaced."
                : steps.isEmpty() ? null : String.join(" ", steps);

        CheckBox again = new CheckBox("Show this before every patch");
        again.getStyleClass().add("app-check");
        again.setSelected(true);

        boolean ok = Ui.prompt(shell.window())
                .title(title)
                .message(message)
                .details(files)
                .extra(again)
                .confirm("Patch").width(520)
                .ask();
        if (!again.isSelected()) {
            AppSettings.setPreviewBeforePatch(false);
            shell.toast(Toaster.Kind.INFO, "Preview turned off. You can turn it back on in Settings.");
        }
        return ok;
    }

    private void runCrack(CrackJob.Request request, String game) {
        run(new CrackJob(request, sink()), r -> {
            if (r.rolledBack() && r.antivirus()) {
                shell.setStatus("Blocked by antivirus, changes undone");
                showResult("error", "Antivirus got in the way, nothing was changed",
                        "Antivirus removed or changed the emulator DLL, so every change was undone and the game is as it was. "
                        + "Excluding the game folder from Microsoft Defender usually fixes this.",
                        resultAction("Add Defender exclusion", () -> addExclusion(request.gameDir())));
                return;
            }
            if (r.rolledBack()) {
                shell.setStatus("Patch failed, changes undone");
                showResult("error", "The patch failed, nothing was changed",
                        r.failed() + " file(s) failed, so every change was undone and the game is as it was. The log below says why.");
                return;
            }
            boolean moved = !r.location().equals(request.gameDir());
            if (r.patched() == 0 && r.unpacked() == 0 && !moved) {
                shell.setStatus(r.upToDate() > 0 ? "Already up to date" : "Nothing to patch");
                showResult("info", r.upToDate() > 0 ? "Already patched" : "Nothing to patch", r.upToDate() > 0
                        ? "This game is already patched with " + request.emulator() + ", so nothing needed rewriting."
                        : "There are no steam_api DLLs in this folder, so nothing was changed.");
                return;
            }
            History.add(HistoryEntry.patch(game, r.location(), request.emulator().toString(), r.verified(), r.failed(),
                    r.unpacked(), r.dllBackups(), r.folderBackup()));
            if (!r.location().equals(gameDirectory)) {
                gameDirectory = r.location();
                AppSettings.addRecentGame(gameDirectory);
                log("Game now lives at " + gameDirectory, false);
            }
            if (r.failed() == 0 && r.unpackFailed() > 0) {
                shell.setStatus("Patched, but " + r.unpackFailed() + " exe could not be unpacked");
                showResult("error", "Patched, but the DRM couldn't be removed",
                        "Patched " + r.verified() + " file(s) with " + request.emulator() + ", but Steamless could not unpack "
                        + r.unpackFailed() + " executable(s), so the game may not start. The log below says why.",
                        resultAction("Open folder", () -> Ui.openFolder(gameDirectory)),
                        resultAction("Show in History", () -> shell.show(AppShell.View.HISTORY)));
            } else if (r.failed() == 0) {
                String unpacked = r.unpacked() > 0 ? " and unpacked " + r.unpacked() + " exe" : "";
                shell.setStatus("Patched and verified " + r.verified() + " file(s) with " + request.emulator() + unpacked);
                int kept = r.dllBackups().size();
                String backups = kept == 0 && r.folderBackup().isEmpty() ? " No backup was kept."
                        : " The originals are kept" + (kept > 0 ? " beside the game files" : "") + (r.folderBackup().isPresent() ? (kept > 0 ? " and" : "") + " in a full folder copy" : "") + ".";
                Button restore = resultAction("Restore originals", this::startRestore);
                restore.setDisable(kept == 0);
                showResult("success", "Patched with " + request.emulator(),
                        "Patched and verified " + r.verified() + " file(s)" + unpacked + "." + backups,
                        resultAction("Open folder", () -> Ui.openFolder(gameDirectory)), restore,
                        resultAction("Show in History", () -> shell.show(AppShell.View.HISTORY)));
                flashCrackButton();
            } else {
                shell.setStatus("Finished with errors");
                showResult("error", "Finished with errors", r.verified() + " file(s) patched, " + r.failed() + " failed. The log below says why.");
            }
        });
    }

    private void addExclusion(Path dir) {
        if (isBusy()) return;
        if (!Ui.prompt(shell.window())
                .title("Exclude this game from Defender?")
                .message("Defender stops scanning these folders, so it can't quarantine the emulator DLLs again.")
                .details(List.of(dir.toString(), AppPaths.emulators().toString(), AppPaths.tools().toString()))
                .note("Windows will ask for administrator permission.")
                .confirm("Add exclusion").width(540)
                .ask()) return;
        planning = true;
        setBusy(true);
        shell.setStatus("Waiting for administrator permission...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                java.nio.file.Files.createDirectories(AppPaths.tools());
                DefenderExclusion.add(List.of(dir, AppPaths.emulators(), AppPaths.tools()));
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            planning = false;
            setBusy(false);
            log("Added Defender exclusions for " + dir + " and " + AppPaths.emulators(), false);
            shell.setStatus("Exclusion added");
            shell.toast(Toaster.Kind.SUCCESS, "Exclusion added.", "Patch again", () -> {
                if (dir.equals(gameDirectory)) startCrack();
            });
        });
        task.setOnFailed(e -> {
            planning = false;
            setBusy(false);
            log(Errors.describe(task.getException()), true);
            shell.setStatus("No exclusion added");
            shell.toast(Toaster.Kind.ERROR, Errors.describe(task.getException()));
        });
        Ui.start(task, "defender-exclusion");
    }

    private void startRestore() {
        if (isBusy() || gameInfo == null || gameInfo.backupCount() == 0) return;
        if (!Ui.prompt(shell.window())
                .title("Put the original files back?")
                .message(gameInfo.backupCount() + " backup file(s) are restored (.bak, *_o.dll and unpacked executables), replacing the emulator.")
                .confirm("Restore")
                .ask()) return;
        String game = gameName.getText();
        Path dir = gameDirectory;
        whenGameClosed(dir, () -> runRestore(dir, game));
    }

    private void runRestore(Path dir, String game) {
        run(new RestoreJob(dir, sink()), restored -> {
            History.add(HistoryEntry.restore(game, dir, restored));
            shell.setStatus("Restored " + restored + " file(s)");
            showResult("success", "Originals restored", "Put back " + restored + " original file(s). The emulator is gone from this game.",
                    resultAction("Open folder", () -> Ui.openFolder(dir)));
        });
    }

    private void whenGameClosed(Path dir, Runnable start) {
        findRunning(dir, running -> {
            setBusy(false);
            if (running.isEmpty()) start.run();
            else if (askToWait(running)) waitForExit(dir, running, start);
            else shell.setStatus("Cancelled, the game is still running");
        });
        setBusy(true);
    }

    private void findRunning(Path dir, Consumer<List<ProcessGuard.RunningProcess>> then) {
        checkingProcesses = true;
        Task<List<ProcessGuard.RunningProcess>> task = new Task<>() {
            @Override
            protected List<ProcessGuard.RunningProcess> call() {
                return ProcessGuard.runningFrom(dir);
            }
        };
        task.setOnSucceeded(e -> {
            checkingProcesses = false;
            then.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            checkingProcesses = false;
            Log.error("Could not list running processes", task.getException());
            then.accept(List.of());
        });
        Ui.start(task, "process-guard");
    }

    private boolean askToWait(List<ProcessGuard.RunningProcess> running) {
        return Ui.prompt(shell.window())
                .title("This game is still running")
                .message("Files that are in use can't be replaced. Close it, or let Lumina wait and carry on by itself once it's closed.")
                .details(List.of(processNames(running)))
                .confirm("Wait for it")
                .ask();
    }

    private void waitForExit(Path dir, List<ProcessGuard.RunningProcess> running, Runnable start) {
        log("Waiting for " + processNames(running) + " to close...", false);
        shell.setStatus("Waiting for " + processNames(running) + " to close...");
        // rescan every tick, a launcher can hand over to the real game
        waitTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (checkingProcesses) return;
            findRunning(dir, still -> {
                if (waitTimer == null) return;
                if (!still.isEmpty()) {
                    shell.setStatus("Waiting for " + processNames(still) + " to close...");
                    return;
                }
                stopWaiting();
                log("Game closed, continuing.", false);
                start.run();
            });
        }));
        waitTimer.setCycleCount(Animation.INDEFINITE);
        waitTimer.play();
        setBusy(true);
    }

    private void stopWaiting() {
        waitTimer.stop();
        waitTimer = null;
        setBusy(false);
    }

    private static String processNames(List<ProcessGuard.RunningProcess> running) {
        return String.join(", ", running.stream().map(ProcessGuard.RunningProcess::name).distinct().toList());
    }

    private <T> void run(Job<T> job, Consumer<T> onSuccess) {
        runningJob = job;
        hideResult();
        setBusy(true);

        steps.setSteps(job.steps());
        job.titleProperty().addListener((o, a, step) -> steps.setActive(step));
        progressBar.setProgress(0);
        percentLabel.setText("");
        job.progressProperty().addListener((o, a, p) -> {
            Ui.animateProgress(progressBar, p.doubleValue());
            percentLabel.setText(p.doubleValue() < 0 ? "" : Math.round(p.doubleValue() * 100) + "%");
        });
        progressDetail.textProperty().bind(job.messageProperty());
        shell.bindStatus(job.messageProperty());
        showProgress();

        job.setOnSucceeded(e -> {
            steps.complete();
            finishJob();
            onSuccess.accept(job.getValue());
        });
        job.setOnFailed(e -> {
            steps.fail();
            finishJob();
            Throwable ex = job.getException();
            Log.error("Job failed", ex);
            log("Failed: " + Errors.describe(ex), true);
            shell.setStatus("Failed");
            showResult("error", "That didn't work", Errors.describe(ex) + (job instanceof CrackJob ? " Every change made so far was undone." : ""));
        });
        job.setOnCancelled(e -> {
            steps.fail();
            finishJob();
            log("Cancelled.", true);
            shell.setStatus("Cancelled");
            showResult("info", "Cancelled", job instanceof CrackJob
                    ? "Every change made so far was undone, the game is as it was." : "Stopped before it finished.");
        });
        Ui.start(job, "job");
    }

    private void cancelJob() {
        if (waitTimer != null) {
            stopWaiting();
            log("Stopped waiting.", false);
            shell.setStatus("Ready");
        } else if (runningJob != null && Ui.prompt(shell.window())
                .title("Stop what's running?")
                .message("Lumina stops as soon as it safely can. A patch that's stopped halfway is put back how it was.")
                .confirm("Stop").cancel("Keep going").destructive()
                .ask()) {
            runningJob.cancel();
        }
    }

    private void finishJob() {
        runningJob = null;
        progressDetail.textProperty().unbind();
        shell.unbindStatus();
        setBusy(false);
        if (gameDirectory != null && Files.isDirectory(gameDirectory)) {
            gamePath.setText(gameDirectory.toString());
            readyTitle.setText(gameDirectory.getFileName().toString());
            refreshGameInfo();
        }
    }

    /**
     * What happened, on the page until the next job or game, instead of a toast that disappears. It takes keyboard
     * focus, so a screen reader reads it out and a keyboard user is right at its buttons.
     */
    private void showResult(String kind, String title, String text, Button... actions) {
        Icon icon = switch (kind) { case "success" -> Icon.CHECKMARK_CIRCLE; case "error" -> Icon.ERROR_CIRCLE; default -> Icon.INFO; };
        Label heading = new Label(title);
        heading.getStyleClass().add("result-title");
        heading.setWrapText(true);
        heading.setMinHeight(Region.USE_PREF_SIZE);
        Label body = new Label(text);
        body.getStyleClass().add("result-text");
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);
        HBox top = Layout.row("toolbar", icon.create(20), heading);
        resultCard.getChildren().setAll(top, body);
        if (actions.length > 0) resultCard.getChildren().add(Layout.row("toolbar", actions));
        resultCard.getStyleClass().removeAll("success", "error", "info");
        resultCard.getStyleClass().add(kind);
        resultCard.setFocusTraversable(true);
        resultCard.setAccessibleRole(AccessibleRole.TEXT);
        resultCard.setAccessibleText(title + ". " + text);
        resultCard.setManaged(true);
        Ui.reveal(resultCard, 220);
        resultCard.requestFocus();
    }

    private static Button resultAction(String label, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("secondary-button");
        b.setMnemonicParsing(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void hideResult() {
        resultCard.setVisible(false);
        resultCard.setManaged(false);
        resultCard.getChildren().clear();
    }

    private void showProgress() {
        if (progressBox.isVisible()) return;
        progressBox.setManaged(true);
        Ui.reveal(progressBox, 250);
    }

    private void hideProgress() {
        progressBox.setVisible(false);
        progressBox.setManaged(false);
    }

    /** SmokeAPI needs the original next to it as _o.dll, so for it the .bak option is shown as on and locked. */
    private void applyBackupOption(boolean busy) {
        boolean proxy = emulatorBox.getValue() != null && emulatorBox.getValue().isProxy();
        syncingBackup = true;
        try {
            backupCheckBox.setSelected(proxy || AppSettings.isBackupDlls());
        } finally {
            syncingBackup = false;
        }
        backupCheckBox.setText(proxy ? "The original files are always kept (SmokeAPI needs them)" : "Keep a backup of the original files");
        backupCheckBox.setTooltip(new Tooltip(proxy
                ? "SmokeAPI loads the original steam_api DLL from <name>_o.dll beside it, so it is always kept."
                : "Keeps a copy of every original steam_api DLL as <name>.dll.bak next to it, so Restore can put it back."));
        backupCheckBox.setDisable(busy || proxy);
    }

    private void setBusy(boolean busy) {
        for (Control c : List.of(backButton, changeDirButton, unpackCheckBox, emulatorBox, appIdField, emulatorSettingsButton)) c.setDisable(busy);
        applyBackupOption(busy);
        shell.setNavigationLocked(busy);
        crackButton.getStyleClass().removeAll("accent", "danger");
        crackButton.getStyleClass().add(busy ? "danger" : "accent");
        updateButtons();
    }

    private void flashCrackButton() {
        crackButton.setText("Done");
        crackButton.getStyleClass().add("success");
        Ui.pop(crackButton);
        PauseTransition back = new PauseTransition(Duration.seconds(1.6));
        back.setOnFinished(e -> {
            crackButton.getStyleClass().remove("success");
            updateButtons();
        });
        back.play();
    }

    private void updateButtons() {
        boolean busy = isBusy();
        crackButton.setDisable(!busy && gameDirectory == null);
        if (!crackButton.getStyleClass().contains("success")) crackButton.setText(crackLabel());
        restoreButton.setDisable(busy || gameInfo == null || gameInfo.backupCount() == 0);
        openFolderButton.setDisable(gameDirectory == null);
        emulatorSettingsButton.setDisable(busy || gameInfo == null || gameInfo.dllCount() == 0);
    }

    private void openEmulatorSettings() {
        if (isBusy() || gameInfo == null || gameInfo.dllCount() == 0) return;
        Emulator emulator = gameInfo.patch().emulator()
                .flatMap(name -> Arrays.stream(Emulator.values()).filter(e -> e.toString().equals(name)).findFirst())
                .orElse(emulatorBox.getValue());
        List<Path> dirs = gameInfo.patch().dlls().stream()
                .map(d -> emulator.settingsDir(d.dll()))
                .distinct()
                .toList();
        if (EmulatorSettingsDialog.show(shell.window(), gameName.getText(), dirs, emulator, appIdField.getText().trim())) {
            log("Saved " + emulator + " settings to " + dirs.size() + " " + emulator.settingsLocation() + " location(s).", false);
            shell.toast(Toaster.Kind.SUCCESS, "Emulator settings saved.");
        }
    }

    private String crackLabel() {
        if (waitTimer != null) return "Stop waiting";
        if (runningJob != null) return "Cancel";
        return alreadyPatchedWith(emulatorBox.getValue()) ? "Re-apply patch" : "Patch game";
    }

    private boolean isBusy() {
        return runningJob != null || waitTimer != null || checkingProcesses || planning;
    }

    private StatusSink sink() {
        return (msg, error) -> Platform.runLater(() -> log(msg, error));
    }

    private void log(String message, boolean error) {
        var items = logList.getItems();
        items.add(TIME.format(LocalTime.now()) + (error ? "  ✖  " : "     ") + message);
        if (items.size() > MAX_LOG_LINES) items.remove(0, items.size() - MAX_LOG_LINES);
        logList.scrollTo(items.size() - 1);
    }
}
