package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.library.SteamLibrary;
import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.animation.TranslateTransition;
import com.javafxapp.javafxapp.ui.shell.Motion;

import javafx.css.PseudoClass;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
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

import java.util.Optional;

public final class GameTile extends StackPane {

    static final double WIDTH = 176;
    private static final double COVER_HEIGHT = Math.round(WIDTH * 215 / 460);
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double NAME_HEIGHT = 42; // two lines of 14px Nunito plus a little, or JavaFX cuts it to one
    private final Runnable onToggle;

    private final Label size = new Label();
    private final Label badge = new Label("CHECKING");
    private final StackPane check = new StackPane(Icon.CHECKMARK.create(14));
    private final String gameName;
    private PatchDetector.State state;
    private String status = "checking";
    private final ImageView image = new ImageView();
    private boolean selected;

    public GameTile(SteamLibrary.Game game, Image cover, Runnable onToggle, Runnable onOpen) {
        getStyleClass().add("game-tile");
        gameName = game.name();
        // keyboard: Tab or the arrow keys (see LibraryController) to move, Space to tick, Enter to open
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.CHECK_BOX);
        setAccessibleHelp("Space adds it to the batch queue or takes it out, Enter opens it");
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                onToggle.run();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                onOpen.run();
                e.consume();
            }
        });
        this.onToggle = onToggle;
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
        // two lines before it gets cut, and always two lines tall so the tiles line up
        name.setWrapText(true);
        name.setAlignment(Pos.TOP_LEFT);
        name.setMinHeight(NAME_HEIGHT);
        name.setPrefHeight(NAME_HEIGHT);
        name.setMaxHeight(NAME_HEIGHT);
        Tooltip.install(this, new Tooltip(game.name() + "\n" + game.dir() + "\n\nClick or Space adds it to the batch  ·  double-click or Enter opens it"));

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
        notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
        if (value) Ui.pop(check);
    }

    public void setCover(Image cover) {
        showWhenLoaded(cover, image);
    }

    public void setSize(long bytes) {
        size.setText(bytes >= 0 ? FileOps.formatSize(bytes) : "—");
    }

    /** The patch state once it is known, for the Library filter. Empty while checking or if the folder can't be read. */
    public Optional<PatchDetector.State> state() {
        return Optional.ofNullable(state);
    }

    public void showReport(PatchDetector.Report report) {
        state = report.state();
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
        status = text.toLowerCase();
        notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
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
        card.setOnMouseEntered(e -> { if (Motion.reduced()) return; lift.setToY(-4); lift.playFromStart(); });
        card.setOnMouseExited(e -> { lift.setToY(0); lift.playFromStart(); });
    }

    // what a screen reader says: "Cyberpunk 2077, patched, in the batch queue", and Space or its "click" ticks it
    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case TEXT -> gameName + ", " + status + (selected ? ", in the batch queue" : "");
            case SELECTED -> selected;
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) onToggle.run();
        else super.executeAccessibleAction(action, parameters);
    }
}
