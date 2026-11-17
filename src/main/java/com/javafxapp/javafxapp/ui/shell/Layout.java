package com.javafxapp.javafxapp.ui.shell;

import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class Layout {

    private Layout() {}

    public static VBox column(String styleClasses, Node... children) {
        VBox box = new VBox(children);
        box.getStyleClass().addAll(styleClasses.split("\\s+"));
        return box;
    }

    public static HBox row(String styleClasses, Node... children) {
        HBox box = new HBox(children);
        box.getStyleClass().addAll(styleClasses.split("\\s+"));
        return box;
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
