package com.javafxapp.javafxapp.ui.pages;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.support.Diagnostics;
import com.javafxapp.javafxapp.safety.DefenderExclusion;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorInstaller;
import com.javafxapp.javafxapp.unpack.Steamless;
import com.javafxapp.javafxapp.ui.components.AccentPicker;
import com.javafxapp.javafxapp.ui.components.Toaster;
import com.javafxapp.javafxapp.ui.shell.AppShell;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.Motion;
import com.javafxapp.javafxapp.ui.shell.Theme;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SettingsController implements Page {

    private static final PseudoClass MISSING = PseudoClass.getPseudoClass("missing");

    private AppShell shell;
    private Emulator downloading;
    private final DoubleProperty downloadProgress = new SimpleDoubleProperty(-1);
    private final StringProperty downloadText = new SimpleStringProperty("");

    private boolean downloadingSteamless;

    @FXML private VBox settingsContainer, emulatorList, toolList;
    @FXML private CheckBox unpackDrmCheckbox;
    @FXML private Label moveDirLabel, backupDirLabel, appDataLabel;
    @FXML private Button selectMoveDir, openMoveDir, selectBackupDir, openBackupDir;
    @FXML private Button openAppData, openLogsButton, clearRecentButton, resetSettings, excludeEmulatorsButton, copyDiagnosticsButton;
    @FXML private CheckBox logToFileCheckbox, moveGameDirCheckbox, backupGameDirCheckbox, previewCheckbox;
    @FXML private ToggleButton themeDark, themeLight, themeSystem;
    @FXML private Label themeHint, motionHint, themeLabel, motionLabel;
    @FXML private ToggleButton motionSystem, motionFull, motionReduced;
    @FXML private Label accentLabel;
    @FXML private HBox accentHost;
    private AccentPicker accentPicker;
    @FXML private Button accentReset;
    @FXML private VBox settingsNav, appearanceCard, backupCard, emulatorsCard, drmCard, dataCard;
    @FXML private ScrollPane settingsScroll;
    private final Map<VBox, Button> sectionButtons = new LinkedHashMap<>();
    private Runnable motionSync;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    @FXML
    public void initialize() {
        setUpThemeToggle();
        setUpMotionToggle();
        setUpAccentPicker();
        setUpSectionList();
        themeLabel.setLabelFor(themeDark);
        motionLabel.setLabelFor(motionSystem);
        logToFileCheckbox.setOnAction(e -> {
            AppSettings.setLogToFile(logToFileCheckbox.isSelected());
            shell.setStatus("Logging to file " + (logToFileCheckbox.isSelected() ? "enabled" : "disabled"));
        });
        previewCheckbox.setOnAction(e -> AppSettings.setPreviewBeforePatch(previewCheckbox.isSelected()));
        unpackDrmCheckbox.setOnAction(e -> AppSettings.setUnpackDrm(unpackDrmCheckbox.isSelected()));
        moveGameDirCheckbox.setOnAction(e -> {
            AppSettings.setMoveGameDir(moveGameDirCheckbox.isSelected());
            refresh();
        });
        backupGameDirCheckbox.setOnAction(e -> {
            AppSettings.setBackupGameDir(backupGameDirCheckbox.isSelected());
            refresh();
        });

        selectMoveDir.setOnAction(e -> chooseDir("Select move destination",
                AppSettings::getMoveGameDirPath, AppSettings::setMoveGameDirPath));
        selectBackupDir.setOnAction(e -> chooseDir("Select backup destination",
                AppSettings::getBackupGameDirPath, AppSettings::setBackupGameDirPath));
        openMoveDir.setOnAction(e -> Ui.openFolder(Path.of(AppSettings.getMoveGameDirPath())));
        openBackupDir.setOnAction(e -> Ui.openFolder(Path.of(AppSettings.getBackupGameDirPath())));

        excludeEmulatorsButton.setOnAction(e -> excludeEmulators());
        copyDiagnosticsButton.setOnAction(e -> copyDiagnostics());
        copyDiagnosticsButton.setTooltip(new Tooltip("Copies versions, settings, what Lumina found in the game open on Home, and the end of the log, "
                + "for a bug report. Your Windows account name is left out."));
        appDataLabel.setText(AppPaths.home().toString());
        openAppData.setOnAction(e -> openCreating(AppPaths.home()));
        openLogsButton.setOnAction(e -> openCreating(AppPaths.logs()));
        clearRecentButton.setOnAction(e -> {
            AppSettings.clearRecentGames();
            shell.toast(Toaster.Kind.INFO, "Recent games cleared.");
        });
        resetSettings.setOnAction(e -> {
            if (!Ui.prompt(shell.window())
                    .title("Reset all settings?")
                    .message("Everything on this page goes back to its default, and the theme goes back to dark. Your history and backups aren't touched.")
                    .confirm("Reset").destructive()
                    .ask()) return;
            AppSettings.reset();
            Theme.choose(AppSettings.Theme.DARK, settingsContainer.getScene());
            Theme.apply(settingsContainer.getScene());
            syncAccent();
            refresh();
            shell.toast(Toaster.Kind.INFO, "Settings reset to defaults.");
        });
    }

    private void excludeEmulators() {
        Path dir = AppPaths.emulators();
        Path tools = AppPaths.tools();
        if (!Ui.prompt(shell.window())
                .title("Exclude the emulator and tools folders from Defender?")
                .message("Defender stops scanning them, so it can't quarantine the emulator DLLs and Steamless that Lumina keeps there.")
                .details(java.util.List.of(dir.toString(), tools.toString()))
                .note("Windows will ask for administrator permission.")
                .confirm("Add exclusion").width(520)
                .ask()) return;
        excludeEmulatorsButton.setDisable(true);
        shell.setStatus("Waiting for administrator permission...");
        javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
            @Override
            protected Void call() throws Exception {
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.createDirectories(tools);
                DefenderExclusion.add(java.util.List.of(dir, tools));
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            excludeEmulatorsButton.setDisable(false);
            shell.setStatus("Exclusion added");
            shell.toast(Toaster.Kind.SUCCESS, "Defender exclusion added for the emulator folder.");
        });
        task.setOnFailed(e -> {
            excludeEmulatorsButton.setDisable(false);
            shell.setStatus("No exclusion added");
            shell.toast(Toaster.Kind.ERROR, com.javafxapp.javafxapp.common.Errors.describe(task.getException()));
        });
        Ui.start(task, "defender-exclusion");
    }

    private void copyDiagnostics() {
        copyDiagnosticsButton.setDisable(true);
        var game = shell.currentGame();
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return Diagnostics.build(game);
            }
        };
        task.setOnSucceeded(e -> {
            copyDiagnosticsButton.setDisable(false);
            ClipboardContent content = new ClipboardContent();
            content.putString(task.getValue());
            Clipboard.getSystemClipboard().setContent(content);
            shell.toast(Toaster.Kind.SUCCESS, game.isPresent()
                    ? "Copied, including " + game.get().getFileName() + ". Paste it into your bug report."
                    : "Copied. Open the game on Home first if the report is about one, then copy again.");
        });
        task.setOnFailed(e -> {
            copyDiagnosticsButton.setDisable(false);
            Log.error("Could not build the diagnostics", task.getException());
            shell.toast(Toaster.Kind.ERROR, "Could not collect the diagnostics: " + Errors.describe(task.getException()));
        });
        Ui.start(task, "diagnostics");
    }

    // the section list: a click scrolls to the card, scrolling highlights the card you're looking at
    private void setUpSectionList() {
        Map<String, VBox> sections = new LinkedHashMap<>();
        sections.put("Appearance", appearanceCard);
        sections.put("Backup & move", backupCard);
        sections.put("Emulators", emulatorsCard);
        sections.put("DRM unpacking", drmCard);
        sections.put("App data & logs", dataCard);
        sections.forEach((name, card) -> {
            Button b = new Button(name);
            b.getStyleClass().addAll("sidebar-button", "nav-button");
            b.setMnemonicParsing(false);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAccessibleHelp("Jumps to the " + name + " settings");
            b.setOnAction(e -> scrollTo(card));
            sectionButtons.put(card, b);
            settingsNav.getChildren().add(b);
        });
        settingsScroll.vvalueProperty().addListener((o, a, v) -> markSection());
        settingsScroll.viewportBoundsProperty().addListener((o, a, v) -> markSection());
        markSection();
    }

    private void scrollTo(VBox card) {
        double content = settingsContainer.getLayoutBounds().getHeight();
        double viewport = settingsScroll.getViewportBounds().getHeight();
        if (content > viewport) settingsScroll.setVvalue(Math.min(1, Math.max(0, (card.getBoundsInParent().getMinY() - 16) / (content - viewport))));
        // move keyboard focus into the section too, so Tab carries on from there
        card.lookupAll(".button, .check-box, .toggle-button").stream().filter(n -> !n.isDisabled()).findFirst().ifPresent(javafx.scene.Node::requestFocus);
        markSection(card);
    }

    private void markSection() {
        double content = settingsContainer.getLayoutBounds().getHeight();
        double viewport = settingsScroll.getViewportBounds().getHeight();
        double top = settingsScroll.getVvalue() * Math.max(0, content - viewport);
        VBox current = appearanceCard;
        for (VBox card : sectionButtons.keySet()) if (card.getBoundsInParent().getMinY() - 40 <= top) current = card;
        // at the very bottom the last card is the one you're reading, even if it's short
        if (content > viewport && settingsScroll.getVvalue() >= 0.999) current = dataCard;
        markSection(current);
    }

    private void markSection(VBox current) {
        sectionButtons.forEach((card, b) -> b.pseudoClassStateChanged(SELECTED, card == current));
    }

    private void setUpMotionToggle() {
        ToggleGroup group = new ToggleGroup();
        Map<AppSettings.Motion, ToggleButton> buttons = new EnumMap<>(Map.of(
                AppSettings.Motion.SYSTEM, motionSystem, AppSettings.Motion.FULL, motionFull, AppSettings.Motion.REDUCED, motionReduced));
        buttons.forEach((motion, button) -> {
            button.setToggleGroup(group);
            button.setUserData(motion);
        });
        Runnable sync = () -> {
            buttons.get(AppSettings.getMotion()).setSelected(true);
            motionHint.setText(AppSettings.getMotion() == AppSettings.Motion.SYSTEM
                    ? "Windows has animations " + (Motion.windowsAnimates() ? "on" : "off") : "");
        };
        sync.run();
        motionSync = sync;
        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
            else {
                AppSettings.setMotion((AppSettings.Motion) now.getUserData());
                sync.run();
            }
        });
    }

    private void setUpAccentPicker() {
        accentPicker = new AccentPicker(color -> {
            Theme.chooseAccent(color.equals(AccentPicker.DEFAULT) ? null : color, settingsContainer.getScene());
            accentReset.setDisable(Theme.accent() == null);
        });
        accentHost.getChildren().add(accentPicker);
        accentLabel.setLabelFor(accentPicker);
        syncAccent();
        accentReset.setOnAction(e -> {
            Theme.chooseAccent(null, settingsContainer.getScene());
            syncAccent();
        });
    }

    private void syncAccent() {
        Color saved = Theme.accent();
        accentPicker.setValue(saved == null ? AccentPicker.DEFAULT : saved);
        accentReset.setDisable(saved == null);
    }

    private void setUpThemeToggle() {
        ToggleGroup group = new ToggleGroup();
        Map<AppSettings.Theme, ToggleButton> buttons = new EnumMap<>(Map.of(
                AppSettings.Theme.DARK, themeDark, AppSettings.Theme.LIGHT, themeLight, AppSettings.Theme.SYSTEM, themeSystem));
        buttons.forEach((theme, button) -> {
            button.setToggleGroup(group);
            button.setUserData(theme);
        });
        Runnable sync = () -> {
            buttons.get(Theme.choiceProperty().get()).setSelected(true);
            themeHint.setText(Theme.choiceProperty().get() == AppSettings.Theme.SYSTEM
                    ? "Following Windows (" + Theme.current().name().toLowerCase() + ")" : "");
        };
        sync.run();
        Theme.choiceProperty().addListener((o, old, now) -> sync.run());
        Theme.currentProperty().addListener((o, old, now) -> sync.run());
        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
            else if (now.getUserData() != Theme.choiceProperty().get())
                Theme.choose((AppSettings.Theme) now.getUserData(), settingsContainer.getScene());
        });
    }

    @Override
    public void attach(AppShell shell) {
        this.shell = shell;
        refresh();
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        if (motionSync != null) motionSync.run();
        logToFileCheckbox.setSelected(AppSettings.isLogToFile());
        moveGameDirCheckbox.setSelected(AppSettings.isMoveGameDir());
        backupGameDirCheckbox.setSelected(AppSettings.isBackupGameDir());
        previewCheckbox.setSelected(AppSettings.isPreviewBeforePatch());
        unpackDrmCheckbox.setSelected(AppSettings.isUnpackDrm());

        showPath(moveDirLabel, selectMoveDir, openMoveDir, AppSettings.getMoveGameDirPath(), AppSettings.isMoveGameDir());
        showPath(backupDirLabel, selectBackupDir, openBackupDir, AppSettings.getBackupGameDirPath(), AppSettings.isBackupGameDir());

        emulatorList.getChildren().clear();
        for (Emulator emulator : Emulator.values()) emulatorList.getChildren().add(emulatorRow(emulator));
        toolList.getChildren().setAll(steamlessRow());
    }

    private boolean downloadBusy() {
        return downloading != null || downloadingSteamless;
    }

    private HBox steamlessRow() {
        Label name = new Label("Steamless");
        name.getStyleClass().add("emulator-name");
        boolean installed = Steamless.isInstalled();
        Label status = new Label(Steamless.installedVersion().map(v -> "Installed · " + v).orElse("Not installed"));
        status.getStyleClass().addAll("badge", installed ? "badge-ok" : "badge-off");
        Label desc = new Label("Removes SteamStub DRM from game executables. Downloaded the first time a game needs it.");
        desc.getStyleClass().add("path-label");
        VBox text = Layout.column("stack", Layout.row("toolbar", name, status), desc);

        if (downloadingSteamless) {
            ProgressBar bar = new ProgressBar();
            bar.progressProperty().bind(downloadProgress);
            bar.setMaxWidth(Double.MAX_VALUE);
            Label detail = new Label();
            detail.textProperty().bind(downloadText);
            detail.getStyleClass().add("progress-detail");
            text.getChildren().addAll(bar, detail);
        }

        Button download = new Button(installed ? "Update" : "Download");
        download.getStyleClass().add("secondary-button");
        download.setOnAction(e -> downloadSteamless());
        download.setDisable(downloadBusy());
        Button open = new Button("Open");
        open.getStyleClass().add("secondary-button");
        open.setOnAction(e -> Ui.openFolder(Steamless.dir()));
        open.setDisable(!installed);
        Button remove = new Button("Remove");
        remove.getStyleClass().add("secondary-button");
        remove.setOnAction(e -> removeSteamless());
        remove.setDisable(downloadBusy() || !installed);

        HBox.setHgrow(text, Priority.ALWAYS);
        return Layout.row("card compact inset toolbar", text, Layout.spacer(), download, open, remove);
    }

    private void downloadSteamless() {
        downloadingSteamless = true;
        downloadProgress.set(-1);
        downloadText.set("Connecting…");
        shell.setNavigationLocked(true);
        refresh();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.nanoTime();
                Steamless.install((m, err) -> Platform.runLater(() -> downloadText.set(m)), (done, total) -> {
                    double secs = Math.max((System.nanoTime() - start) / 1e9, 0.001);
                    String text = FileOps.formatSize(done) + (total > 0 ? " of " + FileOps.formatSize(total) : "")
                            + "  ·  " + FileOps.formatSize((long) (done / secs)) + "/s";
                    Platform.runLater(() -> {
                        downloadProgress.set(total > 0 ? (double) done / total : -1);
                        downloadText.set(text);
                    });
                });
                return null;
            }
        };
        Runnable done = () -> {
            downloadingSteamless = false;
            shell.setNavigationLocked(false);
            refresh();
        };
        task.setOnSucceeded(e -> {
            done.run();
            shell.toast(Toaster.Kind.SUCCESS, "Steamless " + Steamless.installedVersion().orElse("") + " is ready.");
        });
        task.setOnFailed(e -> {
            done.run();
            Log.error("Steamless download failed", task.getException());
            shell.toast(Toaster.Kind.ERROR, "Steamless download failed: " + Errors.describe(task.getException()));
        });
        Ui.start(task, "steamless-download");
    }

    private void removeSteamless() {
        if (!Ui.prompt(shell.window())
                .title("Remove Steamless?")
                .message("It's downloaded again the next time a patch needs it. Games you already unpacked aren't affected.")
                .confirm("Remove").destructive()
                .ask()) return;
        try {
            Steamless.uninstall();
            shell.toast(Toaster.Kind.INFO, "Steamless removed.");
        } catch (IOException e) {
            shell.toast(Toaster.Kind.ERROR, "Could not remove Steamless: " + Errors.describe(e));
        }
        refresh();
    }

    private HBox emulatorRow(Emulator emulator) {
        Label name = new Label(emulator.toString());
        name.getStyleClass().add("emulator-name");
        Label status = new Label(emulator.installedVersion().map(v -> "Installed · " + v).orElse("Not installed"));
        status.getStyleClass().addAll("badge", emulator.isInstalled() ? "badge-ok" : "badge-off");
        Label desc = new Label(emulator.description());
        desc.getStyleClass().add("path-label");
        VBox text = Layout.column("stack", Layout.row("toolbar", name, status), desc);

        if (emulator == downloading) {
            ProgressBar bar = new ProgressBar();
            bar.progressProperty().bind(downloadProgress);
            bar.setMaxWidth(Double.MAX_VALUE);
            Label detail = new Label();
            detail.textProperty().bind(downloadText);
            detail.getStyleClass().add("progress-detail");
            text.getChildren().addAll(bar, detail);
        }

        Button download = new Button(emulator.isInstalled() ? "Update" : "Download");
        download.getStyleClass().add("secondary-button");
        download.setOnAction(e -> download(emulator));
        download.setDisable(downloadBusy());
        Button open = new Button("Open");
        open.getStyleClass().add("secondary-button");
        open.setOnAction(e -> Ui.openFolder(emulator.dir()));
        open.setDisable(!emulator.isInstalled());
        Button remove = new Button("Remove");
        remove.getStyleClass().add("secondary-button");
        remove.setOnAction(e -> remove(emulator));
        remove.setDisable(downloadBusy() || !emulator.isInstalled());

        HBox.setHgrow(text, Priority.ALWAYS);
        return Layout.row("card compact inset toolbar", text, Layout.spacer(), download, open, remove);
    }

    private void download(Emulator emulator) {
        downloading = emulator;
        downloadProgress.set(-1);
        downloadText.set("Connecting…");
        shell.setNavigationLocked(true);
        refresh();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.nanoTime();
                EmulatorInstaller.install(emulator, (m, err) -> Platform.runLater(() -> downloadText.set(m)), (done, total) -> {
                    double secs = Math.max((System.nanoTime() - start) / 1e9, 0.001);
                    String text = FileOps.formatSize(done) + (total > 0 ? " of " + FileOps.formatSize(total) : "")
                            + "  ·  " + FileOps.formatSize((long) (done / secs)) + "/s";
                    Platform.runLater(() -> {
                        downloadProgress.set(total > 0 ? (double) done / total : -1);
                        downloadText.set(text);
                    });
                });
                return null;
            }
        };
        Runnable done = () -> {
            downloading = null;
            shell.setNavigationLocked(false);
            refresh();
        };
        task.setOnSucceeded(e -> {
            done.run();
            shell.toast(Toaster.Kind.SUCCESS, emulator + " " + emulator.installedVersion().orElse("") + " is ready.");
        });
        task.setOnFailed(e -> {
            done.run();
            Log.error(emulator + " download failed", task.getException());
            shell.toast(Toaster.Kind.ERROR, emulator + " download failed: " + Errors.describe(task.getException()));
        });
        Ui.start(task, "emulator-download");
    }

    private void remove(Emulator emulator) {
        if (!Ui.prompt(shell.window())
                .title("Remove " + emulator + "?")
                .message("It's downloaded again the next time a patch needs it. Games you already patched aren't affected.")
                .confirm("Remove").destructive()
                .ask()) return;
        try {
            EmulatorInstaller.uninstall(emulator);
            shell.toast(Toaster.Kind.INFO, emulator + " removed.");
        } catch (IOException e) {
            shell.toast(Toaster.Kind.ERROR, "Could not remove " + emulator + ": " + Errors.describe(e));
        }
        refresh();
    }

    private static void showPath(Label label, Button browse, Button open, String path, boolean enabled) {
        boolean exists = !path.isBlank() && Files.isDirectory(Path.of(path));
        browse.setDisable(!enabled);
        open.setDisable(!enabled || !exists);
        label.setDisable(!enabled);
        label.setText(path.isBlank() ? "No directory selected" : path);
        label.setTooltip(path.isBlank() ? null : new Tooltip(path));
        label.pseudoClassStateChanged(MISSING, enabled && !exists);
    }

    private void chooseDir(String title, Supplier<String> current, Consumer<String> save) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        String cur = current.get();
        if (!cur.isBlank() && Files.isDirectory(Path.of(cur))) chooser.setInitialDirectory(new File(cur));
        File selected = chooser.showDialog(shell.window());
        if (selected != null) {
            save.accept(selected.getAbsolutePath());
            shell.setStatus("Directory selected: " + selected.getAbsolutePath());
            refresh();
        }
    }

    private void openCreating(Path dir) {
        try {
            Ui.openFolder(Files.createDirectories(dir));
        } catch (IOException e) {
            shell.toast(Toaster.Kind.ERROR, "Could not open " + dir + ": " + Errors.describe(e));
        }
    }
}
