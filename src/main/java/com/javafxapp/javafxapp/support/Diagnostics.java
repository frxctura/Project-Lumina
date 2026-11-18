package com.javafxapp.javafxapp.support;

import com.javafxapp.javafxapp.backup.History;
import com.javafxapp.javafxapp.backup.HistoryEntry;
import com.javafxapp.javafxapp.common.Errors;
import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.config.AppInfo;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.config.AppSettings;
import com.javafxapp.javafxapp.emulator.Emulator;
import com.javafxapp.javafxapp.emulator.EmulatorConfig;
import com.javafxapp.javafxapp.emulator.SteamInterfaces;
import com.javafxapp.javafxapp.library.GameInfo;
import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.patching.SteamApi;
import com.javafxapp.javafxapp.unpack.Steamless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A text report to paste into a bug report: versions, settings, what Lumina found in the game, and the end of the
 * log. It leaves out anything personal, the user's home folder becomes %USERPROFILE%, and it never includes file
 * contents, only names.
 */
public final class Diagnostics {

    static final int LOG_LINES = 40;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Diagnostics() {}

    /** @param game the game open in Lumina, if any */
    public static String build(Optional<Path> game) {
        StringBuilder out = new StringBuilder();
        out.append("Lumina ").append(AppInfo.version()).append('\n');
        out.append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")  ·  Java ")
                .append(System.getProperty("java.version")).append('\n');
        out.append("Data folder: ").append(AppPaths.home()).append('\n');

        out.append("\nEmulators\n");
        for (Emulator e : Emulator.values())
            out.append("  ").append(e).append(": ").append(e.installedVersion().map(v -> "installed (" + v + ")").orElse("not installed")).append('\n');
        out.append("  Steamless: ").append(Steamless.installedVersion().map(v -> "installed (" + v + ")").orElse("not installed")).append('\n');

        out.append("\nSettings\n");
        out.append("  Emulator: ").append(AppSettings.getEmulator())
                .append("  ·  .bak backups: ").append(onOff(AppSettings.isBackupDlls()))
                .append("  ·  unpack SteamStub: ").append(onOff(AppSettings.isUnpackDrm()))
                .append("  ·  preview: ").append(onOff(AppSettings.isPreviewBeforePatch())).append('\n');
        out.append("  Folder copy before patching: ").append(onOff(AppSettings.isBackupGameDir()))
                .append("  ·  move afterwards: ").append(onOff(AppSettings.isMoveGameDir()))
                .append("  ·  logging to file: ").append(onOff(AppSettings.isLogToFile())).append('\n');

        out.append("\nGame\n");
        if (game.isPresent()) out.append(gameSection(game.get()));
        else out.append("  none open. Open the game on Lumina's Home page first and copy this again, so it includes the game.\n");

        out.append("\nEnd of the log\n");
        out.append(logSection(AppPaths.logs().resolve("lumina.log"), AppSettings.isLogToFile()));
        return redact(out.toString());
    }

    /** What Lumina sees in a game folder: its DLLs and whether they're patched, backups, settings files, history. */
    public static String gameSection(Path dir) {
        StringBuilder out = new StringBuilder();
        out.append("  ").append(dir.getFileName()).append("  (").append(dir).append(")\n");
        GameInfo info;
        try {
            info = GameInfo.scan(dir);
        } catch (IOException | RuntimeException e) {
            return out.append("  could not read the folder: ").append(Errors.describe(e)).append('\n').toString();
        }
        out.append("  ").append(FileOps.formatSize(info.totalSize())).append(" in ").append(info.fileCount()).append(" files")
                .append("  ·  Steam App ID: ").append(info.appId().orElse("not set"))
                .append("  ·  state: ").append(info.patch().state().name().toLowerCase(Locale.ROOT))
                .append("  ·  SteamStub executables: ").append(info.stubCount()).append('\n');

        if (info.patch().dlls().isEmpty()) out.append("  steam_api DLLs: none found\n");
        for (PatchDetector.DllStatus d : info.patch().dlls()) {
            out.append("  ").append(dir.relativize(d.dll())).append(": ")
                    .append(d.emulator().map(name -> "patched with " + name).orElse("not an emulator DLL (original)"));
            Path settings = EmulatorConfig.settingsDir(d.dll());
            List<String> files = namesIn(settings);
            if (!files.isEmpty()) out.append("  ·  steam_settings: ").append(String.join(", ", files));
            SteamInterfaces.existing(settings).ifPresent(n -> out.append("  ·  steam_interfaces.txt lists ").append(n));
            if (Files.exists(d.dll().resolveSibling("SmokeAPI.config.json"))) out.append("  ·  SmokeAPI.config.json present");
            out.append('\n');
        }

        try {
            List<String> backups = FileOps.findFiles(dir, SteamApi::isBackup).stream()
                    .map(p -> dir.relativize(p).toString()).sorted().toList();
            out.append("  backup files: ").append(backups.isEmpty() ? "none" : String.join(", ", backups)).append('\n');
        } catch (IOException e) {
            out.append("  backup files: could not look (").append(Errors.describe(e)).append(")\n");
        }

        List<HistoryEntry> history = History.load().stream().filter(e -> e.gameDir().equals(dir)).limit(5).toList();
        if (!history.isEmpty()) {
            out.append("  history:\n");
            for (HistoryEntry e : history)
                out.append("    ").append(TIME.format(e.time())).append("  ").append(e.action().name().toLowerCase(Locale.ROOT))
                        .append(e.emulator().isEmpty() ? "" : " with " + e.emulator()).append("  ·  ").append(e.files()).append(" file(s)")
                        .append(e.unpacked() > 0 ? ", " + e.unpacked() + " unpacked" : "")
                        .append(e.failed() > 0 ? ", " + e.failed() + " failed" : "").append('\n');
        }
        return out.toString();
    }

    /** The last {@code LOG_LINES} lines of the log, or a hint if there is none. */
    static String logSection(Path logFile, boolean loggingOn) {
        if (!Files.isRegularFile(logFile) || isEmpty(logFile))
            return loggingOn
                    ? "  logging is on, but nothing has been logged yet. Repeat the problem and copy this again.\n"
                    : "  nothing logged. Turn on \"Write activity to a log file\" in Settings, repeat the problem, and copy this again.\n";
        try {
            List<String> lines = tail(logFile, LOG_LINES);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append("  ").append(line.length() > 400 ? line.substring(0, 400) + "…" : line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return "  could not read the log: " + Errors.describe(e) + "\n";
        }
    }

    private static boolean isEmpty(Path file) {
        try {
            return Files.size(file) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    /** The last {@code count} lines of a file, without reading all of a large log into memory. */
    static List<String> tail(Path file, int count) throws IOException {
        final long window = 64 * 1024;
        try (var channel = Files.newByteChannel(file)) {
            long size = channel.size();
            long start = Math.max(0, size - window);
            channel.position(start);
            byte[] bytes = new byte[(int) (size - start)];
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* fill */ }
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
            if (start > 0 && !lines.isEmpty()) lines.remove(0); // began in the middle of a line
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
            return lines.subList(Math.max(0, lines.size() - count), lines.size());
        }
    }

    /** Replaces the user's own folders with variables, so a pasted report doesn't carry the Windows account name. */
    static String redact(String text) {
        String result = text;
        for (String[] pair : new String[][]{
                {System.getenv("LOCALAPPDATA"), "%LOCALAPPDATA%"},
                {System.getenv("APPDATA"), "%APPDATA%"},
                {System.getProperty("user.home"), "%USERPROFILE%"}}) {
            if (pair[0] == null || pair[0].isBlank()) continue;
            result = replaceIgnoreCase(result, pair[0], pair[1]);
            result = replaceIgnoreCase(result, pair[0].replace('\\', '/'), pair[1]);
        }
        return result;
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(target), java.util.regex.Matcher.quoteReplacement(replacement));
    }

    private static List<String> namesIn(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }
}
