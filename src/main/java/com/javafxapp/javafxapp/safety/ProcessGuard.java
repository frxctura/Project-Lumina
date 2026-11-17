package com.javafxapp.javafxapp.safety;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ProcessGuard {

    public record RunningProcess(long pid, String name, Path executable) {}

    private ProcessGuard() {}

    public static List<RunningProcess> runningFrom(Path dir) {
        Path root = lower(dir);
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid())
                .flatMap(p -> p.info().command()
                        .filter(cmd -> lower(Path.of(cmd)).startsWith(root))
                        .map(cmd -> new RunningProcess(p.pid(), Path.of(cmd).getFileName().toString(), Path.of(cmd)))
                        .stream())
                .distinct()
                .toList();
    }

    private static Path lower(Path p) {
        return Path.of(p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT));
    }

    public static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
