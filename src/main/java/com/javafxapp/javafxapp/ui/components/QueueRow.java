package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.patching.BatchJob.State;
import com.javafxapp.javafxapp.patching.CrackJob;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

public final class QueueRow extends HBox {

    public final Path dir;
    private String appId;
    private String name;
    private State state = State.QUEUED;

    private final Label icon = new Label();
    private final Label nameLabel = new Label();
    private final Label detail = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Button remove = new Button("✕");

    public QueueRow(Path dir, String name, String appId, Runnable onRemove) {
        this.dir = dir;
        this.appId = appId;
        getStyleClass().addAll("card", "compact", "queue-row");
        setAlignment(Pos.CENTER_LEFT);

        icon.getStyleClass().add("queue-icon");
        nameLabel.getStyleClass().add("queue-name");
        detail.getStyleClass().add("queue-detail");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("queue-progress");
        remove.getStyleClass().add("queue-remove");
        remove.setTooltip(new Tooltip("Remove from queue"));
        remove.setOnAction(e -> onRemove.run());

        VBox text = Layout.column("queue-text", nameLabel, detail, progress);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        getChildren().addAll(icon, text, remove);
        setName(name);
        setState(State.QUEUED, "Queued");
    }

    public String appId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        nameLabel.setText(name);
        Tooltip.install(nameLabel, new Tooltip(name + "\n" + dir));
    }

    public State state() {
        return state;
    }

    public boolean isPending() {
        return state == State.QUEUED || isRetry();
    }

    public boolean isRetry() {
        return state == State.FAILED || state == State.CANCELLED;
    }

    public void setWaiting(String text) {
        detail.textProperty().unbind();
        detail.setText(text);
        detail.setTooltip(new Tooltip(text));
    }

    public void setLocked(boolean locked) {
        remove.setVisible(!locked);
    }

    public void run(CrackJob job) {
        setState(State.RUNNING, "");
        detail.textProperty().bind(job.messageProperty());
        progress.progressProperty().bind(job.progressProperty());
    }

    public void setState(State state, String text) {
        this.state = state;
        detail.textProperty().unbind();
        progress.progressProperty().unbind();
        for (State s : State.values()) pseudoClassStateChanged(PseudoClass.getPseudoClass(s.name().toLowerCase()), s == state);
        icon.setText(switch (state) {
            case QUEUED -> "•";
            case RUNNING -> "▶";
            case DONE -> "✔";
            case FAILED -> "✖";
            case SKIPPED, CANCELLED -> "–";
        });
        detail.setText(text);
        detail.setTooltip(new Tooltip(text));
        boolean running = state == State.RUNNING;
        progress.setVisible(running);
        progress.setManaged(running);
        if (state != State.QUEUED) Ui.pop(icon);
    }
}
