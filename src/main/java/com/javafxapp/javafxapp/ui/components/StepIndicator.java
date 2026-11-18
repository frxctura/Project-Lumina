package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Layout;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.animation.Animation;
import javafx.animation.ScaleTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class StepIndicator extends HBox {

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass DONE = PseudoClass.getPseudoClass("done");
    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");

    private final List<String> names = new ArrayList<>();
    private final List<Label> dots = new ArrayList<>();
    private final List<VBox> steps = new ArrayList<>();
    private final List<Region> lines = new ArrayList<>();
    private final ScaleTransition pulse = new ScaleTransition(Duration.millis(650));
    private int active = -1;

    public StepIndicator() {
        getStyleClass().add("step-indicator");
        setAlignment(Pos.CENTER_LEFT);
        pulse.setFromX(1);
        pulse.setFromY(1);
        pulse.setToX(1.18);
        pulse.setToY(1.18);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
    }

    public void setSteps(List<String> stepNames) {
        pulse.stop();
        getChildren().clear();
        names.clear();
        dots.clear();
        steps.clear();
        lines.clear();
        active = -1;

        for (int i = 0; i < stepNames.size(); i++) {
            if (i > 0) {
                Region line = new Region();
                line.getStyleClass().add("step-line");
                HBox.setHgrow(line, Priority.ALWAYS);
                lines.add(line);
                getChildren().add(line);
            }
            Label dot = new Label(String.valueOf(i + 1));
            dot.getStyleClass().add("step-dot");
            Label name = new Label(stepNames.get(i));
            name.getStyleClass().add("step-name");
            VBox step = Layout.column("step", dot, name);
            names.add(stepNames.get(i));
            dots.add(dot);
            steps.add(step);
            getChildren().add(step);
        }
    }

    public void setActive(String stepName) {
        int index = names.indexOf(stepName);
        if (index < 0 || index == active) return;
        for (int i = 0; i < steps.size(); i++) setState(i, i < index ? DONE : i == index ? ACTIVE : null);
        active = index;
        pulse.stop();
        dots.forEach(d -> { d.setScaleX(1); d.setScaleY(1); });
        if (com.javafxapp.javafxapp.ui.shell.Motion.reduced()) return;
        pulse.setNode(dots.get(index));
        pulse.playFromStart();
    }

    public void complete() {
        pulse.stop();
        for (int i = 0; i < steps.size(); i++) setState(i, DONE);
        dots.forEach(Ui::pop);
    }

    public void fail() {
        pulse.stop();
        if (active >= 0) setState(active, FAILED);
    }

    private void setState(int i, PseudoClass state) {
        VBox step = steps.get(i);
        Label dot = dots.get(i);
        for (PseudoClass pc : List.of(ACTIVE, DONE, FAILED)) step.pseudoClassStateChanged(pc, pc == state);
        dot.setScaleX(1);
        dot.setScaleY(1);
        // done and failed are drawn icons, so they look the same on every machine; the number stays text
        boolean mark = state == DONE || state == FAILED;
        dot.setText(mark ? "" : String.valueOf(i + 1));
        dot.setGraphic(mark ? (state == DONE ? Icon.CHECKMARK : Icon.DISMISS).create(14) : null);
        String name = names.get(i);
        dot.setAccessibleText(name + ": " + (state == DONE ? "done" : state == FAILED ? "failed" : state == ACTIVE ? "in progress" : "waiting"));
        if (i > 0) lines.get(i - 1).pseudoClassStateChanged(DONE, state == DONE || state == ACTIVE || state == FAILED);
    }
}
