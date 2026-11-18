package com.javafxapp.javafxapp.ui.shell;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

// our own title bar and window handling, for an undecorated stage
public final class WindowChrome {

    private static final double EDGE = 6;

    private final Stage stage;
    private final BorderPane root;
    private final BooleanProperty maximized = new SimpleBooleanProperty();
    private Rectangle2D restore;
    private double dragX, dragY;

    private int resizeEdges;
    private double startX, startY, startW, startH, startMouseX, startMouseY;

    private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;

    public WindowChrome(Stage stage, BorderPane root) {
        this.stage = stage;
        this.root = root;
        root.setTop(buildTitleBar());
        root.getStyleClass().add("framed");
    }

    public boolean isMaximized() {
        return maximized.get();
    }

    // where the window goes back to, also while it is maximized
    public Rectangle2D normalBounds() {
        return maximized.get() && restore != null ? restore : currentBounds();
    }

    public void setMaximized(boolean value) {
        if (value == maximized.get()) return;
        if (value) {
            restore = currentBounds();
            Rectangle2D area = screenFor(restore).getVisualBounds();
            setBounds(area);
        } else if (restore != null) {
            setBounds(restore);
        }
        maximized.set(value);
        root.getStyleClass().remove("framed");
        if (!value) root.getStyleClass().add("framed");
    }

    // call once the scene exists
    public void attach(Scene scene) {
        installResizing(scene);
    }

    private Node buildTitleBar() {
        ImageView icon = new ImageView();
        Theme.bindImage(icon, Ui.image("icon.png"), Ui.image("icon-dark.png"));
        icon.setFitWidth(16);
        icon.setFitHeight(16);
        Label title = new Label("Lumina");
        title.getStyleClass().add("title-bar-text");
        HBox drag = new HBox(8, icon, title);
        drag.setAlignment(Pos.CENTER_LEFT);
        drag.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(drag, Priority.ALWAYS);

        drag.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        drag.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (maximized.get()) {
                // pull the window off the edge, keeping it under the cursor
                double ratio = (e.getScreenX() - stage.getX()) / stage.getWidth();
                setMaximized(false);
                dragX = stage.getWidth() * ratio;
                dragY = e.getScreenY() - stage.getY();
            }
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        drag.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) setMaximized(!maximized.get());
        });

        Button minimize = windowButton("minimize", () -> stage.setIconified(true));
        Button maximize = windowButton("maximize", () -> setMaximized(!maximized.get()));
        Button close = windowButton("close", () -> stage.fireEvent(
                new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        close.getStyleClass().add("close");
        maximized.addListener((o, was, is) -> {
            Node icon2 = maximize.getGraphic();
            icon2.getStyleClass().removeAll("maximize", "restore");
            icon2.getStyleClass().add(is ? "restore" : "maximize");
        });

        HBox bar = new HBox(drag, minimize, maximize, close);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Button windowButton(String kind, Runnable action) {
        Region icon = new Region();
        icon.getStyleClass().addAll("window-icon", kind);
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("window-button");
        // like Windows' own caption buttons: not in the Tab order, but named for screen readers
        button.setFocusTraversable(false);
        button.setAccessibleText(switch (kind) { case "minimize" -> "Minimize"; case "maximize" -> "Maximize"; default -> "Close"; });
        button.setOnAction(e -> action.run());
        return button;
    }

    private void installResizing(Scene scene) {
        if (scene == null) return;
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            scene.setCursor(maximized.get() ? Cursor.DEFAULT : cursorFor(edgesAt(scene, e)));
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (maximized.get() || e.getButton() != MouseButton.PRIMARY) return;
            resizeEdges = edgesAt(scene, e);
            if (resizeEdges == 0) return;
            startX = stage.getX();
            startY = stage.getY();
            startW = stage.getWidth();
            startH = stage.getHeight();
            startMouseX = e.getScreenX();
            startMouseY = e.getScreenY();
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (resizeEdges == 0) return;
            double dx = e.getScreenX() - startMouseX, dy = e.getScreenY() - startMouseY;
            double minW = stage.getMinWidth(), minH = stage.getMinHeight();
            if ((resizeEdges & RIGHT) != 0) stage.setWidth(Math.max(minW, startW + dx));
            if ((resizeEdges & BOTTOM) != 0) stage.setHeight(Math.max(minH, startH + dy));
            if ((resizeEdges & LEFT) != 0) {
                double w = Math.max(minW, startW - dx);
                stage.setX(startX + startW - w);
                stage.setWidth(w);
            }
            if ((resizeEdges & TOP) != 0) {
                double h = Math.max(minH, startH - dy);
                stage.setY(startY + startH - h);
                stage.setHeight(h);
            }
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> resizeEdges = 0);
    }

    private static int edgesAt(Scene scene, MouseEvent e) {
        int edges = 0;
        if (e.getSceneX() < EDGE) edges |= LEFT;
        if (e.getSceneX() > scene.getWidth() - EDGE) edges |= RIGHT;
        if (e.getSceneY() < EDGE) edges |= TOP;
        if (e.getSceneY() > scene.getHeight() - EDGE) edges |= BOTTOM;
        return edges;
    }

    private static Cursor cursorFor(int edges) {
        return switch (edges) {
            case LEFT, RIGHT -> Cursor.H_RESIZE;
            case TOP, BOTTOM -> Cursor.V_RESIZE;
            case LEFT | TOP, RIGHT | BOTTOM -> Cursor.NW_RESIZE;
            case RIGHT | TOP, LEFT | BOTTOM -> Cursor.NE_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    private Rectangle2D currentBounds() {
        boolean known = !Double.isNaN(stage.getWidth()) && !Double.isNaN(stage.getX());
        return known ? new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                : new Rectangle2D(100, 100, 1060, 520);
    }

    private static Screen screenFor(Rectangle2D bounds) {
        return Screen.getScreensForRectangle(bounds).stream().findFirst().orElse(Screen.getPrimary());
    }

    private void setBounds(Rectangle2D r) {
        stage.setX(r.getMinX());
        stage.setY(r.getMinY());
        stage.setWidth(r.getWidth());
        stage.setHeight(r.getHeight());
    }
}
