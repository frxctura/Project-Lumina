package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.patching.SteamApi;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class Archives {

    enum Format { ZIP, SEVEN_Z }

    private Archives() {}

    static void extractSteamApi(Path archive, Format format, Path targetDir) throws IOException {
        extractSteamApi(archive, format, targetDir, Function.identity());
    }

    /** @param archiveName maps steam_api.dll / steam_api64.dll to the file that replaces it inside the archive */
    static void extractSteamApi(Path archive, Format format, Path targetDir, Function<String, String> archiveName) throws IOException {
        Files.createDirectories(targetDir);
        if (format == Format.ZIP) extractZip(archive, targetDir, archiveName);
        else extract7z(archive, targetDir, archiveName);
    }

    private static void extractZip(Path archive, Path targetDir, Function<String, String> archiveName) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> names = zip.stream().filter(e -> !e.isDirectory()).map(ZipEntry::getName).toList();
            for (Map.Entry<String, String> pick : choose(names, archiveName).entrySet()) {
                try (InputStream in = zip.getInputStream(zip.getEntry(pick.getValue()))) {
                    Files.copy(in, targetDir.resolve(pick.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void extract7z(Path archive, Path targetDir, Function<String, String> archiveName) throws IOException {
        try {
            extract7zInJava(archive, targetDir, archiveName);
        } catch (IOException | RuntimeException e) {
            // the java library can't read every 7z (gbe_fork's use a multi-stream filter), windows' tar can
            Log.info("7z extraction in Java failed (" + e.getMessage() + "), trying tar");
            extract7zWithTar(archive, targetDir, archiveName);
        }
    }

    private static void extract7zWithTar(Path archive, Path targetDir, Function<String, String> archiveName) throws IOException {
        List<String> names = tar("-tf", archive.toString()).lines().filter(n -> !n.endsWith("/")).toList();
        Map<String, String> picks = choose(names, archiveName);
        Path work = Files.createTempDirectory(targetDir, "tar-");
        try {
            List<String> args = new ArrayList<>(List.of("-xf", archive.toString(), "-C", work.toString()));
            args.addAll(picks.values());
            tar(args.toArray(String[]::new));
            for (Map.Entry<String, String> pick : picks.entrySet())
                Files.move(work.resolve(pick.getValue()), targetDir.resolve(pick.getKey()), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            FileOps.deleteQuietly(work);
        }
    }

    private static String tar(String... args) throws IOException {
        String root = System.getenv("SystemRoot");
        List<String> command = new ArrayList<>();
        command.add(root != null ? root + "\\System32\\tar.exe" : "tar");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("tar took too long to read the archive.");
            }
            if (process.exitValue() != 0) throw new IOException("tar could not read the archive: " + output.strip());
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading the archive.", e);
        }
    }

    private static void extract7zInJava(Path archive, Path targetDir, Function<String, String> archiveName) throws IOException {
        Map<String, String> picks;
        try (SevenZFile sz = new SevenZFile(archive.toFile())) {
            List<String> names = new ArrayList<>();
            for (SevenZArchiveEntry e : sz.getEntries()) if (!e.isDirectory()) names.add(e.getName());
            picks = choose(names, archiveName);
        }
        Map<String, String> byEntry = new HashMap<>();
        picks.forEach((dll, entry) -> byEntry.put(entry, dll));

        try (SevenZFile sz = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = sz.getNextEntry()) != null && !byEntry.isEmpty()) {
                String dll = byEntry.remove(entry.getName());
                if (dll == null) continue;
                try (OutputStream out = Files.newOutputStream(targetDir.resolve(dll))) {
                    int n;
                    while ((n = sz.read(buffer)) > 0) out.write(buffer, 0, n);
                }
            }
        }
    }

    static Map<String, String> choose(Collection<String> entryNames) throws IOException {
        return choose(entryNames, Function.identity());
    }

    /** For each steam_api DLL name, the archive entry that replaces it. The keys are the names the DLLs are stored under. */
    static Map<String, String> choose(Collection<String> entryNames, Function<String, String> archiveName) throws IOException {
        Map<String, String> picks = new LinkedHashMap<>();
        for (String dll : SteamApi.DLL_NAMES) {
            String wanted = archiveName.apply(dll);
            String best = entryNames.stream()
                    .filter(n -> fileName(n).equalsIgnoreCase(wanted))
                    .min(Comparator.comparingInt(Archives::score))
                    .orElseThrow(() -> new IOException("Archive does not contain " + wanted));
            picks.put(dll, best);
        }
        return picks;
    }

    private static int score(String entryName) {
        String n = entryName.toLowerCase().replace('\\', '/');
        int score = n.split("/").length;
        for (String bad : List.of("experimental", "debug", "linux", "steamclient", "tools", "lobby_connect"))
            if (n.contains(bad)) score += 100;
        if (n.contains("regular")) score -= 50;
        return score;
    }

    private static String fileName(String entryName) {
        String n = entryName.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }
}
