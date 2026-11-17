package com.javafxapp.javafxapp.common;

import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public abstract class Job<T> extends Task<T> {

    protected final StatusSink log;

    protected Job(StatusSink log) {
        this.log = log;
    }

    public abstract List<String> steps();

    protected void step(String step, String text) {
        updateTitle(step);
        updateMessage(text);
        log.info(text);
    }

    protected void checkCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
    }

    protected static void requireDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Directory no longer exists: " + dir);
    }

    protected Progress slice(double from, double to, String label) {
        long start = System.nanoTime();
        long[] lastUpdate = {0};
        return (done, total) -> {
            long now = System.nanoTime();
            if (now - lastUpdate[0] < 80_000_000L && done < total) return;
            lastUpdate[0] = now;

            double seconds = Math.max((now - start) / 1e9, 0.001);
            String speed = FileOps.formatSize((long) (done / seconds)) + "/s";
            if (total > 0) {
                updateProgress(from + (to - from) * Math.min(1, (double) done / total), 1);
                updateMessage(label + "  " + FileOps.formatSize(done) + " of " + FileOps.formatSize(total) + "  ·  " + speed);
            } else {
                updateProgress(-1, 1);
                updateMessage(label + "  " + FileOps.formatSize(done) + "  ·  " + speed);
            }
        };
    }
}
