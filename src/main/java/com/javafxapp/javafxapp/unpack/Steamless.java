package com.javafxapp.javafxapp.unpack;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Http;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Processes;
import com.javafxapp.javafxapp.common.Progress;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Steamless (by atom0s) removes the SteamStub wrapper from a game executable. Lumina downloads its command-line
 * program once and runs it on the executables {@link SteamStub} finds. Steamless is a Windows .NET program.
 */
public final class Steamless {

    private static final String LATEST_API = "https://api.github.com/repos/atom0s/Steamless/releases/latest";
    private static final Pattern ASSET = Pattern.compile(".*/Steamless\\.v[\\d.]+\\.-\\.by\\.atom0s\\.zip");
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_URL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final String CLI = "Steamless.CLI.exe";
    private static final String VERSION_FILE = "version.txt";
    private static final long TIMEOUT_MINUTES = 3;

    private Steamless() {}

    public static Path dir() {
        return AppPaths.tools().resolve("steamless");
    }

    public static boolean isInstalled() {
        return Files.isRegularFile(dir().resolve(CLI));
    }

    public static Optional<String> installedVersion() {
        if (!isInstalled()) return Optional.empty();
        try {
            return Optional.of(Files.readString(dir().resolve(VERSION_FILE)).trim());
        } catch (IOException e) {
            return Optional.of("unknown version");
        }
    }

    public static void uninstall() throws IOException {
        FileOps.deleteRecursive(dir());
    }

    /** Puts the previous install back if Lumina stopped half-way through an update. */
    public static void recover() {
        Path old = FileOps.previousOf(dir());
        if (!Files.isDirectory(old)) return;
        try {
            FileOps.deleteRecursive(isInstalled() ? old : dir());
            if (!isInstalled()) Files.move(old, dir());
        } catch (IOException e) {
            Log.error("Could not recover the previous Steamless install", e);
        }
    }

    /** Downloads the newest release. The current install stays in place until the new one is complete. */
    public static void install(StatusSink log, Progress progress) throws IOException, InterruptedException {
        recover();
        String json = Http.getString(LATEST_API);
        Matcher tag = TAG.matcher(json);
        String version = tag.find() ? tag.group(1) : "latest";
        String url = null;
        Matcher m = ASSET_URL.matcher(json);
        while (m.find() && url == null) if (ASSET.matcher(m.group(1)).matches()) url = m.group(1);
        if (url == null) throw new IOException("No download found in Steamless " + version);
        log.info("Downloading Steamless " + version + "...");

        // scratch files stay in the tools folder so a Defender exclusion on it covers them
        Files.createDirectories(AppPaths.tools());
        Path archive = Files.createTempFile(AppPaths.tools(), "steamless-", ".tmp");
        Path fresh = AppPaths.tools().resolve("steamless.new");
        try {
            Http.download(url, archive, progress);
            if (!Files.isRegularFile(archive))
                throw new IOException("The Steamless download disappeared right after it finished, most likely removed by antivirus."
                        + " Add a Defender exclusion for " + AppPaths.tools() + " and try again.");
            log.info("Extracting Steamless...");
            FileOps.deleteRecursive(fresh);
            extract(archive, fresh);
            if (!Files.isRegularFile(fresh.resolve(CLI))) throw new IOException("Steamless " + version + " has no " + CLI + ". The current install was kept.");
            Files.writeString(fresh.resolve(VERSION_FILE), version);
            FileOps.replaceDirectory(fresh, dir());
            log.info("Steamless " + version + " installed.");
        } finally {
            Files.deleteIfExists(archive);
            FileOps.deleteQuietly(fresh);
        }
    }

    /** Unpacks the command-line program and its plug-ins. The GUI and the sample plug-in are not needed. */
    private static void extract(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("ExamplePlugin") || name.contains("ExamplePlugin.dll")
                        || name.equals("Steamless.exe") || name.equals("Steamless.exe.config")) continue;
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe path in the Steamless download: " + name);
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out);
                }
            }
        }
    }

    /**
     * Unpacks {@code exe}. Steamless writes {@code <name>.exe.unpacked.exe} next to it; that file is returned and the
     * original is left untouched, so the caller decides what to keep.
     */
    public static Path unpack(Path exe) throws IOException, InterruptedException {
        if (!isInstalled()) throw new IOException("Steamless is not installed.");
        Path unpacked = exe.resolveSibling(exe.getFileName() + SteamStub.UNPACKED_SUFFIX);
        Files.deleteIfExists(unpacked);

        Processes.Result result;
        try {
            result = Processes.run(new ProcessBuilder(dir().resolve(CLI).toString(), "--quiet", exe.toString())
                    .directory(dir().toFile()), Duration.ofMinutes(TIMEOUT_MINUTES));
        } catch (IOException e) {
            throw new IOException("Could not start Steamless. It needs the .NET Framework 4 that comes with Windows."
                    + " Antivirus may also have blocked it: " + e.getMessage(), e);
        }
        if (result.timedOut()) {
            Files.deleteIfExists(unpacked);
            throw new IOException("Steamless took too long on " + exe.getFileName() + ".");
        }
        if (result.exitCode() != 0 || !Files.isRegularFile(unpacked)) {
            Files.deleteIfExists(unpacked);
            throw new IOException("Steamless can't handle it (probably an unsupported SteamStub version)"
                    + (result.output().isEmpty() ? "" : ": " + lastLine(result.output())));
        }
        return unpacked;
    }

    private static String lastLine(String text) {
        String[] lines = text.split("\\R");
        return lines[lines.length - 1].strip();
    }
}
