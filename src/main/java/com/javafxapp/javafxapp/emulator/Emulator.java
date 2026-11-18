package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.patching.SteamApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public enum Emulator {

    GOLDBERG("Goldberg", "Original Goldberg emulator (final 2023 build)", "goldberg", Strategy.REPLACE, true, false),
    GBE_FORK("gbe_fork", "Maintained Goldberg fork (latest release)", "gbe_fork", Strategy.REPLACE, true, true),
    SMOKEAPI("SmokeAPI", "Unlocks DLC for games you own, the game keeps its real Steam login. The original DLL stays beside it as *_o.dll",
            "smokeapi", Strategy.PROXY, false, true);

    /** How an emulator gets into a game. */
    public enum Strategy {
        /** Overwrites steam_api(64).dll; the original is kept as a .bak copy if you ask for it. */
        REPLACE,
        /** Takes the place of steam_api(64).dll and loads the original, which is renamed to steam_api(64)_o.dll. */
        PROXY
    }

    static final String VERSION_FILE = "version.txt";

    private final String displayName;
    private final String description;
    private final String dirName;
    private final Strategy strategy;
    private final boolean identity;
    private final boolean unlockAll;

    Emulator(String displayName, String description, String dirName, Strategy strategy, boolean identity, boolean unlockAll) {
        this.displayName = displayName;
        this.description = description;
        this.dirName = dirName;
        this.strategy = strategy;
        this.identity = identity;
        this.unlockAll = unlockAll;
    }

    public String description()        { return description; }
    public Strategy strategy()         { return strategy; }
    public boolean isProxy()           { return strategy == Strategy.PROXY; }
    /** Only gbe_fork reads steam_settings/steam_appid.txt. Proxies are not given an App ID file at all. */
    public boolean usesSteamSettings() { return this == GBE_FORK; }
    public boolean writesAppId()       { return !isProxy(); }
    /** Whether the emulator has its own username, Steam ID, language and offline switch. */
    public boolean supportsIdentity()  { return identity; }
    public boolean supportsUnlockAll() { return unlockAll; }
    /** Goldberg and gbe_fork answer an older game's interface requests from steam_settings/steam_interfaces.txt. */
    public boolean supportsInterfacesFile() { return !isProxy(); }
    public Path dir()                  { return AppPaths.emulators().resolve(dirName); }
    public Path dll(String name)       { return dir().resolve(name); }

    /** The file inside the downloaded archive that becomes {@code steam_api.dll} or {@code steam_api64.dll} here. */
    String archiveName(String dllName) {
        if (this != SMOKEAPI) return dllName;
        return dllName.equals("steam_api.dll") ? "smoke_api32.dll" : "smoke_api64.dll";
    }

    /** Where this emulator's per-game settings live, given one of the game's steam_api DLLs. */
    public Path settingsDir(Path dll) {
        return this == SMOKEAPI ? dll.toAbsolutePath().getParent() : EmulatorConfig.settingsDir(dll);
    }

    public String settingsLocation() {
        return this == SMOKEAPI ? "SmokeAPI.config.json" : "steam_settings";
    }

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
