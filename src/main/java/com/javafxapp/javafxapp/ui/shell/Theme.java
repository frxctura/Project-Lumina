package com.javafxapp.javafxapp.ui.shell;

import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.config.AppSettings;

import javafx.animation.FadeTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Theme {

    private static final String LIGHT_CLASS = "light";
    private static final String PERSONALIZE_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final Pattern LIGHT_VALUE = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");

    private static final ObjectProperty<AppSettings.Theme> choice = new SimpleObjectProperty<>(AppSettings.Theme.DARK);
    private static final ObjectProperty<AppSettings.Theme> current = new SimpleObjectProperty<>(AppSettings.Theme.DARK);

    private Theme() {}

    public static void load() {
        AppSettings.Theme saved = AppSettings.getTheme();
        choice.set(saved);
        current.set(resolve(saved));
    }

    public static ReadOnlyObjectProperty<AppSettings.Theme> choiceProperty() {
        return choice;
    }

    public static ReadOnlyObjectProperty<AppSettings.Theme> currentProperty() {
        return current;
    }

    public static AppSettings.Theme current() {
        return current.get();
    }

    public static boolean isLight() {
        return current.get() == AppSettings.Theme.LIGHT;
    }

    // white artwork on dark themes, dark artwork on light ones. Only swaps images it put there itself
    public static void bindImage(ImageView view, Image onDark, Image onLight) {
        view.setImage(isLight() ? onLight : onDark);
        currentProperty().addListener((o, was, now) -> {
            Image shown = view.getImage();
            if (shown == null || shown == onDark || shown == onLight) view.setImage(isLight() ? onLight : onDark);
        });
    }

    public static void apply(Node root) {
        root.getStyleClass().remove(LIGHT_CLASS);
        if (isLight()) root.getStyleClass().add(LIGHT_CLASS);
    }

    public static void apply(Scene scene) {
        scene.getRoot().lookupAll(".app-root").forEach(Theme::apply);
    }

    public static void choose(AppSettings.Theme theme, Scene scene) {
        AppSettings.setTheme(theme);
        choice.set(theme);
        if (theme != AppSettings.Theme.SYSTEM) {
            switchTo(theme, scene);
            return;
        }
        Task<AppSettings.Theme> query = new Task<>() {
            @Override
            protected AppSettings.Theme call() {
                return resolve(AppSettings.Theme.SYSTEM);
            }
        };
        query.setOnSucceeded(e -> {
            if (choice.get() == AppSettings.Theme.SYSTEM) switchTo(query.getValue(), scene);
        });
        Ui.start(query, "theme-query");
    }

    private static void switchTo(AppSettings.Theme theme, Scene scene) {
        if (theme == current.get()) return;
        current.set(theme);
        if (!(scene.getRoot() instanceof Pane host) || scene.getWidth() <= 0) {
            apply(scene);
            return;
        }
        ImageView cover = new ImageView(scene.snapshot(null));
        cover.setFitWidth(scene.getWidth());
        cover.setFitHeight(scene.getHeight());
        cover.setManaged(false);
        cover.setMouseTransparent(true);
        host.getChildren().add(cover);
        apply(scene);
        FadeTransition fade = new FadeTransition(Duration.millis(320), cover);
        fade.setToValue(0);
        fade.setOnFinished(e -> host.getChildren().remove(cover));
        fade.play();
    }

    private static AppSettings.Theme resolve(AppSettings.Theme theme) {
        if (theme != AppSettings.Theme.SYSTEM) return theme;
        return systemUsesLightTheme() ? AppSettings.Theme.LIGHT : AppSettings.Theme.DARK;
    }

    private static boolean systemUsesLightTheme() {
        try {
            Process reg = new ProcessBuilder("reg", "query", PERSONALIZE_KEY, "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true).start();
            String out = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            reg.waitFor();
            Matcher m = LIGHT_VALUE.matcher(out);
            return m.find() && Long.parseLong(m.group(1), 16) != 0;
        } catch (IOException e) {
            Log.error("Could not read the Windows app theme", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
