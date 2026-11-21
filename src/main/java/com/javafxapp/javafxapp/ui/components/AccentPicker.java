package com.javafxapp.javafxapp.ui.components;

import com.javafxapp.javafxapp.ui.shell.Theme;
import com.javafxapp.javafxapp.ui.shell.Ui;

import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A row of preset accent swatches plus a themed popover for picking any color. */
public final class AccentPicker extends HBox {

    public static final Color DEFAULT = Color.web("#7859f7");

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final String[][] PRESETS = {
            {"Purple", "#7859f7"}, {"Blue", "#3b82f6"}, {"Cyan", "#14b8c4"}, {"Green", "#22a35a"},
            {"Amber", "#e0a020"}, {"Orange", "#f06a2c"}, {"Red", "#e5484d"}, {"Pink", "#e04da0"},
    };

    private final List<Button> presetButtons = new ArrayList<>();
    private final Button custom = new Button();
    private final Region customFill = new Region();
    private final Consumer<Color> onPick;
    private Color value = DEFAULT;
    private Popup popup;

    public AccentPicker(Consumer<Color> onPick) {
        this.onPick = onPick;
        getStyleClass().add("accent-swatches");
        setAlignment(Pos.CENTER_LEFT);
        for (String[] preset : PRESETS) {
            Color color = Color.web(preset[1]);
            Button b = swatch(preset[0]);
            b.getGraphic().setStyle("-fx-background-color: " + preset[1] + ";");
            b.setUserData(color);
            b.setOnAction(e -> pick(color));
            presetButtons.add(b);
            getChildren().add(b);
        }
        custom.getStyleClass().addAll("accent-swatch", "accent-custom");
        customFill.getStyleClass().add("accent-swatch-fill");
        custom.setGraphic(customFill);
        custom.setAccessibleText("Custom color");
        custom.setTooltip(new Tooltip("Custom color…"));
        custom.setOnAction(e -> togglePopover());
        getChildren().add(custom);
        setValue(DEFAULT);
    }

    private Button swatch(String name) {
        Button b = new Button();
        b.getStyleClass().add("accent-swatch");
        Region fill = new Region();
        fill.getStyleClass().add("accent-swatch-fill");
        b.setGraphic(fill);
        b.setAccessibleText(name);
        b.setTooltip(new Tooltip(name));
        return b;
    }

    public void setValue(Color color) {
        value = color;
        boolean matched = false;
        for (Button b : presetButtons) {
            boolean on = same((Color) b.getUserData(), color);
            matched |= on;
            b.pseudoClassStateChanged(SELECTED, on);
        }
        custom.pseudoClassStateChanged(SELECTED, !matched);
        customFill.setStyle(matched ? "" : "-fx-background-color: " + hex(color) + ";");
    }

    private void pick(Color color) {
        setValue(color);
        onPick.accept(color);
    }

    private void togglePopover() {
        if (popup != null && popup.isShowing()) {
            popup.hide();
            return;
        }
        popup = new Popup();
        popup.setAutoHide(true);
        popup.getContent().add(buildPopover());
        Bounds b = custom.localToScreen(custom.getBoundsInLocal());
        popup.show(custom, b.getMinX(), b.getMaxY() + 6);
        popup.getContent().get(0).lookup(".hue-slider").requestFocus();
    }

    private VBox buildPopover() {
        Region preview = new Region();
        preview.getStyleClass().add("accent-preview");
        Slider hue = slider(0, 360, value.getHue());
        Slider sat = slider(0, 100, value.getSaturation() * 100);
        Slider bri = slider(20, 100, Math.max(20, value.getBrightness() * 100));
        hue.getStyleClass().add("hue-slider");
        TextField hex = new TextField(hex(value));
        hex.getStyleClass().add("accent-hex");
        hex.setPrefColumnCount(7);

        boolean[] syncing = {false};
        Runnable fromSliders = () -> {
            if (syncing[0]) return;
            Color c = Color.hsb(hue.getValue(), sat.getValue() / 100, bri.getValue() / 100);
            preview.setStyle("-fx-background-color: " + hex(c) + ";");
            tracks(sat, bri, c);
            syncing[0] = true;
            hex.setText(hex(c));
            syncing[0] = false;
            pick(c);
        };
        hue.valueProperty().addListener((o, a, n) -> fromSliders.run());
        sat.valueProperty().addListener((o, a, n) -> fromSliders.run());
        bri.valueProperty().addListener((o, a, n) -> fromSliders.run());
        hex.textProperty().addListener((o, a, n) -> {
            if (syncing[0] || !n.matches("#?[0-9a-fA-F]{6}")) return;
            Color c = Color.web(n.startsWith("#") ? n : "#" + n);
            syncing[0] = true;
            hue.setValue(c.getHue());
            sat.setValue(c.getSaturation() * 100);
            bri.setValue(Math.max(20, c.getBrightness() * 100));
            tracks(sat, bri, c);
            syncing[0] = false;
            preview.setStyle("-fx-background-color: " + hex(c) + ";");
            pick(c);
        });
        preview.setStyle("-fx-background-color: " + hex(value) + ";");
        tracks(sat, bri, value);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("accent-grid");
        grid.addRow(0, label("Hue"), hue);
        grid.addRow(1, label("Saturation"), sat);
        grid.addRow(2, label("Brightness"), bri);

        HBox top = new HBox(10, preview, hex);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, top, grid);
        box.getStyleClass().addAll("app-root", "accent-popover");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.getStylesheets().setAll(Ui.stylesheet("styles.css"));
        Theme.apply(box);
        return box;
    }

    private static void tracks(Slider sat, Slider bri, Color c) {
        sat.setStyle("-track-start: " + hex(Color.hsb(c.getHue(), 0, c.getBrightness())) + "; -track-end: " + hex(Color.hsb(c.getHue(), 1, c.getBrightness())) + ";");
        bri.setStyle("-track-start: #000000; -track-end: " + hex(Color.hsb(c.getHue(), c.getSaturation(), 1)) + ";");
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("path-label");
        return l;
    }

    private static Slider slider(double min, double max, double v) {
        Slider s = new Slider(min, max, v);
        s.getStyleClass().add("accent-slider");
        s.setMinWidth(180);
        s.setPrefWidth(180);
        s.setMaxWidth(180);
        return s;
    }

    private static boolean same(Color a, Color b) {
        return hex(a).equalsIgnoreCase(hex(b));
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }
}
