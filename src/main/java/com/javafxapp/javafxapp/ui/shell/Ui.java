package com.javafxapp.javafxapp.ui.shell;

import com.javafxapp.javafxapp.common.Log;

import javafx.animation.*;
import javafx.beans.value.WritableValue;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.control.*;
import javafx.css.PseudoClass;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.LongFunction;

public final class Ui {

    public static final String CONTACT_URL = "https://github.com/frxctura";
    private static final String ANIMATION_KEY = "lumina.animation";
    private static final DateTimeFormatter FULL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Ui() {}

    private static void play(Node node, Animation animation) {
        if (node.getProperties().get(ANIMATION_KEY) instanceof Animation running) running.stop();
        node.getProperties().put(ANIMATION_KEY, animation);
        animation.play();
    }

    public static void fadeIn(Node node, int ms) {
        node.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setToValue(1);
        play(node, fade);
    }

    public static void reveal(Node node, int ms) {
        reveal(node, ms, 0);
    }

    public static void reveal(Node node, int ms, int delayMs) {
        slideIn(node, 0, 10, ms, delayMs);
    }

    // javafx moves a scroll pane about 40px per wheel notch, which is crawling on a tall page
    public static void fasterScrolling(Scene scene, double factor) {
        scene.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.getDeltaY() == 0) return;
            ScrollPane pane = null;
            for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
                // lists, tables and text areas scroll themselves
                if (n instanceof TableView || n instanceof ListView || n instanceof TextInputControl) return;
                if (n instanceof ScrollPane p) {
                    pane = p;
                    break;
                }
            }
            if (pane == null || pane.getContent() == null) return;
            double range = pane.getContent().getLayoutBounds().getHeight() - pane.getViewportBounds().getHeight();
            if (range <= 0) return;
            double next = pane.getVvalue() - e.getDeltaY() * factor / range;
            pane.setVvalue(Math.max(pane.getVmin(), Math.min(pane.getVmax(), next)));
            e.consume();
        });
    }

    public static void slideIn(Node node, double dx, double dy, int ms, int delayMs) {
        node.setVisible(true);
        node.setOpacity(0);
        if (Motion.reduced()) {
            // no movement, only the fade, and no stagger
            node.setTranslateX(0);
            node.setTranslateY(0);
            FadeTransition fade = new FadeTransition(Duration.millis(Math.min(ms, 150)), node);
            fade.setToValue(1);
            play(node, fade);
            return;
        }
        node.setTranslateX(dx);
        node.setTranslateY(dy);
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(ms), node);
        slide.setToX(0);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition all = new ParallelTransition(fade, slide);
        all.setDelay(Duration.millis(delayMs));
        play(node, all);
    }

    public static void hide(Node node, int ms) {
        FadeTransition fade = new FadeTransition(Duration.millis(ms), node);
        fade.setToValue(0);
        fade.setOnFinished(e -> node.setVisible(false));
        play(node, fade);
    }

    public static void swap(Node from, Node to) {
        hide(from, 150);
        reveal(to, 260);
    }

    public static void pop(Node node) {
        if (Motion.reduced()) return;
        ScaleTransition up = new ScaleTransition(Duration.millis(120), node);
        up.setToX(1.08);
        up.setToY(1.08);
        ScaleTransition down = new ScaleTransition(Duration.millis(180), node);
        down.setToX(1);
        down.setToY(1);
        new SequentialTransition(up, down).play();
    }

    public static void animateProgress(ProgressBar bar, double value) {
        Timeline current = (Timeline) bar.getProperties().get(ANIMATION_KEY);
        if (current != null) current.stop();
        if (value < 0 || bar.getProgress() < 0) {
            bar.setProgress(value);
            return;
        }
        Timeline t = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(bar.progressProperty(), value, Interpolator.EASE_OUT)));
        bar.getProperties().put(ANIMATION_KEY, t);
        t.play();
    }

    public static void countUp(Label label, long target, LongFunction<String> format) {
        if (Motion.reduced()) {
            label.setText(format.apply(target));
            return;
        }
        var value = new WritableValue<Number>() {
            long v;
            public Number getValue() { return v; }
            public void setValue(Number n) { v = n.longValue(); label.setText(format.apply(v)); }
        };
        value.setValue(0);
        Timeline t = new Timeline(new KeyFrame(Duration.millis(600), new KeyValue(value, target, Interpolator.EASE_OUT)));
        play(label, t);
    }

    // darkens the window behind a dialog, like the smoke layer behind Windows' own dialogs.
    // returns how to undo it
    static Runnable dim(Window owner) {
        if (owner == null || owner.getScene() == null || !(owner.getScene().getRoot() instanceof StackPane root)) return () -> {};
        Region scrim = new Region();
        scrim.setStyle("-fx-background-color: " + (Theme.isLight() ? "rgba(0,0,0,0.25)" : "rgba(0,0,0,0.5)") + ";");
        scrim.setOpacity(0);
        root.getChildren().add(scrim);
        fadeIn(scrim, 150);
        return () -> {
            FadeTransition out = new FadeTransition(Duration.millis(150), scrim);
            out.setToValue(0);
            out.setOnFinished(e -> root.getChildren().remove(scrim));
            out.play();
        };
    }

    private static final PseudoClass KEYBOARD = PseudoClass.getPseudoClass("keyboard");
    private static boolean keyboardInUse;

    /**
     * Focus rings show only while someone is using the keyboard: Tab or an arrow key puts {@code :keyboard} on
     * {@code root} (the node with the app-root style), a mouse click takes it off. Every window does this, and a new
     * window starts in whatever mode the last one was in, so a dialog opened from the keyboard shows its ring at once.
     */
    public static void trackKeyboardFocus(Scene scene, Node root) {
        root.pseudoClassStateChanged(KEYBOARD, keyboardInUse);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN -> {
                    keyboardInUse = true;
                    root.pseudoClassStateChanged(KEYBOARD, true);
                }
                default -> { }
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            keyboardInUse = false;
            root.pseudoClassStateChanged(KEYBOARD, false);
        });
    }

    /** A button that only shows an icon. {@code name} is what a screen reader says, and the tooltip. */
    public static Button iconButton(com.javafxapp.javafxapp.ui.components.Icon icon, double size, String name, String... styles) {
        Button b = new Button();
        b.setGraphic(icon.create(size));
        b.getStyleClass().addAll(styles);
        b.setAccessibleText(name);
        b.setTooltip(new Tooltip(name));
        b.setMnemonicParsing(false);
        return b;
    }

    public static Prompt prompt(Window owner) {
        return new Prompt(owner);
    }

    static Alert styled(Alert alert, Window owner) {
        alert.initOwner(owner);
        alert.initStyle(StageStyle.TRANSPARENT);
        alert.setHeaderText(null);
        alert.setGraphic(null);
        DialogPane pane = alert.getDialogPane();
        pane.getScene().setFill(null);
        for (String css : new String[]{"styles.css", "alertstyles.css"}) pane.getStylesheets().add(stylesheet(css));
        pane.getStyleClass().addAll("app-root", "lumina-alert");
        trackKeyboardFocus(pane.getScene(), pane);
        Theme.apply(pane);
        return alert;
    }

    // the content needs a real width or its wrapped labels get cut off
    static void setDialogWidth(DialogPane pane, double width) {
        pane.setPrefWidth(width);
        if (pane.getContent() instanceof Region content) content.setPrefWidth(width);
    }

    public static Image image(String name) {
        return new Image(Objects.requireNonNull(Ui.class.getResource("/com/javafxapp/javafxapp/images/" + name)).toExternalForm());
    }

    public static String stylesheet(String name) {
        return Objects.requireNonNull(Ui.class.getResource("/com/javafxapp/javafxapp/styles/" + name)).toExternalForm();
    }

    public static String relativeTime(Instant time) {
        long seconds = java.time.Duration.between(time, Instant.now()).getSeconds();
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + " min ago";
        if (seconds < 86_400) return (seconds / 3600) + " h ago";
        if (seconds < 7 * 86_400) return (seconds / 86_400) + " d ago";
        return FULL_TIME.format(time).substring(0, 10);
    }

    public static String fullTime(Instant time) {
        return FULL_TIME.format(time);
    }

    public static void openUrl(String url) {
        background("open-url", () -> Desktop.getDesktop().browse(URI.create(url)));
    }

    public static void openFolder(Path dir) {
        background("open-folder", () -> Desktop.getDesktop().open(dir.toFile()));
    }

    public static void showInExplorer(Path file) {
        if (Files.isDirectory(file)) openFolder(file);
        else background("reveal", () -> new ProcessBuilder("explorer.exe", "/select,", file.toString()).start());
    }

    public static void start(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    private interface IoAction { void run() throws Exception; }

    private static void background(String name, IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                Log.error(name + " failed", e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
}
