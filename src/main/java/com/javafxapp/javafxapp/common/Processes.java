package com.javafxapp.javafxapp.common;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Runs a helper program and waits for it for a limited time. */
public final class Processes {

    /** @param timedOut the program was still running when the time was up, and has been killed */
    public record Result(int exitCode, String output, boolean timedOut) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    private Processes() {}

    public static Result run(ProcessBuilder builder, Duration timeout) throws IOException, InterruptedException {
        return run(builder, timeout, StandardCharsets.UTF_8);
    }

    /**
     * Output goes to a file, not a pipe. Reading a pipe until it closes blocks for as long as the program runs, so a
     * program that hangs would also hang the reader and the timeout would never get a chance.
     */
    public static Result run(ProcessBuilder builder, Duration timeout, Charset charset) throws IOException, InterruptedException {
        Path out = Files.createTempFile("lumina-process-", ".txt");
        try {
            Process process = builder.redirectErrorStream(true).redirectOutput(out.toFile()).start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    return new Result(-1, read(out, charset), true);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                throw e;
            }
            return new Result(process.exitValue(), read(out, charset), false);
        } finally {
            try {
                Files.deleteIfExists(out);
            } catch (IOException e) {
                out.toFile().deleteOnExit(); // a program that was just killed can still hold the file for a moment
            }
        }
    }

    private static String read(Path file, Charset charset) throws IOException {
        return new String(Files.readAllBytes(file), charset).strip();
    }
}
