package com.javafxapp.javafxapp;

import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.EmulatorInstaller;
import com.javafxapp.javafxapp.ui.pages.LibraryController;
import com.javafxapp.javafxapp.ui.pages.MainController;
import com.javafxapp.javafxapp.ui.shell.AppShell.View;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.ShellController;
import com.javafxapp.javafxapp.ui.shell.Theme;
import com.javafxapp.javafxapp.ui.shell.Ui;
import com.javafxapp.javafxapp.ui.shell.WindowChrome;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class LuminaApp extends Application {

    private static final double MIN_WIDTH = 1000, MIN_HEIGHT = 480;
    private static final String[] FONTS = {"Nunito-Black.ttf", "Nunito-Light.ttf", "Nunito-Medium.ttf", "Nunito-SemiBold.ttf"};

    @Override
    public void init() {
        EmulatorInstaller.migrateLegacyInstall();
        Theme.load();
    }

    @Override
    public void start(Stage stage) throws IOException {
        loadFonts();

        FXMLLoader shellLoader = loader("Shell.fxml");
        Parent shellRoot = shellLoader.load();
        ShellController shell = shellLoader.getController();

        MainController main = addPage(shell, View.MAIN, "MainView.fxml");
        LibraryController library = addPage(shell, View.LIBRARY, "LibraryView.fxml");
        addPage(shell, View.HISTORY, "HistoryView.fxml");
        addPage(shell, View.SETTINGS, "SettingsView.fxml");
        shell.setGameOpener(main::selectDirectory);
        shell.setBatchHandler(library::enqueue);

        // extra pane for the theme fade to draw on
        Scene scene = new Scene(new StackPane(shellRoot));
        Theme.apply(scene);
        Ui.fasterScrolling(scene, 3);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setTitle("Lumina");
        stage.getIcons().add(new Image(Objects.requireNonNull(LuminaApp.class.getResourceAsStream("images/icon.png"))));
        WindowChrome chrome = new WindowChrome(stage, (BorderPane) shellRoot);
        chrome.attach(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        restoreWindow(stage, chrome);
        stage.setOnCloseRequest(e -> {
            saveWindow(stage, chrome);
            shell.close();
        });
        stage.show();
        shell.start();

        getParameters().getRaw().stream().map(Path::of).filter(Files::isDirectory).findFirst().ifPresent(shell::openGame);
    }

    private static void restoreWindow(Stage stage, WindowChrome chrome) {
        AppSettings.getWindowBounds().ifPresent(b -> {
            stage.setWidth(Math.max(MIN_WIDTH, b.width()));
            stage.setHeight(Math.max(MIN_HEIGHT, b.height()));
            if (!Screen.getScreensForRectangle(b.x(), b.y(), b.width(), b.height()).isEmpty()) {
                stage.setX(b.x());
                stage.setY(b.y());
            }
        });
        chrome.setMaximized(AppSettings.isWindowMaximized());
    }

    private static void saveWindow(Stage stage, WindowChrome chrome) {
        AppSettings.setWindowMaximized(chrome.isMaximized());
        if (stage.isIconified()) return;
        Rectangle2D b = chrome.normalBounds();
        AppSettings.setWindowBounds(new AppSettings.WindowBounds(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
    }

    private static <T extends Page> T addPage(ShellController shell, View view, String fxml) throws IOException {
        FXMLLoader loader = loader(fxml);
        Parent root = loader.load();
        T page = loader.getController();
        shell.addPage(view, root, page);
        return page;
    }

    private static FXMLLoader loader(String fxml) {
        return new FXMLLoader(LuminaApp.class.getResource("views/" + fxml));
    }

    private void loadFonts() {
        for (String font : FONTS) {
            try (InputStream in = LuminaApp.class.getResourceAsStream("fonts/" + font)) {
                if (in != null) Font.loadFont(in, 12);
            } catch (IOException ignored) {
                // falls back to the system font
            }
        }
    }

    public static void main(String[] args) {
        System.setProperty("LUMINA_LOGS", AppPaths.logs().toString());
        launch(args);
    }
}
