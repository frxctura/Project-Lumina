package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Processes;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SteamLibrary {

    private static final Path DEFAULT_STEAM = Path.of("C:/Program Files (x86)/Steam");
    private static final Set<String> TOOL_APP_IDS = Set.of("228980");
    private static final List<String> TOOL_PREFIXES = List.of("Steamworks", "Steam Linux Runtime", "Proton ");
    private static final String CDN_HEADER = "https://cdn.cloudflare.steamstatic.com/steam/apps/%s/header.jpg";

    public record Library(Path path, boolean online) {}

    public record Game(String appId, String name, Path dir, long size, Optional<String> cover) {}

    public record Scan(Optional<Path> steam, List<Library> libraries, List<Game> games) {}

    record Manifest(String appId, String name, String installDir, long sizeOnDisk) {}

    private SteamLibrary() {}

    public static Scan scan() {
        Optional<Path> steam = findSteam();
        if (steam.isEmpty()) return new Scan(Optional.empty(), List.of(), List.of());

        List<Library> libraries = new ArrayList<>();
        List<Game> games = new ArrayList<>();
        for (Path path : libraryPaths(steam.get())) {
            boolean online = Files.isDirectory(path.resolve("steamapps"));
            libraries.add(new Library(path, online));
            if (online) games.addAll(gamesIn(path, steam.get()));
        }
        games.sort(Comparator.comparing(Game::name, String.CASE_INSENSITIVE_ORDER));
        return new Scan(steam, libraries, games);
    }

    public static Game folder(Path dir) {
        return new Game("", dir.getFileName().toString(), dir, -1, Optional.empty());
    }

    static Optional<Path> findSteam() {
        // for screenshots and testing: point Lumina at a demo Steam folder instead of the real one
        String override = System.getenv("LUMINA_STEAM_DIR");
        if (override != null && !override.isBlank())
            return Optional.of(Path.of(override.strip())).filter(p -> Files.isDirectory(p.resolve("steamapps")));
        Optional<Path> fromRegistry = Optional.empty();
        try {
            fromRegistry = registryValue("HKCU\\Software\\Valve\\Steam", "SteamPath").map(Path::of);
        } catch (IOException | RuntimeException e) {
            Log.error("Could not read the Steam path from the registry", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fromRegistry.filter(p -> Files.isDirectory(p.resolve("steamapps")))
                .or(() -> Optional.of(DEFAULT_STEAM).filter(p -> Files.isDirectory(p.resolve("steamapps"))))
                .map(SteamLibrary::realCase);
    }

    /**
     * Reads a string value from the registry. reg.exe writes in the console's code page, which is rarely UTF-8, and
     * characters it can't represent turn into "?", so a Steam folder like D:\Spiele Größe would come back wrong.
     * Switching the code page to UTF-8 first makes it write the real text.
     */
    static Optional<String> registryValue(String key, String value) throws IOException, InterruptedException {
        Processes.Result result = Processes.run(new ProcessBuilder("cmd.exe", "/d", "/s", "/c",
                "chcp 65001>nul & reg query " + key + " /v " + value), Duration.ofSeconds(10));
        return result.succeeded() ? parseRegQuery(result.output(), value) : Optional.empty();
    }

    // steam lowercases the path, the real casing looks nicer
    private static Path realCase(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    static Optional<String> parseRegQuery(String output) {
        return parseRegQuery(output, "SteamPath");
    }

    static Optional<String> parseRegQuery(String output, String valueName) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(valueName) + "\\s+REG_\\w+\\s+(.+)$").matcher(output);
        return m.find() ? Optional.of(m.group(1).trim()).filter(s -> !s.isEmpty()) : Optional.empty();
    }

    private static List<Path> libraryPaths(Path steam) {
        Map<String, Path> unique = new LinkedHashMap<>();
        unique.put(key(steam), steam);
        try {
            for (Path p : parseLibraryFolders(Files.readString(steam.resolve("steamapps/libraryfolders.vdf"))))
                unique.putIfAbsent(key(p), p);
        } catch (IOException e) {
            Log.error("Could not read libraryfolders.vdf", e);
        }
        return List.copyOf(unique.values());
    }

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    static List<Path> parseLibraryFolders(String vdf) {
        Map<String, Object> folders = Vdf.object(Vdf.parse(vdf), "libraryfolders").orElse(Map.of());
        List<Path> paths = new ArrayList<>();
        folders.forEach((key, value) -> {
            if (!key.chars().allMatch(Character::isDigit)) return;
            Optional<String> path = value instanceof String s ? Optional.of(s) : Vdf.object(folders, key).flatMap(m -> Vdf.string(m, "path"));
            path.filter(s -> !s.isBlank()).ifPresent(s -> paths.add(Path.of(s)));
        });
        return paths;
    }

    static Optional<Manifest> parseManifest(String acf) {
        return Vdf.object(Vdf.parse(acf), "AppState").flatMap(state -> {
            Optional<String> appId = Vdf.string(state, "appid");
            Optional<String> installDir = Vdf.string(state, "installdir").filter(s -> !s.isBlank());
            if (appId.isEmpty() || installDir.isEmpty()) return Optional.empty();
            long size;
            try {
                size = Long.parseLong(Vdf.string(state, "SizeOnDisk").orElse("-1"));
            } catch (NumberFormatException e) {
                size = -1;
            }
            String name = Vdf.string(state, "name").filter(s -> !s.isBlank()).orElse(installDir.get());
            return Optional.of(new Manifest(appId.get(), name, installDir.get(), size));
        });
    }

    static boolean isTool(Manifest m) {
        return TOOL_APP_IDS.contains(m.appId()) || TOOL_PREFIXES.stream().anyMatch(m.name()::startsWith);
    }

    private static List<Game> gamesIn(Path library, Path steam) {
        Path steamapps = library.resolve("steamapps");
        List<Game> games = new ArrayList<>();
        try (Stream<Path> files = Files.list(steamapps)) {
            files.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("appmanifest_") && n.endsWith(".acf");
            }).forEach(acf -> {
                try {
                    parseManifest(Files.readString(acf)).filter(m -> !isTool(m)).ifPresent(m -> {
                        Path dir = steamapps.resolve("common").resolve(m.installDir());
                        if (Files.isDirectory(dir))
                            games.add(new Game(m.appId(), m.name(), dir, m.sizeOnDisk() > 0 ? m.sizeOnDisk() : -1, Optional.of(cover(steam, m.appId()))));
                    });
                } catch (IOException e) {
                    Log.error("Skipping unreadable manifest " + acf, e);
                }
            });
        } catch (IOException e) {
            Log.error("Could not list " + steamapps, e);
        }
        return games;
    }

    private static String cover(Path steam, String appId) {
        Path cache = steam.resolve("appcache/librarycache");
        for (Path p : List.of(cache.resolve(appId).resolve("header.jpg"), cache.resolve(appId + "_header.jpg")))
            if (Files.isRegularFile(p)) return p.toUri().toString();
        return CoverCache.cached(appId).map(p -> p.toUri().toString()).orElse(String.format(CDN_HEADER, appId));
    }
}
