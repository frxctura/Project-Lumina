package com.javafxapp.javafxapp.emulator;

import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.patching.SteamApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Older games ask Steam for interfaces by version name (SteamUser021, STEAMAPPS_INTERFACE_VERSION008...) and an
 * emulator can only answer with the exact versions the game was built against. Those names are written into the
 * game's own steam_api DLL, so reading them from the original DLL gives the list the emulator wants in
 * {@code steam_settings/steam_interfaces.txt}. This is what gbe_fork's generate_interfaces tool does.
 */
public final class SteamInterfaces {

    public static final String FILE = "steam_interfaces.txt";

    // the same names, in the same order, as gbe_fork's generate_interfaces
    private static final List<Pattern> PATTERNS = List.of(
            "STEAMAPPS_INTERFACE_VERSION\\d+", "SteamApps\\d+", "STEAMAPPLIST_INTERFACE_VERSION\\d+",
            "STEAMAPPTICKET_INTERFACE_VERSION\\d+", "SteamClient\\d+", "STEAMCONTROLLER_INTERFACE_VERSION",
            "SteamController\\d+", "SteamFriends\\d+", "SteamGameServerStats\\d+", "SteamGameCoordinator\\d+",
            "SteamGameServer\\d+", "STEAMHTMLSURFACE_INTERFACE_VERSION_\\d+", "STEAMHTTP_INTERFACE_VERSION\\d+",
            "SteamInput\\d+", "STEAMINVENTORY_INTERFACE_V\\d+", "SteamMatchMakingServers\\d+", "SteamMatchMaking\\d+",
            "SteamMatchGameSearch\\d+", "SteamParties\\d+", "STEAMMUSIC_INTERFACE_VERSION\\d+",
            "STEAMMUSICREMOTE_INTERFACE_VERSION\\d+", "SteamNetworkingMessages\\d+", "SteamNetworkingSockets\\d+",
            "SteamNetworkingUtils\\d+", "SteamNetworking\\d+", "STEAMPARENTALSETTINGS_INTERFACE_VERSION\\d+",
            "STEAMREMOTEPLAY_INTERFACE_VERSION\\d+", "STEAMREMOTESTORAGE_INTERFACE_VERSION\\d+",
            "STEAMSCREENSHOTS_INTERFACE_VERSION\\d+", "STEAMTIMELINE_INTERFACE_V\\d+", "STEAMUGC_INTERFACE_VERSION\\d+",
            "SteamUser\\d+", "STEAMUSERSTATS_INTERFACE_VERSION\\d+", "SteamUtils\\d+", "STEAMVIDEO_INTERFACE_V\\d+",
            "STEAMUNIFIEDMESSAGES_INTERFACE_VERSION\\d+", "SteamMasterServerUpdater\\d+"
    ).stream().map(Pattern::compile).toList();

    private SteamInterfaces() {}

    /** The interface names in {@code binary}, grouped by interface and without repeats. */
    public static List<String> scan(byte[] binary) {
        // one byte per character keeps every match's text exactly as it is in the file
        String text = new String(binary, StandardCharsets.ISO_8859_1);
        List<String> found = new ArrayList<>();
        for (Pattern pattern : PATTERNS) {
            Set<String> matches = new LinkedHashSet<>();
            Matcher m = pattern.matcher(text);
            while (m.find()) matches.add(m.group());
            // when a DLL carries several SteamClient versions, 017 is the one the emulators expect
            if (pattern.pattern().equals("SteamClient\\d+") && matches.size() > 1 && matches.contains("SteamClient017"))
                matches = new LinkedHashSet<>(List.of("SteamClient017"));
            found.addAll(matches);
        }
        return found;
    }

    public static List<String> scan(Path dll) throws IOException {
        return scan(Files.readAllBytes(dll));
    }

    /** The DLL that still has the game's own interface names: the kept original, or the DLL itself if it's untouched. */
    public static Optional<Path> originalOf(Path dll) {
        Path bak = SteamApi.backupOf(dll);
        if (Files.isRegularFile(bak)) return Optional.of(bak);
        Path proxied = SteamApi.proxyBackupOf(dll);
        if (Files.isRegularFile(proxied)) return Optional.of(proxied);
        return PatchDetector.isEmulatorDll(dll) ? Optional.empty() : Optional.of(dll);
    }

    public static Path file(Path settingsDir) {
        return settingsDir.resolve(FILE);
    }

    /** How many interfaces the file in {@code settingsDir} lists, or empty if there is none. */
    public static Optional<Integer> existing(Path settingsDir) {
        try {
            if (!Files.isRegularFile(file(settingsDir))) return Optional.empty();
            return Optional.of((int) Files.readAllLines(file(settingsDir), StandardCharsets.ISO_8859_1).stream()
                    .filter(l -> !l.isBlank()).count());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void remove(Path settingsDir) throws IOException {
        Files.deleteIfExists(file(settingsDir));
    }

    /**
     * Writes {@code steam_interfaces.txt} into {@code settingsDir}, from the game's steam_api DLL(s) next to that folder.
     *
     * @return the interface names written
     * @throws IOException when there is no original DLL to read, or it names no interfaces at all
     */
    public static List<String> generate(Path settingsDir) throws IOException {
        Path gameDir = settingsDir.toAbsolutePath().getParent();
        Set<String> names = new LinkedHashSet<>();
        boolean hadDll = false;
        for (String dllName : SteamApi.DLL_NAMES) {
            Path dll = gameDir.resolve(dllName);
            if (!Files.isRegularFile(dll)) continue;
            hadDll = true;
            Optional<Path> original = originalOf(dll);
            if (original.isPresent()) names.addAll(scan(original.get()));
        }
        if (!hadDll) throw new IOException("There is no steam_api DLL next to " + settingsDir.getFileName() + ".");
        if (names.isEmpty() && hasOriginal(gameDir))
            throw new IOException("The game's steam_api DLL doesn't name any interfaces, so this game doesn't need the file.");
        if (names.isEmpty())
            throw new IOException("The original steam_api DLL isn't there any more, only the emulator's. Restore the originals"
                    + " first, or keep .bak backups when patching, then try again.");

        List<String> several = withSeveralVersions(names);
        if (!several.isEmpty())
            throw new IOException("This steam_api DLL lists several versions of the same interface (" + several.get(0)
                    + (several.size() > 1 ? " and " + (several.size() - 1) + " more" : "")
                    + "), so it carries every version and can't tell which ones the game uses. A file made from it would be wrong,"
                    + " so none was written.");

        Files.createDirectories(settingsDir);
        Files.writeString(file(settingsDir), String.join("\r\n", names) + "\r\n", StandardCharsets.ISO_8859_1);
        return List.copyOf(names);
    }

    /**
     * Interfaces that appear in more than one version. A DLL built for one SDK names one version of each, so several
     * means it ships the whole table (some engines' plug-in DLLs do), and the names say nothing about the game.
     * SteamClient is left out: DLLs do carry a few of those, and {@link #scan} already picks one.
     */
    static List<String> withSeveralVersions(Collection<String> names) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (String name : names) {
            String interfaceName = name.replaceFirst("\\d+$", "");
            if (!interfaceName.equals("SteamClient")) versions.merge(interfaceName, 1, Integer::sum);
        }
        return versions.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
    }

    private static boolean hasOriginal(Path gameDir) {
        return SteamApi.DLL_NAMES.stream().map(gameDir::resolve).filter(Files::isRegularFile)
                .anyMatch(d -> originalOf(d).isPresent());
    }
}
