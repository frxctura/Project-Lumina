package com.javafxapp.javafxapp.common;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileOps {

    private static final int BUFFER = 1 << 20;

    private FileOps() {}

    public static long totalSize(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(FileOps::sizeOrZero).sum();
        }
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        copyDirectory(source, target, Progress.NONE);
    }

    public static void copyDirectory(Path source, Path target, Progress progress) throws IOException {
        long total = progress == Progress.NONE ? 0 : totalSize(source);
        long[] done = {0};
        byte[] buffer = new byte[BUFFER];

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(dest)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
                        out.write(buffer, 0, n);
                        done[0] += n;
                        progress.update(done[0], total);
                    }
                }
                Files.setLastModifiedTime(dest, attrs.lastModifiedTime());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // rename can't cross drives, so fall back to copy and delete
    public static void moveDirectory(Path source, Path target, Progress progress) throws IOException {
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException e) {
            try {
                copyDirectory(source, target, progress);
            } catch (IOException copyFailed) {
                deleteQuietly(target);
                throw copyFailed;
            }
            deleteRecursive(source);
        }
    }

    public static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    public static void deleteQuietly(Path path) {
        try {
            deleteRecursive(path);
        } catch (IOException e) {
            Log.error("Could not clean up " + path, e);
        }
    }

    public static List<Path> findFiles(Path root, Predicate<String> lowerCaseName) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> lowerCaseName.test(p.getFileName().toString().toLowerCase()))
                    .collect(Collectors.toList());
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = bytes;
        int unit = -1;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format("%.2f %s", size, units[unit]);
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
