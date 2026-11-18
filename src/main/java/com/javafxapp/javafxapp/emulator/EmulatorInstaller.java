package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Http;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Progress;
import com.javafxapp.javafxapp.common.StatusSink;
import com.javafxapp.javafxapp.config.AppPaths;
import com.javafxapp.javafxapp.patching.SteamApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmulatorInstaller {

    private record Release(String url, String version, Archives.Format format) {}

    private static final String GOLDBERG_URL =
            "https://gitlab.com/Mr_Goldberg/goldberg_emulator/-/jobs/4247811310/artifacts/download";
    private static final String GBE_LATEST_API = "https://api.github.com/repos/Detanup01/gbe_fork/releases/latest";
    private static final Pattern GBE_ASSET = Pattern.compile(".*/emu-win-release(-vs\\d+)?\\.7z");
    private static final String SMOKE_LATEST_API = "https://api.github.com/repos/acidicoala/SmokeAPI/releases/latest";
    private static final Pattern SMOKE_ASSET = Pattern.compile(".*/SmokeAPI-v[\\d.]+\\.zip");
    private static final Pattern TAG =Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET_URL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");

    private EmulatorInstaller() {}

    public static void install(Emulator emulator, StatusSink log, Progress progress) throws IOException, InterruptedException {
        recover(emulator);
        Release release = resolve(emulator);
        log.info("Downloading " + emulator + " " + release.version() + "...");

        // scratch files go in the emulators folder, not %TEMP%, so a Defender exclusion on it covers them
        Files.createDirectories(AppPaths.emulators());
        Path archive = Files.createTempFile(AppPaths.emulators(), emulator.name().toLowerCase() + "-", ".tmp");
        Path staging = Files.createTempDirectory(AppPaths.emulators(), "extract-");
        Path fresh = sibling(emulator, ".new");
        try {
            Http.download(release.url(), archive, progress);
            if (!Files.isRegularFile(archive))
                throw new IOException("The " + emulator + " download disappeared right after it finished, most likely removed by antivirus."
                        + " Add a Defender exclusion for " + AppPaths.emulators() + " and try again.");
            log.info("Extracting...");
            Archives.extractSteamApi(archive, release.format(), staging, emulator::archiveName);
            Files.writeString(staging.resolve(Emulator.VERSION_FILE), release.version());

            FileOps.deleteRecursive(fresh);
            Files.createDirectories(emulator.dir().getParent());
            FileOps.copyDirectory(staging, fresh);
            for (String dll : SteamApi.DLL_NAMES)
                if (!Files.isRegularFile(fresh.resolve(dll)))
                    throw new IOException(emulator + " " + release.version() + " is missing " + dll + ". The current install was kept.");
            FileOps.replaceDirectory(fresh, emulator.dir());
            log.info(emulator + " " + release.version() + " installed.");
        } finally {
            Files.deleteIfExists(archive);
            FileOps.deleteRecursive(staging);
            FileOps.deleteQuietly(fresh);
        }
    }

    public static void recover(Emulator emulator) {
        Path old = FileOps.previousOf(emulator.dir());
        if (!Files.isDirectory(old)) return;
        try {
            if (emulator.isInstalled()) {
                FileOps.deleteRecursive(old);
            } else {
                FileOps.deleteRecursive(emulator.dir());
                Files.move(old, emulator.dir());
                Log.info("Recovered the previous " + emulator + " install after an interrupted update.");
            }
        } catch (IOException e) {
            Log.error("Could not recover the previous " + emulator + " install", e);
        }
    }

    private static Path sibling(Emulator emulator, String suffix) {
        return emulator.dir().resolveSibling(emulator.dir().getFileName() + suffix);
    }

    public static void uninstall(Emulator emulator) throws IOException {
        FileOps.deleteRecursive(emulator.dir());
    }

    public static void migrateLegacyInstall() {
        for (Emulator e : Emulator.values()) recover(e);
        Path legacy = AppPaths.legacyDependencies();
        if (Emulator.GOLDBERG.isInstalled()) return;
        if (!SteamApi.DLL_NAMES.stream().allMatch(n -> Files.isRegularFile(legacy.resolve(n)))) return;
        try {
            Files.createDirectories(Emulator.GOLDBERG.dir());
            for (String n : SteamApi.DLL_NAMES)
                Files.copy(legacy.resolve(n), Emulator.GOLDBERG.dll(n), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(Emulator.GOLDBERG.dir().resolve(Emulator.VERSION_FILE), "2023-05 (migrated)");
            Log.info("Migrated legacy Goldberg install from " + legacy);
        } catch (IOException e) {
            Log.error("Legacy migration failed", e);
        }
    }

    private static Release resolve(Emulator emulator) throws IOException, InterruptedException {
        return switch (emulator) {
            case GOLDBERG -> new Release(GOLDBERG_URL, "2023-05 (475342f0)", Archives.Format.ZIP);
            case GBE_FORK -> latestGithub(GBE_LATEST_API, GBE_ASSET, Archives.Format.SEVEN_Z, "gbe_fork");
            case SMOKEAPI -> latestGithub(SMOKE_LATEST_API, SMOKE_ASSET, Archives.Format.ZIP, "SmokeAPI");
        };
    }

    /** The newest GitHub release of a project, and the download in it whose URL matches {@code asset}. */
    private static Release latestGithub(String api, Pattern asset, Archives.Format format, String project)
            throws IOException, InterruptedException {
        String json = Http.getString(api);
        Matcher tag = TAG.matcher(json);
        String version = tag.find() ? tag.group(1) : "latest";

        List<String> candidates = new ArrayList<>();
        Matcher m = ASSET_URL.matcher(json);
        while (m.find()) if (asset.matcher(m.group(1)).matches()) candidates.add(m.group(1));

        String url = candidates.stream().min(Comparator.naturalOrder())
                .orElseThrow(() -> new IOException("No Windows release found in " + project + " " + version));
        return new Release(url, version, format);
    }
}
