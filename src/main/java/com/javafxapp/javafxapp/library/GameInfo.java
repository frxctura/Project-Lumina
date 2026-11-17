package com.javafxapp.javafxapp.library;

import com.javafxapp.javafxapp.patching.PatchDetector;
import com.javafxapp.javafxapp.patching.SteamApi;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public record GameInfo(Path path, long totalSize, long fileCount, Optional<Path> executable,
                       String lastModified, int dllCount, int backupCount, Optional<String> appId,
                       PatchDetector.Report patch) {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static GameInfo scan(Path dir) throws IOException {
        var acc = new Object() {
            long size, count, bestExeScore = Long.MIN_VALUE;
            int backups;
            final List<Path> dlls = new ArrayList<>();
            Path exe, appIdFile;
        };

        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                long fileSize = sizeOf(p);
                acc.size += fileSize;
                acc.count++;
                String name = p.getFileName().toString().toLowerCase();
                if (SteamApi.isDll(name)) acc.dlls.add(p);
                else if (SteamApi.isBackup(name)) acc.backups++;
                else if (name.equals(SteamApi.APPID_FILE) && acc.appIdFile == null) acc.appIdFile = p;
                else if (name.endsWith(".exe") && !isHelperExe(name)) {
                    // a big exe near the top is usually the game itself
                    long score = fileSize - (dir.relativize(p).getNameCount() - 1) * 50_000_000L;
                    if (score > acc.bestExeScore) {
                        acc.bestExeScore = score;
                        acc.exe = p;
                    }
                }
            });
        }

        String modified = DATE_FORMAT.format(Files.getLastModifiedTime(dir).toInstant());
        Optional<String> appId = Optional.ofNullable(acc.appIdFile).flatMap(SteamApi::readAppId);
        return new GameInfo(dir, acc.size, acc.count, Optional.ofNullable(acc.exe), modified, acc.dlls.size(), acc.backups, appId,
                PatchDetector.inspect(acc.dlls));
    }

    private static boolean isHelperExe(String name) {
        return Stream.of("unins", "crash", "redist", "setup", "vc_", "dxwebsetup", "launcherhelper", "ue4prereq")
                .anyMatch(name::contains);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    public static Image loadIcon(Path file) {
        try {
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(file.toFile(), 64, 64);
            if (icon == null || icon.getIconWidth() <= 0) return null;

            BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();

            WritableImage fx = new WritableImage(img.getWidth(), img.getHeight());
            PixelWriter pw = fx.getPixelWriter();
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++)
                    pw.setArgb(x, y, img.getRGB(x, y));
            return fx;
        } catch (Exception e) {
            return null;
        }
    }
}
