package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.common.Http;
import com.javafxapp.javafxapp.common.Progress;
import com.javafxapp.javafxapp.config.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class CoverCache {

    private CoverCache() {}

    static Path dir() {
        return AppPaths.home().resolve("covers");
    }

    static Path file(String appId) {
        return dir().resolve(appId + ".jpg");
    }

    public static Optional<Path> cached(String appId) {
        if (!SteamStore.isValidAppId(appId)) return Optional.empty();
        Path file = file(appId);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public static Path fetch(String appId, String url) throws IOException, InterruptedException {
        Optional<Path> cached = cached(appId);
        if (cached.isPresent()) return cached.get();
        if (!SteamStore.isValidAppId(appId)) throw new IOException("Invalid app id: " + appId);
        Files.createDirectories(dir());
        Path part = Files.createTempFile(dir(), appId, ".part");
        try {
            Http.download(url, part, Progress.NONE);
            return Files.move(part, file(appId), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
