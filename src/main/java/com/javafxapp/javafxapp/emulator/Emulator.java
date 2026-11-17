package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.patching.SteamApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public enum Emulator {

    GOLDBERG("Goldberg", "Original Goldberg emulator (final 2023 build)", "goldberg", false),
    GBE_FORK("gbe_fork", "Maintained Goldberg fork (latest release)", "gbe_fork", true);

    static final String VERSION_FILE = "version.txt";

    private final String displayName;
    private final String description;
    private final String dirName;
    private final boolean usesSteamSettings;

    Emulator(String displayName, String description, String dirName, boolean usesSteamSettings) {
        this.displayName = displayName;
        this.description = description;
        this.dirName = dirName;
        this.usesSteamSettings = usesSteamSettings;
    }

    public String description()        { return description; }
    public boolean usesSteamSettings() { return usesSteamSettings; }
    public Path dir()                  { return AppPaths.emulators().resolve(dirName); }
    public Path dll(String name)       { return dir().resolve(name); }

    public boolean isInstalled() {
        return SteamApi.DLL_NAMES.stream().allMatch(n -> Files.isRegularFile(dll(n)));
    }

    public Optional<String> installedVersion() {
        if (!isInstalled()) return Optional.empty();
        try {
            return Optional.of(Files.readString(dir().resolve(VERSION_FILE)).trim());
        } catch (IOException e) {
            return Optional.of("unknown version");
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
