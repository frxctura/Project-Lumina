package com.javafxapp.javafxapp.patching;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.emulator.Emulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PatchDetector {

    public enum State { NO_DLLS, ORIGINAL, PATCHED, MIXED }

    public record DllStatus(Path dll, Optional<String> emulator) {
        public boolean patched() { return emulator.isPresent(); }
    }

    public record Report(State state, List<DllStatus> dlls) {
        public Optional<String> emulator() {
            return dlls.stream().map(DllStatus::emulator).flatMap(Optional::stream).findFirst();
        }
    }

    private static final Map<String, Map<String, String>> EMULATOR_HASHES = new ConcurrentHashMap<>();

    private PatchDetector() {}

    public static Report inspect(List<Path> dlls) {
        Map<String, String> known = knownHashes();
        List<DllStatus> statuses = new ArrayList<>();
        for (Path dll : dlls) {
            String hash = hashOrNull(dll);
            statuses.add(new DllStatus(dll, Optional.ofNullable(hash == null ? null : known.get(hash))));
        }
        long patched = statuses.stream().filter(DllStatus::patched).count();
        State state = statuses.isEmpty() ? State.NO_DLLS
                : patched == 0 ? State.ORIGINAL
                : patched == statuses.size() ? State.PATCHED : State.MIXED;
        return new Report(state, statuses);
    }

    public static Report inspect(Path gameDir) throws IOException {
        return inspect(FileOps.findFiles(gameDir, SteamApi::isDll));
    }

    public static boolean sameContent(Path file, Path expected) throws IOException {
        return Files.size(file) == Files.size(expected) && sha256(file).equals(sha256(expected));
    }

    public static boolean isEmulatorDll(Path dll) {
        String hash = hashOrNull(dll);
        return hash != null && knownHashes().containsKey(hash);
    }

    private static Map<String, String> knownHashes() {
        Map<String, String> all = new HashMap<>();
        for (Emulator emulator : Emulator.values()) {
            String key = emulator.name() + "|" + emulator.installedVersion().orElse("");
            Map<String, String> hashes = EMULATOR_HASHES.computeIfAbsent(key, k -> {
                Map<String, String> m = new HashMap<>();
                for (String name : SteamApi.DLL_NAMES) {
                    String h = hashOrNull(emulator.dll(name));
                    if (h != null) m.put(h, emulator.toString());
                }
                return m;
            });
            all.putAll(hashes);
        }
        return all;
    }

    private static String hashOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? sha256(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
