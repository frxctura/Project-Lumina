package com.javafxapp.javafxapp.ui.shell;

import com.javafxapp.javafxapp.config.AppInfo;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.ui.components.Icon;
import com.javafxapp.javafxapp.ui.components.Toaster;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ShellController implements AppShell {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Map<View, Parent> roots = new EnumMap<>(View.class);
    private final Map<View, Page> pages = new EnumMap<>(View.class);
    private final Map<View, Button> navButtons = new EnumMap<>(View.class);
    private View current;
    private boolean navigationLocked;
    private Toaster toaster;
    private FadeTransition pulse;
    private Consumer<Path> gameOpener = p -> {};
    private Consumer<List<Path>> batchHandler = l -> {};
    private Supplier<Path> currentGameSource = () -> null;

    @FXML private BorderPane shellRoot;
    @FXML private StackPane content;
    @FXML private VBox toastLayer;
    @FXML private Button navHome, navLibrary, navHistory, navSettings;
    @FXML private ImageView logo;
    @FXML private Region activityDot;
    @FXML private Button contactButton;
    @FXML private Button themeToggle;
    @FXML private Label statusLabel, versionLabel;

    @FXML
    public void initialize() {
        toaster = new Toaster(toastLayer);
        versionLabel.setText("Lumina " + AppInfo.version());
        navButtons.put(View.MAIN, navHome);
        navButtons.put(View.LIBRARY, navLibrary);
        navButtons.put(View.HISTORY, navHistory);
        navButtons.put(View.SETTINGS, navSettings);
        navButtons.forEach((view, button) -> button.setOnAction(e -> show(view)));
        Theme.bindImage(logo, Ui.image("logo.png"), Ui.image("logo-dark.png"));
        contactButton.setOnAction(e -> Ui.openUrl(Ui.CONTACT_URL));
        contactButton.setAccessibleText("frxctura on GitHub (opens in the browser)");
        themeToggle.setOnAction(e -> Theme.choose(Theme.isLight() ? AppSettings.Theme.DARK : AppSettings.Theme.LIGHT, shellRoot.getScene()));
        Theme.currentProperty().addListener((o, old, now) -> updateThemeToggle());
        updateThemeToggle();
        setStatus("Ready");
    }

    private void updateThemeToggle() {
        String action = Theme.isLight() ? "Switch to dark theme" : "Switch to light theme";
        themeToggle.setGraphic((Theme.isLight() ? Icon.WEATHER_MOON : Icon.WEATHER_SUNNY).create(18));
        themeToggle.setTooltip(new Tooltip(action));
        themeToggle.setAccessibleText(action);
    }

    public void addPage(View view, Parent root, Page page) {
        root.setVisible(false);
        roots.put(view, root);
        pages.put(view, page);
        content.getChildren().add(root);
        page.attach(this);
    }

    public void setGameOpener(Consumer<Path> opener) {
        this.gameOpener = opener;
    }

    public void setCurrentGameSource(Supplier<Path> source) {
        this.currentGameSource = source;
    }

    @Override
    public Optional<Path> currentGame() {
        return Optional.ofNullable(currentGameSource.get());
    }

    public void setBatchHandler(Consumer<List<Path>> handler) {
        this.batchHandler = handler;
    }

    public void start() {
        show(View.MAIN);
    }

    public void close() {
        pages.values().forEach(Page::onClose);
    }

    @Override
    public void show(View view) {
        if (view == current) return;
        View previous = current;
        current = view;
        navButtons.forEach((v, b) -> b.pseudoClassStateChanged(SELECTED, v == view));

        if (previous != null) Ui.hide(roots.get(previous), 140);
        int direction = previous == null ? 0 : (view.ordinal() > previous.ordinal() ? 24 : -24);
        Parent root = roots.get(view);
        root.toFront();
        Ui.slideIn(root, direction, 0, 240, previous == null ? 0 : 60);
        pages.get(view).onShow();
    }

    @Override
    public void openGame(Path dir) {
        if (refuseWhileLocked()) return;
        show(View.MAIN);
        gameOpener.accept(dir);
    }

    @Override
    public void enqueueBatch(List<Path> dirs) {
        if (refuseWhileLocked()) return;
        show(View.LIBRARY);
        batchHandler.accept(dirs);
    }

    @Override
    public void setStatus(String text) {
        if (!statusLabel.textProperty().isBound()) statusLabel.setText(text);
    }

    @Override
    public void bindStatus(ObservableValue<String> text) {
        statusLabel.textProperty().bind(text);
        showActivity(true);
    }

    @Override
    public void unbindStatus() {
        statusLabel.textProperty().unbind();
        showActivity(false);
    }

    private void showActivity(boolean on) {
        activityDot.setVisible(on);
        activityDot.setManaged(on);
        if (on) {
            if (Motion.reduced()) return; // the dot stays, it just doesn't pulse
            if (pulse == null) {
                pulse = new FadeTransition(Duration.millis(700), activityDot);
                pulse.setFromValue(0.3);
                pulse.setToValue(1);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(Animation.INDEFINITE);
            }
            pulse.play();
        } else if (pulse != null) {
            pulse.stop();
            activityDot.setOpacity(1);
        }
    }

    @Override
    public void setNavigationLocked(boolean locked) {
        navigationLocked = locked;
        navButtons.forEach((v, b) -> b.setDisable(locked && v != current));
    }

    private boolean refuseWhileLocked() {
        if (navigationLocked) toast(Toaster.Kind.INFO, "Wait for the current task to finish first.");
        return navigationLocked;
    }

    @Override
    public void toast(Toaster.Kind kind, String text) {
        toaster.show(kind, text, null, null);
    }

    @Override
    public void toast(Toaster.Kind kind, String text, String actionText, Runnable action) {
        toaster.show(kind, text, actionText, action);
    }

    @Override
    public Window window() {
        return shellRoot.getScene().getWindow();
    }
}
