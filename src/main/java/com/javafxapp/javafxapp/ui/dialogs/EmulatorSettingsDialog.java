package com.javafxapp.javafxapp.ui.dialogs;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorConfig;
import com.javafxapp.javafxapp.library.SteamStore;
import com.javafxapp.javafxapp.ui.shell.Theme;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class EmulatorSettingsDialog {

    private static final String STEAM_ID_PATTERN = "7656119\\d{10}";

    private static final class DlcRow {
        final StringProperty appId = new SimpleStringProperty();
        final StringProperty name = new SimpleStringProperty();

        DlcRow(String appId, String name) {
            this.appId.set(appId);
            this.name.set(name);
        }
    }

    private final Stage stage = new Stage();
    private final List<Path> settingsDirs;
    private final Emulator emulator;
    private final String appId;
    private final ObservableList<DlcRow> dlcs = FXCollections.observableArrayList();

    private final TextField username = new TextField();
    private final TextField steamId = new TextField();
    private final ComboBox<String> language = new ComboBox<>();
    private final CheckBox offline = new CheckBox("Offline mode (no LAN / network features)");
    private final CheckBox unlockAll = new CheckBox("Unlock all DLC");
    private final Label dlcCount = new Label();
    private final Label fetchStatus = new Label();
    private final Button fetchButton = new Button("FETCH FROM STEAM");
    private boolean saved;
    private Task<?> fetchTask;

    private EmulatorSettingsDialog(Window owner, String game, List<Path> settingsDirs, Emulator emulator, String appId) {
        this.settingsDirs = settingsDirs;
        this.emulator = emulator;
        this.appId = appId;

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Emulator settings · " + game);
        stage.getIcons().setAll(((Stage) owner).getIcons());
        stage.setMinWidth(560);
        stage.setMinHeight(480);

        VBox root = Layout.column("app-root main-area page", header(game), form(), dlcSection(), footer());
        root.getStylesheets().addAll(stylesheet("styles.css"), stylesheet("main.css"));
        Theme.apply(root);

        Scene scene = new Scene(root, 640, 560);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.setOnHidden(e -> { if (fetchTask != null) fetchTask.cancel(); });
        load();
    }

    public static boolean show(Window owner, String game, List<Path> settingsDirs, Emulator emulator, String appId) {
        EmulatorSettingsDialog dialog = new EmulatorSettingsDialog(owner, game, settingsDirs, emulator, appId);
        dialog.stage.showAndWait();
        return dialog.saved;
    }

    private VBox header(String game) {
        Label title = new Label("Emulator settings");
        title.getStyleClass().add("title");
        Label sub = new Label(game + "  ·  " + emulator + "  ·  saved to " + settingsDirs.size()
                + " " + emulator.settingsLocation() + (settingsDirs.size() == 1 ? " location" : " locations"));
        sub.getStyleClass().add("subtitle");
        sub.setTooltip(new Tooltip(String.join("\n", settingsDirs.stream().map(Path::toString).toList())));
        return Layout.column("stack", title, sub);
    }

    private GridPane form() {
        username.setPromptText("Emulator default");
        steamId.setPromptText("Generated automatically");
        steamId.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,17}") ? c : null));
        language.getItems().setAll(EmulatorConfig.LANGUAGES);
        language.setEditable(true);
        language.setPromptText("Emulator default");
        language.setMaxWidth(Double.MAX_VALUE);
        offline.getStyleClass().add("app-check");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.getColumnConstraints().addAll(new ColumnConstraints(110), columnGrow());
        addRow(grid, 0, "Username", username);
        addRow(grid, 1, "Steam ID", steamId);
        addRow(grid, 2, "Language", language);
        grid.add(offline, 1, 3);
        // SmokeAPI leaves the player's own Steam account alone, so there is no username, ID, language or offline switch
        grid.setVisible(emulator.supportsIdentity());
        grid.setManaged(emulator.supportsIdentity());
        return grid;
    }

    private VBox dlcSection() {
        Label title = new Label("DLC");
        title.getStyleClass().add("section-label");
        dlcCount.getStyleClass().addAll("badge", "badge-off");
        dlcs.addListener((ListChangeListener<DlcRow>) c -> updateCount());

        Button add = new Button("ADD");
        Button remove = new Button("REMOVE");
        Button clear = new Button("CLEAR");
        for (Button b : List.of(fetchButton, add, remove, clear)) b.getStyleClass().add("secondary-button");
        fetchButton.setDisable(!SteamStore.isValidAppId(appId));
        fetchButton.setTooltip(new Tooltip(SteamStore.isValidAppId(appId)
                ? "Replace the list with every DLC Steam lists for App ID " + appId
                : "Set a Steam App ID on the main screen first"));
        fetchButton.setOnAction(e -> fetchDlcs());

        TableView<DlcRow> table = new TableView<>(dlcs);
        table.setEditable(true);
        table.setPlaceholder(new Label("No DLC listed"));
        table.getStyleClass().add("dlc-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<DlcRow, String> idCol = new TableColumn<>("App ID");
        idCol.setCellValueFactory(r -> r.getValue().appId);
        idCol.setCellFactory(TextFieldTableCell.forTableColumn());
        idCol.setMaxWidth(110);
        idCol.setMinWidth(90);
        TableColumn<DlcRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(r -> r.getValue().name);
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        table.getColumns().setAll(List.of(idCol, nameCol));
        VBox.setVgrow(table, Priority.ALWAYS);

        add.setOnAction(e -> {
            dlcs.add(new DlcRow("", "New DLC"));
            table.getSelectionModel().selectLast();
            table.scrollTo(dlcs.size() - 1);
            table.edit(dlcs.size() - 1, idCol);
        });
        remove.setOnAction(e -> dlcs.removeAll(List.copyOf(table.getSelectionModel().getSelectedItems())));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        clear.setOnAction(e -> dlcs.clear());

        unlockAll.getStyleClass().add("app-check");
        unlockAll.setVisible(emulator.supportsUnlockAll());
        unlockAll.setManaged(emulator.supportsUnlockAll());
        if (emulator == Emulator.SMOKEAPI) {
            unlockAll.setText("Unlock every DLC the game lists on Steam");
            title.setText("Extra DLC");
            title.setTooltip(new Tooltip("DLC without a store page, which Steam doesn't list. Needs the game's App ID."));
        }
        fetchStatus.getStyleClass().add("progress-detail");
        if (emulator == Emulator.SMOKEAPI && !SteamStore.isValidAppId(appId)) {
            // SmokeAPI files extra DLC under the game's App ID, so there is nowhere to put it without one
            add.setDisable(true);
            fetchStatus.setText("Set the Steam App ID on the main screen to add extra DLC.");
        }

        HBox bar = Layout.row("toolbar", title, dlcCount, Layout.spacer(), fetchButton, add, remove, clear);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = Layout.column("gap-2", bar, unlockAll, table, fetchStatus);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox footer() {
        Button openFolder = new Button("OPEN FOLDER");
        openFolder.getStyleClass().add("secondary-button");
        openFolder.setOnAction(e -> Ui.openFolder(settingsDirs.get(0).getParent()));
        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        Button save = new Button("SAVE");
        save.getStyleClass().addAll("primary-button", "accent");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        HBox footer = Layout.row("toolbar", openFolder, Layout.spacer(), cancel, save);
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    private void load() {
        try {
            EmulatorConfig config = EmulatorConfig.load(settingsDirs.get(0), emulator, appId);
            username.setText(config.username());
            steamId.setText(config.steamId());
            language.setValue(config.language().isBlank() ? null : config.language());
            offline.setSelected(config.offline());
            unlockAll.setSelected(config.unlockAllDlc());
            config.dlcs().forEach(d -> dlcs.add(new DlcRow(d.appId(), d.name())));
        } catch (IOException e) {
            fetchStatus.setText("Could not read existing settings: " + Errors.describe(e));
        }
        updateCount();
    }

    private void save() {
        String id = steamId.getText().trim();
        if (!id.isEmpty() && !id.matches(STEAM_ID_PATTERN)) {
            Ui.prompt(stage).title("That doesn't look like a Steam ID")
                    .message("A Steam ID is 17 digits and starts with 7656119. Leave it empty and the emulator makes one up.")
                    .inform("OK");
            return;
        }
        List<EmulatorConfig.Dlc> list = dlcs.stream()
                .filter(r -> r.appId.get() != null && r.appId.get().trim().matches("\\d+"))
                .map(r -> new EmulatorConfig.Dlc(r.appId.get().trim(), Objects.requireNonNullElse(r.name.get(), "").trim()))
                .distinct().toList();
        String lang = Objects.requireNonNullElse(language.getEditor().getText(), "").trim().toLowerCase();
        EmulatorConfig config = new EmulatorConfig(username.getText().trim(), id, lang, offline.isSelected(),
                unlockAll.isSelected(), list);
        try {
            for (Path dir : settingsDirs) config.save(dir, emulator, appId);
            Log.info("Saved emulator settings to " + settingsDirs);
            saved = true;
            stage.close();
        } catch (IOException e) {
            Log.error("Could not save emulator settings", e);
            Ui.prompt(stage).title("Couldn't save the settings").message(Errors.describe(e)).inform("OK");
        }
    }

    private void fetchDlcs() {
        fetchButton.setDisable(true);
        fetchStatus.setText("Asking Steam for the DLC list…");
        Task<List<SteamStore.Dlc>> task = new Task<>() {
            @Override
            protected List<SteamStore.Dlc> call() throws Exception {
                return SteamStore.dlcs(appId, (done, total) -> updateMessage("Looking up DLC names… " + done + " of " + total));
            }
        };
        fetchStatus.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            fetchStatus.textProperty().unbind();
            fetchButton.setDisable(false);
            List<SteamStore.Dlc> found = task.getValue();
            if (found.isEmpty()) {
                fetchStatus.setText("Steam lists no DLC for this game.");
                return;
            }
            dlcs.setAll(found.stream().map(d -> new DlcRow(d.appId(), d.name())).toList());
            fetchStatus.setText("Loaded " + found.size() + " DLC from Steam. Save to apply.");
        });
        task.setOnFailed(e -> {
            fetchStatus.textProperty().unbind();
            fetchButton.setDisable(false);
            fetchStatus.setText("Could not fetch DLC: " + Errors.describe(task.getException()));
        });
        fetchTask = task;
        Ui.start(task, "dlc-fetch");
    }

    private void updateCount() {
        dlcCount.setText(String.valueOf(dlcs.size()));
    }

    private static void addRow(GridPane grid, int row, String label, Control field) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        field.setMaxWidth(Double.MAX_VALUE);
        grid.add(l, 0, row);
        grid.add(field, 1, row);
    }

    private static ColumnConstraints columnGrow() {
        ColumnConstraints c = new ColumnConstraints();
        c.setHgrow(Priority.ALWAYS);
        return c;
    }

    private static String stylesheet(String name) {
        return Objects.requireNonNull(EmulatorSettingsDialog.class.getResource("/com/javafxapp/javafxapp/styles/" + name)).toExternalForm();
    }
}
