package com.javafxapp.javafxapp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The game folders Lumina was started with, by dropping them onto Lumina.exe or running it with their paths. */
public final class LaunchArgs {

    private LaunchArgs() {}

    /**
     * The arguments that are existing folders, once each and in the order given. Anything else is ignored: a flag, a
     * file, a path that no longer exists, or text that isn't a valid path at all.
     */
    public static List<Path> folders(List<String> arguments) {
        List<Path> folders = new ArrayList<>();
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) continue; // "" would otherwise become the current folder
            Path path;
            try {
                path = Path.of(argument.strip()).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                continue;
            }
            if (Files.isDirectory(path) && folders.stream().noneMatch(f -> sameFolder(f, path))) folders.add(path);
        }
        return folders;
    }

    private static boolean sameFolder(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.equals(b);
        }
    }
}
