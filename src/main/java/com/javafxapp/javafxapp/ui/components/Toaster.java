package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.animation.*;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class Toaster {

    public enum Kind { SUCCESS, ERROR, INFO }

    private static final int MAX_TOASTS = 4;
    private static final double WIDTH = 380;
    private final VBox layer;

    public Toaster(VBox layer) {
        this.layer = layer;
    }

    public void show(Kind kind, String text, String actionText, Runnable action) {
        StackPane icon = new StackPane((switch (kind) { case SUCCESS -> Icon.CHECKMARK; case ERROR -> Icon.ERROR_CIRCLE; case INFO -> Icon.INFO; }).create(16));
        icon.getStyleClass().add("toast-icon");
        Label title = new Label(switch (kind) { case SUCCESS -> "Done"; case ERROR -> "That didn't work"; case INFO -> "Heads up"; });
        title.getStyleClass().add("toast-title");
        Label message = new Label(text);
        message.setWrapText(true);
        message.getStyleClass().add("toast-text");
        VBox body = new VBox(2, title, message);
        body.setMinWidth(0);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox content = Layout.row("toast-content", icon, body);
        VBox inner = new VBox(content);
        // the card and its shadow are a layer of their own, so the text is never drawn through an effect
        Region backdrop = new Region();
        backdrop.getStyleClass().add("toast-backdrop");
        StackPane toast = new StackPane(backdrop, inner);
        toast.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase());
        // same width every time so a stack of toasts lines up
        toast.setMinWidth(WIDTH);
        toast.setPrefWidth(WIDTH);
        toast.setMaxWidth(WIDTH);

        if (actionText != null) {
            Hyperlink link = new Hyperlink(actionText);
            link.getStyleClass().add("toast-action");
            link.setOnAction(e -> {
                action.run();
                dismiss(toast);
            });
            content.getChildren().add(link);
        } else if (kind == Kind.ERROR) {
            // so the message can be pasted into a bug report
            Hyperlink copy = new Hyperlink("Copy");
            copy.setAccessibleText("Copy this message");
            copy.getStyleClass().add("toast-action");
            copy.setOnAction(e -> {
                ClipboardContent c = new ClipboardContent();
                c.putString(text);
                Clipboard.getSystemClipboard().setContent(c);
                copy.setText("Copied");
            });
            content.getChildren().add(copy);
        }
        Button close = Ui.iconButton(Icon.DISMISS, 14, "Dismiss: " + title.getText() + ". " + text, "toast-close");
        close.setOnAction(e -> dismiss(toast));
        content.getChildren().add(close);

        content.getChildren().forEach(n -> {
            if (n != body && n instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
        });

        // thin bar that shrinks while the toast waits to go away
        Region timer = new Region();
        timer.getStyleClass().add("toast-timer");
        timer.setMinHeight(3);
        timer.setPrefHeight(3);
        timer.setMaxHeight(3);
        timer.setMaxWidth(Region.USE_PREF_SIZE);
        DoubleProperty left = new SimpleDoubleProperty(1);
        timer.prefWidthProperty().bind(inner.widthProperty().subtract(24).multiply(left));
        VBox.setMargin(timer, new Insets(0, 12, 8, 12));
        inner.getChildren().add(timer);
        inner.setAlignment(Pos.TOP_LEFT);

        if (layer.getChildren().size() >= MAX_TOASTS) layer.getChildren().remove(0);
        layer.getChildren().add(toast);
        Ui.slideIn(toast, 40, 0, 260, 0);
        // JavaFX has no live regions, so a screen reader can't be told a toast appeared. Its text is on the toast and
        // on its buttons, so it's read when the user moves there.
        toast.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        toast.setAccessibleText(title.getText() + ". " + text);

        Timeline life = lifetime(left, readingTime(text, kind, actionText != null));
        life.setOnFinished(e -> dismiss(toast));
        toast.setOnMouseEntered(e -> life.pause());
        toast.setOnMouseExited(e -> life.play());
        life.play();
    }

    // about half a second per word, at least 5s. errors and toasts with a button stay longer
    private static double readingTime(String text, Kind kind, boolean hasAction) {
        int words = text.trim().split("\\s+").length;
        double seconds = Math.min(14, Math.max(5, words * 0.5));
        if (kind == Kind.ERROR) seconds += 3;
        if (hasAction) seconds = Math.max(seconds, 10);
        return seconds;
    }

    private static Timeline lifetime(DoubleProperty left, double seconds) {
        return new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(left, 1)),
                new KeyFrame(Duration.seconds(seconds), new KeyValue(left, 0, Interpolator.LINEAR)));
    }

    private void dismiss(Node toast) {
        if (!layer.getChildren().contains(toast) || toast.getProperties().containsKey("closing")) return;
        toast.getProperties().put("closing", true);
        FadeTransition fade = new FadeTransition(Duration.millis(200), toast);
        fade.setToValue(0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), toast);
        slide.setToX(com.javafxapp.javafxapp.ui.shell.Motion.reduced() ? 0 : 40);
        ParallelTransition out = new ParallelTransition(fade, slide);
        out.setOnFinished(e -> layer.getChildren().remove(toast));
        out.play();
    }
}
