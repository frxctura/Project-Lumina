package com.javafxapp.javafxapp.safety;

import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

public final class DefenderExclusion {

    private static final long TIMEOUT_MINUTES = 3;

    private DefenderExclusion() {}

    public static void add(List<Path> folders) throws IOException, InterruptedException {
        String paths = folders.stream()
                .map(f -> "'" + f.toAbsolutePath().normalize().toString().replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        String elevated = "try { Add-MpPreference -ExclusionPath " + paths + " -ErrorAction Stop; exit 0 } catch { exit 2 }";
        String outer = "try { $p = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                + "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + encode(elevated) + "'; "
                + "exit $p.ExitCode } catch { exit 1 }";

        Processes.Result result = Processes.run(
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encode(outer)),
                Duration.ofMinutes(TIMEOUT_MINUTES));
        if (result.timedOut()) throw new IOException("Timed out waiting for the administrator prompt.");
        switch (result.exitCode()) {
            case 0 -> Log.info("Added Defender exclusion for " + folders);
            case 1 -> throw new IOException("Administrator permission was not given, so no exclusion was added.");
            case 2 -> throw new IOException("Defender refused the exclusion. Another antivirus may be in charge, "
                    + "or Tamper Protection / a company policy blocks changes.");
            default -> throw new IOException("Adding the exclusion failed (code " + result.exitCode() + ").");
        }
    }

    // -EncodedCommand takes base64 utf-16le, which avoids the quoting mess
    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
}
