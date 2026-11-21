package com.javafxapp.javafxapp.config;

import com.javafxapp.javafxapp.emulator.Emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public final class AppSettings {

    // same node as old versions so existing settings carry over
    private static final Preferences PREFS = Preferences.userRoot().node("com/javafxapp/javafxapp");

    private static final String LOG_TO_FILE = "logToFile";
    private static final String MOVE_GAME_DIR = "moveGameDir";
    private static final String MOVE_GAME_DIR_PATH = "moveGameDirPath";
    private static final String BACKUP_GAME_DIR = "backupGameDir";
    private static final String BACKUP_GAME_DIR_PATH = "backupGameDirPath";
    private static final String BACKUP_DLLS = "backupDlls";
    private static final String PREVIEW_BEFORE_PATCH = "previewBeforePatch";
    private static final String UNPACK_DRM = "unpackDrm";
    private static final String EMULATOR = "emulator";
    private static final String RECENT_GAMES = "recentGames";
    private static final String DEFENDER_ASKED = "defenderAsked";
    private static final String THEME = "theme";
    private static final String MOTION = "motion";
    private static final String ACCENT = "accentColor";
    private static final String WINDOW_X = "windowX";
    private static final String WINDOW_Y = "windowY";
    private static final String WINDOW_WIDTH = "windowWidth";
    private static final String WINDOW_HEIGHT = "windowHeight";
    private static final String WINDOW_MAXIMIZED = "windowMaximized";
    private static final int MAX_RECENT = 5;

    private AppSettings() {}

    public static boolean isLogToFile()              { return PREFS.getBoolean(LOG_TO_FILE, false); }
    public static void setLogToFile(boolean v)        { PREFS.putBoolean(LOG_TO_FILE, v); }

    public static boolean isMoveGameDir()             { return PREFS.getBoolean(MOVE_GAME_DIR, false); }
    public static void setMoveGameDir(boolean v)       { PREFS.putBoolean(MOVE_GAME_DIR, v); }
    public static String getMoveGameDirPath()         { return PREFS.get(MOVE_GAME_DIR_PATH, ""); }
    public static void setMoveGameDirPath(String p)    { PREFS.put(MOVE_GAME_DIR_PATH, p == null ? "" : p); }

    public static boolean isBackupGameDir()           { return PREFS.getBoolean(BACKUP_GAME_DIR, false); }
    public static void setBackupGameDir(boolean v)     { PREFS.putBoolean(BACKUP_GAME_DIR, v); }
    public static String getBackupGameDirPath()       { return PREFS.get(BACKUP_GAME_DIR_PATH, ""); }
    public static void setBackupGameDirPath(String p)  { PREFS.put(BACKUP_GAME_DIR_PATH, p == null ? "" : p); }

    public static boolean isBackupDlls()              { return PREFS.getBoolean(BACKUP_DLLS, true); }
    public static void setBackupDlls(boolean v)        { PREFS.putBoolean(BACKUP_DLLS, v); }

    public static boolean isPreviewBeforePatch()      { return PREFS.getBoolean(PREVIEW_BEFORE_PATCH, true); }
    public static void setPreviewBeforePatch(boolean v) { PREFS.putBoolean(PREVIEW_BEFORE_PATCH, v); }

    public static boolean isUnpackDrm()               { return PREFS.getBoolean(UNPACK_DRM, false); }
    public static void setUnpackDrm(boolean v)         { PREFS.putBoolean(UNPACK_DRM, v); }

    public static Emulator getEmulator() {
        try {
            return Emulator.valueOf(PREFS.get(EMULATOR, Emulator.GOLDBERG.name()));
        } catch (IllegalArgumentException e) {
            return Emulator.GOLDBERG;
        }
    }

    public static void setEmulator(Emulator e) { PREFS.put(EMULATOR, e.name()); }

    public static List<Path> getRecentGames() {
        return Arrays.stream(PREFS.get(RECENT_GAMES, "").split("\n"))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory)
                .collect(Collectors.toList());
    }

    public static void addRecentGame(Path dir) {
        List<Path> recent = new ArrayList<>(getRecentGames());
        recent.remove(dir);
        recent.add(0, dir);
        PREFS.put(RECENT_GAMES, recent.stream().limit(MAX_RECENT).map(Path::toString).collect(Collectors.joining("\n")));
    }

    public static void clearRecentGames() { PREFS.remove(RECENT_GAMES); }

    // Defender exclusions can't be read without admin rights, so remember where we already asked
    public static boolean askedAboutDefender(Path dir) {
        return askedFolders().contains(dir.toAbsolutePath().normalize().toString());
    }

    public static void markAskedAboutDefender(Path dir) {
        List<String> asked = new ArrayList<>(askedFolders());
        String key = dir.toAbsolutePath().normalize().toString();
        if (asked.contains(key)) return;
        asked.add(key);
        PREFS.put(DEFENDER_ASKED, String.join("\n", newest(asked, 100, MAX_PREF_CHARS)));
    }

    // a registry-backed preference holds at most Preferences.MAX_VALUE_LENGTH (8192) characters, and put() throws beyond it
    private static final int MAX_PREF_CHARS = 8000;

    /** The last {@code maxItems} entries of {@code items} that still fit in {@code maxChars} once joined with newlines. */
    static List<String> newest(List<String> items, int maxItems, int maxChars) {
        List<String> kept = new ArrayList<>();
        int chars = 0;
        for (int i = items.size() - 1; i >= 0 && kept.size() < maxItems; i--) {
            int added = items.get(i).length() + (kept.isEmpty() ? 0 : 1);
            if (chars + added > maxChars) break;
            chars += added;
            kept.add(0, items.get(i));
        }
        return kept;
    }

    private static List<String> askedFolders() {
        return Arrays.stream(PREFS.get(DEFENDER_ASKED, "").split("\n")).filter(s -> !s.isBlank()).toList();
    }

    public enum Theme { DARK, LIGHT, SYSTEM }

    public static Theme getTheme() {
        try {
            return Theme.valueOf(PREFS.get(THEME, Theme.DARK.name()));
        } catch (IllegalArgumentException e) {
            return Theme.DARK;
        }
    }

    public static void setTheme(Theme theme) { PREFS.put(THEME, theme.name()); }

    /** Custom accent color as #rrggbb, or empty for the built-in purple. */
    public static String getAccent()             { return PREFS.get(ACCENT, ""); }
    public static void setAccent(String hex)      { if (hex == null || hex.isBlank()) PREFS.remove(ACCENT); else PREFS.put(ACCENT, hex); }

    /** Animations: follow Windows (Settings, Accessibility, Visual effects), always on, or reduced. */
    public enum Motion { SYSTEM, FULL, REDUCED }

    public static Motion getMotion() {
        try {
            return Motion.valueOf(PREFS.get(MOTION, Motion.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            return Motion.SYSTEM;
        }
    }

    public static void setMotion(Motion motion) { PREFS.put(MOTION, motion.name()); }

    public record WindowBounds(double x, double y, double width, double height) {}

    public static Optional<WindowBounds> getWindowBounds() {
        double width = PREFS.getDouble(WINDOW_WIDTH, 0);
        double height = PREFS.getDouble(WINDOW_HEIGHT, 0);
        if (width <= 0 || height <= 0) return Optional.empty();
        return Optional.of(new WindowBounds(PREFS.getDouble(WINDOW_X, 0), PREFS.getDouble(WINDOW_Y, 0), width, height));
    }

    public static void setWindowBounds(WindowBounds b) {
        PREFS.putDouble(WINDOW_X, b.x());
        PREFS.putDouble(WINDOW_Y, b.y());
        PREFS.putDouble(WINDOW_WIDTH, b.width());
        PREFS.putDouble(WINDOW_HEIGHT, b.height());
    }

    public static boolean isWindowMaximized()          { return PREFS.getBoolean(WINDOW_MAXIMIZED, false); }
    public static void setWindowMaximized(boolean v)    { PREFS.putBoolean(WINDOW_MAXIMIZED, v); }

    public static Optional<Path> backupTarget() {
        return isBackupGameDir() ? Optional.of(Path.of(getBackupGameDirPath())) : Optional.empty();
    }

    public static Optional<Path> moveTarget() {
        return isMoveGameDir() ? Optional.of(Path.of(getMoveGameDirPath())) : Optional.empty();
    }

    public static void reset() {
        setLogToFile(false);
        setMoveGameDir(false);
        setMoveGameDirPath("");
        setBackupGameDir(false);
        setBackupGameDirPath("");
        setBackupDlls(true);
        setPreviewBeforePatch(true);
        setUnpackDrm(false);
        setEmulator(Emulator.GOLDBERG);
        setMotion(Motion.SYSTEM);
        setAccent("");
        PREFS.remove(DEFENDER_ASKED);
    }

    public static Optional<String> validate() {
        if (isMoveGameDir()) {
            Optional<String> err = checkDir(getMoveGameDirPath(), "Move game directory after cracking");
            if (err.isPresent()) return err;
        }
        return isBackupGameDir() ? checkDir(getBackupGameDirPath(), "Back up entire game directory") : Optional.empty();
    }

    private static Optional<String> checkDir(String path, String setting) {
        if (path.isBlank())
            return Optional.of("\"" + setting + "\" is enabled\nbut no directory was selected.");
        if (!Files.isDirectory(Path.of(path)))
            return Optional.of("The directory for \"" + setting + "\"\ndoes not exist.");
        return Optional.empty();
    }
}
