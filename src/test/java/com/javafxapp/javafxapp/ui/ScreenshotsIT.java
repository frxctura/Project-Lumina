package com.javafxapp.javafxapp.ui;

import com.javafxapp.javafxapp.LuminaApp;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.ui.dialogs.EmulatorSettingsDialog;
import com.javafxapp.javafxapp.ui.pages.LibraryController;
import com.javafxapp.javafxapp.ui.pages.MainController;
import com.javafxapp.javafxapp.ui.shell.AppShell.View;
import com.javafxapp.javafxapp.ui.shell.Page;
import com.javafxapp.javafxapp.ui.shell.ShellController;
import com.javafxapp.javafxapp.ui.shell.Theme;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wires up the real FXML, styles and controllers the way LuminaApp.start does, walks through the screens and dialogs
 * and saves each as a PNG into the folder named by LUMINA_SHOTS. Needs a display, so it only runs with LUMINA_IT=1,
 * and needs LOCALAPPDATA pointing at a scratch folder.
 */
@EnabledIfEnvironmentVariable(named = "LUMINA_IT", matches = "1")
class ScreenshotsIT {

    @TempDir Path tmp;

    private static Path shots;
    private Stage main;

    @BeforeAll
    static void startToolkit() throws Exception {
        assertTrue(AppPaths.home().toString().toLowerCase().contains("scratch"),
                "refusing to run against real app data: point LOCALAPPDATA at a scratch folder, was " + AppPaths.home());
        shots = Files.createDirectories(Path.of(System.getenv("LUMINA_SHOTS")));
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(30, TimeUnit.SECONDS));
    }

    private static byte[] wrappedExe() {
        int peOffset = 0x80;
        ByteBuffer b = ByteBuffer.allocate(peOffset + 24 + 80 + 64).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short) 0x5A4D);
        b.putInt(0x3C, peOffset);
        b.putInt(peOffset, 0x00004550);
        b.putShort(peOffset + 6, (short) 2);
        b.position(peOffset + 24).put(".text".getBytes(StandardCharsets.US_ASCII));
        b.position(peOffset + 24 + 40).put(".bind".getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }

    private Path fakeGame(String name) throws Exception {
        Path game = Files.createDirectories(tmp.resolve(name));
        Files.writeString(game.resolve("steam_api64.dll"), "the real steam api");
        Files.write(game.resolve(name.replace(" ", "") + ".exe"), wrappedExe());
        return game;
    }

    // Driving the FX thread

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(60, TimeUnit.SECONDS);
    }

    /** For actions that open a modal dialog: the FX task doesn't return until the dialog closes. */
    private static void fxAsync(Runnable work) {
        Platform.runLater(work);
    }

    private static void settle(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(owner);
    }

    private Window waitForDialog() throws Exception {
        long end = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < end) {
            Window w = fx(() -> Window.getWindows().stream().filter(x -> x.isShowing() && x != main).findFirst().orElse(null));
            if (w != null) {
                settle(700);
                return w;
            }
            settle(150);
        }
        return fail("no dialog appeared");
    }

    private static void fire(Window w, String text) {
        for (Node n : w.getScene().getRoot().lookupAll(".button"))
            if (n instanceof Button b && text.equals(b.getText())) {
                b.fire();
                return;
            }
        fail("no button '" + text + "' in the dialog");
    }

    private static void close(Window w) throws Exception {
        fx(() -> {
            w.hide();
            return null;
        });
        settle(400);
    }

    // Saving screenshots

    private static void save(String name, Node node) throws Exception {
        WritableImage img = fx(() -> node.snapshot(new SnapshotParameters(), null));
        write(name, img);
    }

    private static void save(String name, Scene scene) throws Exception {
        write(name, fx(() -> scene.snapshot(null)));
    }

    private static void write(String name, WritableImage img) throws Exception {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x55, 0x55, 0x60)); // dialogs have transparent corners
        g.fillRect(0, 0, w, h);
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) argb.setRGB(x, y, img.getPixelReader().getArgb(x, y));
        g.drawImage(argb, 0, 0, null);
        g.dispose();
        ImageIO.write(out, "png", shots.resolve(name + ".png").toFile());
        System.out.println("saved " + name + ".png (" + w + "x" + h + ")");
    }

    private record Loaded<T extends Page>(T page, Parent root) {}

    private <T extends Page> Loaded<T> addPage(ShellController shell, View view, String fxml) throws Exception {
        FXMLLoader loader = new FXMLLoader(LuminaApp.class.getResource("views/" + fxml));
        Parent root = loader.load();
        T page = loader.getController();
        shell.addPage(view, root, page);
        return new Loaded<>(page, root);
    }

    @Test
    void walkThroughTheNewScreens() throws Exception {
        Emulator emulatorBefore = AppSettings.getEmulator();
        boolean unpackBefore = AppSettings.isUnpackDrm(), previewBefore = AppSettings.isPreviewBeforePatch();
        List<Path> recentBefore = new ArrayList<>(AppSettings.getRecentGames());
        // the Defender prompt remembers which folders it asked about, in the real settings; the temp folders mustn't stay there
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("com/javafxapp/javafxapp");
        String defenderAskedBefore = prefs.get("defenderAsked", null);
        try {
            AppSettings.setEmulator(Emulator.SMOKEAPI);
            AppSettings.setUnpackDrm(true);
            AppSettings.setPreviewBeforePatch(true);
            Path game = fakeGame("Fake Game");
            Path other = fakeGame("Other Game");

            // the same wiring as LuminaApp.start
            record Ui(ShellController shell, MainController main, LibraryController library, Parent settings, Scene scene) {}
            Ui ui = fx(() -> {
                for (String f : new String[]{"Nunito-Black.ttf", "Nunito-Light.ttf", "Nunito-Medium.ttf", "Nunito-SemiBold.ttf"})
                    try (InputStream in = LuminaApp.class.getResourceAsStream("fonts/" + f)) {
                        if (in != null) Font.loadFont(in, 12);
                    }
                Theme.load();
                FXMLLoader shellLoader = new FXMLLoader(LuminaApp.class.getResource("views/Shell.fxml"));
                Parent shellRoot = shellLoader.load();
                ShellController shell = shellLoader.getController();
                Loaded<MainController> m = addPage(shell, View.MAIN, "MainView.fxml");
                Loaded<LibraryController> l = addPage(shell, View.LIBRARY, "LibraryView.fxml");
                addPage(shell, View.HISTORY, "HistoryView.fxml");
                Loaded<Page> s = addPage(shell, View.SETTINGS, "SettingsView.fxml");
                shell.setGameOpener(m.page()::selectDirectory);
                shell.setBatchHandler(l.page()::enqueue);
                Scene scene = new Scene(new StackPane(shellRoot), 1280, 820);
                Theme.apply(scene);
                main = new Stage();
                main.setScene(scene);
                main.setTitle("Lumina");
                main.show();
                shell.start();
                return new Ui(shell, m.page(), l.page(), s.root(), scene);
            });
            settle(1200);

            // Home with a game that has a wrapped exe, SmokeAPI selected
            fx(() -> {
                ui.main().selectDirectory(game);
                return null;
            });
            settle(2200);
            save("01-home-smokeapi", ui.scene());

            // per-game settings: SmokeAPI has no username/ID/language, gbe_fork does
            Button settingsButton = field(ui.main(), "emulatorSettingsButton");
            fxAsync(settingsButton::fire);
            Window dialog = waitForDialog();
            save("02-settings-dialog-smokeapi-no-appid", dialog.getScene());
            close(dialog);

            javafx.scene.control.TextField appId = field(ui.main(), "appIdField");
            fx(() -> {
                appId.setText("480");
                return null;
            });
            settle(500);
            fxAsync(settingsButton::fire);
            dialog = waitForDialog();
            save("02b-settings-dialog-smokeapi-with-appid", dialog.getScene());
            close(dialog);

            fxAsync(() -> EmulatorSettingsDialog.show(main, "Fake Game", List.of(game.resolve("steam_settings")), Emulator.GBE_FORK, "480"));
            dialog = waitForDialog();
            save("03-settings-dialog-gbe-fork", dialog.getScene());
            close(dialog);

            // patching: the Defender prompt (unpacking is on), then the preview, then really patch
            Button crack = field(ui.main(), "crackButton");
            fxAsync(crack::fire);
            dialog = waitForDialog();
            save("04-defender-prompt", dialog.getScene());
            close(dialog); // skip: the patch carries on to the preview
            dialog = waitForDialog();
            save("05-preview-smokeapi-unpack", dialog.getScene());
            Window preview = dialog;
            fx(() -> {
                fire(preview, "Patch");
                return null;
            });
            long done = System.currentTimeMillis() + 60_000;
            settle(600);
            save("05b-home-patching", ui.scene());
            while (System.currentTimeMillis() < done && !fx(() -> crack.getText().equals("Patch game") || crack.getText().equals("Re-apply patch") || crack.getText().equals("Done")))
                settle(300);
            settle(2500);
            save("05c-home-after-patch", ui.scene());

            // patching again: the DLLs match, but the exe still has DRM Steamless couldn't remove
            fxAsync(crack::fire);
            dialog = waitForDialog();
            save("05d-preview-second-run", dialog.getScene());
            close(dialog);

            // History shows the entry
            fx(() -> {
                ui.shell().show(View.HISTORY);
                return null;
            });
            settle(1500);
            save("05e-history", ui.scene());

            // Settings page: emulators (SmokeAPI installed) and the DRM unpacking card
            fx(() -> {
                ui.shell().show(View.SETTINGS);
                return null;
            });
            settle(1000);
            save("06-settings-page", ui.scene());

            // Library: batch queue and its preview
            fx(() -> {
                ui.library().enqueue(List.of(other));
                ui.shell().show(View.LIBRARY);
                return null;
            });
            settle(2500);
            save("07-library-queue", ui.scene());
            Button patch = field(ui.library(), "patchButton");
            fxAsync(patch::fire);
            dialog = waitForDialog();
            save("08-batch-defender-prompt", dialog.getScene());
            close(dialog);
            dialog = waitForDialog();
            save("09-batch-preview", dialog.getScene());
            close(dialog);
        } finally {
            AppSettings.setEmulator(emulatorBefore);
            AppSettings.setUnpackDrm(unpackBefore);
            AppSettings.setPreviewBeforePatch(previewBefore);
            AppSettings.clearRecentGames();
            for (int i = recentBefore.size() - 1; i >= 0; i--) AppSettings.addRecentGame(recentBefore.get(i));
            if (defenderAskedBefore == null) prefs.remove("defenderAsked");
            else prefs.put("defenderAsked", defenderAskedBefore);
            if (main != null) fx(() -> {
                main.hide();
                return null;
            });
        }
    }
}
