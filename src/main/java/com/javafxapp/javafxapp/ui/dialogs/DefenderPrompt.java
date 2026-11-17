package com.javafxapp.javafxapp.ui.dialogs;

import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.safety.DefenderExclusion;
import com.javafxapp.javafxapp.ui.components.Toaster;
import com.javafxapp.javafxapp.ui.shell.AppShell;
import com.javafxapp.javafxapp.ui.shell.Ui;
import javafx.concurrent.Task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DefenderPrompt {

    private static final double WIDTH = 540;

    private DefenderPrompt() {}

    // before patching with gbe_fork: asks once per game folder, then calls next whatever the answer was
    public static void offer(AppShell shell, Emulator emulator, List<Path> gameDirs, Runnable next) {
        if (emulator != Emulator.GBE_FORK) {
            next.run();
            return;
        }
        List<Path> fresh = gameDirs.stream().filter(d -> !AppSettings.askedAboutDefender(d)).toList();
        if (fresh.isEmpty()) {
            next.run();
            return;
        }
        fresh.forEach(AppSettings::markAskedAboutDefender);

        List<Path> folders = withEmulators(fresh);
        boolean add = Ui.prompt(shell.window())
                .title("Add a Defender exclusion first?")
                .message("Defender often quarantines gbe_fork's DLLs. Excluding the folders they go into stops that.")
                .details(paths(folders))
                .note("Windows will ask for administrator permission. Lumina only asks once per game.")
                .confirm("ADD EXCLUSION").cancel("SKIP")
                .width(WIDTH)
                .ask();
        if (add) run(shell, folders, next);
        else next.run();
    }

    // the button in the library: any games, any emulator, whenever you like
    public static void addFor(AppShell shell, List<Path> gameDirs) {
        if (gameDirs.isEmpty()) return;
        List<Path> folders = withEmulators(gameDirs);
        boolean add = Ui.prompt(shell.window())
                .title("Exclude these folders from Defender?")
                .message("Defender stops scanning them, so it can't quarantine the emulator DLLs.")
                .details(paths(folders))
                .note("Windows will ask for administrator permission.")
                .confirm("ADD EXCLUSION")
                .width(WIDTH)
                .ask();
        if (!add) return;
        run(shell, folders, () -> {});
    }

    private static void run(AppShell shell, List<Path> folders, Runnable next) {
        shell.setStatus("Waiting for administrator permission...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Files.createDirectories(AppPaths.emulators());
                DefenderExclusion.add(folders);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            folders.forEach(AppSettings::markAskedAboutDefender);
            shell.setStatus("Exclusion added");
            shell.toast(Toaster.Kind.SUCCESS, "Defender exclusions added.");
            next.run();
        });
        task.setOnFailed(e -> {
            shell.setStatus("No exclusion added");
            shell.toast(Toaster.Kind.ERROR, Errors.describe(task.getException()));
            next.run();
        });
        Ui.start(task, "defender-exclusion");
    }

    private static List<Path> withEmulators(List<Path> gameDirs) {
        List<Path> folders = new ArrayList<>(gameDirs);
        folders.add(AppPaths.emulators());
        return folders;
    }

    private static List<String> paths(List<Path> folders) {
        return folders.stream().map(Path::toString).toList();
    }
}
