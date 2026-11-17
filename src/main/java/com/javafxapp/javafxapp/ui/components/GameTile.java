package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.library.SteamLibrary;
import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.animation.TranslateTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public final class GameTile extends StackPane {

    static final double WIDTH = 176;
    private static final double COVER_HEIGHT = Math.round(WIDTH * 215 / 460);
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Label size = new Label();
    private final Label badge = new Label("CHECKING");
    private final Label check = new Label("✔");
    private final ImageView image = new ImageView();
    private boolean selected;

    public GameTile(SteamLibrary.Game game, Image cover, Runnable onToggle, Runnable onOpen) {
        getStyleClass().add("game-tile");
        setAlignment(Pos.TOP_LEFT);

        Label initial = new Label(game.name().isBlank() ? "?" : game.name().substring(0, 1).toUpperCase());
        initial.getStyleClass().add("tile-initial");
        StackPane placeholder = new StackPane(initial);
        placeholder.getStyleClass().add("tile-cover-placeholder");

        image.setFitWidth(WIDTH);
        image.setFitHeight(COVER_HEIGHT);
        image.setSmooth(true);
        image.setVisible(false);
        if (cover != null) showWhenLoaded(cover, image);

        check.getStyleClass().add("tile-check");
        StackPane.setAlignment(check, Pos.TOP_RIGHT);
        StackPane.setMargin(check, new Insets(8));

        StackPane coverPane = new StackPane(placeholder, image, check);
        coverPane.setMinSize(WIDTH, COVER_HEIGHT);
        coverPane.setMaxSize(WIDTH, COVER_HEIGHT);
        // taller than the cover so only the top corners get rounded
        Rectangle clip = new Rectangle(WIDTH, COVER_HEIGHT + 20);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        coverPane.setClip(clip);

        Label name = new Label(game.name());
        name.getStyleClass().add("tile-name");
        name.setMaxWidth(WIDTH - 24);
        Tooltip.install(this, new Tooltip(game.name() + "\n" + game.dir() + "\n\nClick to select  ·  double-click to open"));

        size.getStyleClass().add("tile-size");
        badge.getStyleClass().addAll("badge", "badge-off");
        HBox meta = Layout.row("tile-meta", size, Layout.spacer(), badge);
        VBox info = Layout.column("tile-info", name, meta);

        VBox card = new VBox(coverPane, info);
        card.getStyleClass().add("game-tile-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // the hover shadow sits on its own layer behind the card, so the text never goes through the effect
        Region shadow = new Region();
        shadow.getStyleClass().add("game-tile-shadow");
        shadow.maxWidthProperty().bind(card.widthProperty());
        shadow.maxHeightProperty().bind(card.heightProperty());
        shadow.setMouseTransparent(true);
        getChildren().addAll(shadow, card);
        installLift(card);
        setSize(game.size());

        setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() == 1) {
                onToggle.run();
            } else if (e.getClickCount() == 2) {
                onToggle.run(); // undo the toggle from the first click
                onOpen.run();
            }
        });
    }

    public void setSelected(boolean value) {
        if (value == selected) return;
        selected = value;
        pseudoClassStateChanged(SELECTED, value);
        if (value) Ui.pop(check);
    }

    public void setCover(Image cover) {
        showWhenLoaded(cover, image);
    }

    public void setSize(long bytes) {
        size.setText(bytes >= 0 ? FileOps.formatSize(bytes) : "—");
    }

    public void showReport(PatchDetector.Report report) {
        switch (report.state()) {
            case ORIGINAL -> setBadge("ORIGINAL", "badge-info", report.dlls().size() + " original steam_api DLL(s)");
            case PATCHED -> setBadge("PATCHED", "badge-ok",
                    "Patched with " + report.emulator().orElse("an emulator") + "  ·  " + report.dlls().size() + " DLL(s)");
            case MIXED -> setBadge("MIXED", "badge-warn", "Some DLLs are patched, some are original");
            case NO_DLLS -> setBadge("NO DLLS", "badge-off", "No steam_api DLLs found. Nothing to patch.");
        }
    }

    public void showError(String reason) {
        setBadge("UNKNOWN", "badge-off", reason);
    }

    private void setBadge(String text, String style, String tooltip) {
        badge.setText(text);
        badge.getStyleClass().removeAll("badge-ok", "badge-warn", "badge-info", "badge-off");
        badge.getStyleClass().add(style);
        badge.setTooltip(new Tooltip(tooltip));
        badge.setOpacity(0);
        Ui.fadeIn(badge, 200);
    }

    private static void showWhenLoaded(Image cover, ImageView view) {
        Runnable show = () -> {
            if (cover.isError()) return;
            view.setImage(cover);
            view.setOpacity(0);
            Ui.fadeIn(view, 300);
        };
        if (cover.getProgress() >= 1) show.run();
        else cover.progressProperty().addListener((o, a, p) -> { if (p.doubleValue() >= 1) show.run(); });
    }

    private static void installLift(Region card) {
        TranslateTransition lift = new TranslateTransition(Duration.millis(140), card);
        card.setOnMouseEntered(e -> { lift.setToY(-4); lift.playFromStart(); });
        card.setOnMouseExited(e -> { lift.setToY(0); lift.playFromStart(); });
    }
}
