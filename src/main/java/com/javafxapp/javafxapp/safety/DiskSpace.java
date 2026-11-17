package com.javafxapp.javafxapp.safety;

import com.javafxapp.javafxapp.common.FileOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiskSpace {

    private static final double MARGIN = 1.05;
    private static final long MIN_FREE = 256L * 1024 * 1024;

    private DiskSpace() {}

    public static long free(Path anyPathOnDrive) throws IOException {
        Path p = anyPathOnDrive.toAbsolutePath();
        while (p != null && !Files.exists(p)) p = p.getParent();
        if (p == null) throw new IOException("No existing directory for " + anyPathOnDrive);
        return Files.getFileStore(p).getUsableSpace();
    }

    public static boolean sameDrive(Path a, Path b) {
        Path ra = a.toAbsolutePath().getRoot(), rb = b.toAbsolutePath().getRoot();
        return ra != null && ra.equals(rb);
    }

    public static void require(long bytesNeeded, Path target, String what) throws IOException {
        long free = free(target);
        long needed = (long) (bytesNeeded * MARGIN) + MIN_FREE;
        if (free < needed) {
            throw new IOException("Not enough disk space for " + what + ": needs " + FileOps.formatSize(bytesNeeded)
                    + ", only " + FileOps.formatSize(free) + " free on " + target.toAbsolutePath().getRoot());
        }
    }
}
