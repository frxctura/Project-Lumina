package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.common.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public record EmulatorConfig(String username, String steamId, String language, boolean offline,
                             boolean unlockAllDlc, List<Dlc> dlcs) {

    public record Dlc(String appId, String name) {}

    public static final String DIR = "steam_settings";
    public static final List<String> LANGUAGES = List.of(
            "english", "german", "french", "italian", "spanish", "latam", "portuguese", "brazilian", "polish",
            "russian", "ukrainian", "czech", "hungarian", "romanian", "bulgarian", "greek", "turkish", "dutch",
            "danish", "finnish", "norwegian", "swedish", "japanese", "koreana", "schinese", "tchinese", "thai",
            "vietnamese", "arabic");

    public static EmulatorConfig empty() {
        return new EmulatorConfig("", "", "", false, false, List.of());
    }

    public static Path settingsDir(Path dll) {
        return dll.resolveSibling(DIR);
    }

    public static EmulatorConfig load(Path settingsDir, Emulator emulator) throws IOException {
        return load(settingsDir, emulator, "");
    }

    /** @param appId the game's Steam App ID; SmokeAPI keeps its extra DLC under it */
    public static EmulatorConfig load(Path settingsDir, Emulator emulator, String appId) throws IOException {
        return switch (emulator) {
            case GBE_FORK -> loadGbe(settingsDir);
            case SMOKEAPI -> loadSmoke(settingsDir, appId);
            case GOLDBERG -> loadGoldberg(settingsDir);
        };
    }

    public void save(Path settingsDir, Emulator emulator) throws IOException {
        save(settingsDir, emulator, "");
    }

    public void save(Path settingsDir, Emulator emulator, String appId) throws IOException {
        Files.createDirectories(settingsDir);
        switch (emulator) {
            case GBE_FORK -> saveGbe(settingsDir);
            case SMOKEAPI -> saveSmoke(settingsDir, appId);
            case GOLDBERG -> saveGoldberg(settingsDir);
        }
    }

    // SmokeAPI: SmokeAPI.config.json next to the DLL. Fields Lumina doesn't edit are left as they are.

    static final String SMOKE_FILE = "SmokeAPI.config.json";

    @SuppressWarnings("unchecked")
    private static EmulatorConfig loadSmoke(Path dir, String appId) throws IOException {
        Path file = dir.resolve(SMOKE_FILE);
        if (!Files.isRegularFile(file)) return new EmulatorConfig("", "", "", false, true, List.of()); // SmokeAPI unlocks by default
        Map<String, Object> root = Json.parseObject(Files.readString(file));
        boolean unlocked = "unlocked".equals(root.getOrDefault("default_app_status", "unlocked"));
        List<Dlc> dlcs = new ArrayList<>();
        if (root.get("extra_dlcs") instanceof Map<?, ?> extra && extra.get(appId) instanceof Map<?, ?> app
                && app.get("dlcs") instanceof Map<?, ?> list) {
            ((Map<String, Object>) list).forEach((id, name) -> dlcs.add(new Dlc(id, String.valueOf(name))));
        }
        return new EmulatorConfig("", "", "", false, unlocked, dlcs);
    }

    @SuppressWarnings("unchecked")
    private void saveSmoke(Path dir, String appId) throws IOException {
        if (!dlcs.isEmpty() && !appId.matches("\\d{1,10}"))
            throw new IOException("SmokeAPI keeps extra DLC under the game's App ID. Set the App ID on the main screen first.");
        Path file = dir.resolve(SMOKE_FILE);
        Map<String, Object> root = Files.isRegularFile(file) ? Json.parseObject(Files.readString(file)) : new LinkedHashMap<>();

        root.putIfAbsent("$version", 4L);
        root.putIfAbsent("logging", false); // the packaged default logs everything, which slows games down
        root.put("default_app_status", unlockAllDlc ? "unlocked" : "original");

        Map<String, Object> extra = root.get("extra_dlcs") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        if (appId.matches("\\d{1,10}")) {
            if (dlcs.isEmpty()) {
                extra.remove(appId);
            } else {
                Map<String, Object> list = new LinkedHashMap<>();
                for (Dlc d : dlcs) list.put(d.appId(), d.name());
                Map<String, Object> app = new LinkedHashMap<>();
                app.put("dlcs", list);
                extra.put(appId, app);
            }
        }
        root.put("extra_dlcs", extra);
        Files.writeString(file, Json.write(root));
    }

    // Goldberg: force_*.txt, DLC.txt, offline.txt

    private static EmulatorConfig loadGoldberg(Path dir) throws IOException {
        List<Dlc> dlcs = new ArrayList<>();
        Path dlcFile = dir.resolve("DLC.txt");
        if (Files.isRegularFile(dlcFile)) {
            for (String line : Files.readAllLines(dlcFile)) parseDlc(line).ifPresent(dlcs::add);
        }
        return new EmulatorConfig(read(dir, "force_account_name.txt"), read(dir, "force_steamid.txt"),
                read(dir, "force_language.txt"), Files.exists(dir.resolve("offline.txt")), false, dlcs);
    }

    private void saveGoldberg(Path dir) throws IOException {
        write(dir, "force_account_name.txt", username);
        write(dir, "force_steamid.txt", steamId);
        write(dir, "force_language.txt", language);
        flag(dir, "offline.txt", offline);
        StringBuilder sb = new StringBuilder();
        for (Dlc d : dlcs) sb.append(d.appId()).append('=').append(d.name()).append('\n');
        write(dir, "DLC.txt", sb.toString());
    }

    // gbe_fork: configs.user.ini, configs.app.ini, configs.main.ini

    private static EmulatorConfig loadGbe(Path dir) throws IOException {
        Map<String, Map<String, String>> user = Ini.read(dir.resolve("configs.user.ini"));
        Map<String, Map<String, String>> app = Ini.read(dir.resolve("configs.app.ini"));
        Map<String, Map<String, String>> main = Ini.read(dir.resolve("configs.main.ini"));

        Map<String, String> general = user.getOrDefault("user::general", Map.of());
        Map<String, String> dlcSection = app.getOrDefault("app::dlcs", Map.of());
        List<Dlc> dlcs = new ArrayList<>();
        dlcSection.forEach((k, v) -> { if (k.matches("\\d+")) dlcs.add(new Dlc(k, v)); });

        return new EmulatorConfig(general.getOrDefault("account_name", ""), general.getOrDefault("account_steamid", ""),
                general.getOrDefault("language", ""),
                "1".equals(main.getOrDefault("main::connectivity", Map.of()).get("offline")),
                "1".equals(dlcSection.get("unlock_all")), dlcs);
    }

    private void saveGbe(Path dir) throws IOException {
        Ini.update(dir.resolve("configs.user.ini"), "user::general", Map.of(
                "account_name", username, "account_steamid", steamId, "language", language));
        Ini.update(dir.resolve("configs.main.ini"), "main::connectivity", Map.of("offline", offline ? "1" : "0"));

        Map<String, String> dlcSection = new LinkedHashMap<>();
        dlcSection.put("unlock_all", unlockAllDlc ? "1" : "0");
        for (Dlc d : dlcs) dlcSection.put(d.appId(), d.name());
        Ini.replaceSection(dir.resolve("configs.app.ini"), "app::dlcs", dlcSection);
    }

    static Optional<Dlc> parseDlc(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) return Optional.empty();
        String id = line.substring(0, eq).trim();
        return id.matches("\\d+") ? Optional.of(new Dlc(id, line.substring(eq + 1).trim())) : Optional.empty();
    }

    private static String read(Path dir, String file) throws IOException {
        Path p = dir.resolve(file);
        return Files.isRegularFile(p) ? Files.readString(p).trim() : "";
    }

    private static void write(Path dir, String file, String value) throws IOException {
        Path p = dir.resolve(file);
        if (value == null || value.isBlank()) Files.deleteIfExists(p);
        else Files.writeString(p, value.strip() + (value.contains("\n") ? "\n" : ""));
    }

    private static void flag(Path dir, String file, boolean on) throws IOException {
        Path p = dir.resolve(file);
        if (on) { if (!Files.exists(p)) Files.createFile(p); }
        else Files.deleteIfExists(p);
    }
}
