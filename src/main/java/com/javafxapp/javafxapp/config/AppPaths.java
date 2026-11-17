package com.javafxapp.javafxapp.config;

import java.nio.file.Path;

public final class AppPaths {

    private static final Path HOME = resolveHome();

    private AppPaths() {}

    private static Path resolveHome() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData != null
                ? Path.of(localAppData, "Lumina")
                : Path.of(System.getProperty("user.home"), ".lumina");
    }

    public static Path home()      { return HOME; }
    public static Path emulators() { return HOME.resolve("emulators"); }
    public static Path logs()      { return HOME.resolve("logs"); }

    public static Path legacyDependencies() {
        return Path.of(System.getProperty("user.dir"), "dependencies");
    }
}
