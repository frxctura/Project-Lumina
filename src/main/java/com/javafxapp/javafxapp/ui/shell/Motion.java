package com.javafxapp.javafxapp.ui.shell;

import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.common.Processes;
import com.javafxapp.javafxapp.config.AppSettings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Whether to animate. People who get dizzy or distracted by motion turn off "Animation effects" in Windows
 * (Settings, Accessibility, Visual effects), and Lumina follows that unless the user picked something in Settings.
 * When reduced, things still fade (the fade tells you something changed) but nothing slides, pops, pulses or counts up.
 */
public final class Motion {

    // JavaFX 17 can't read the Windows setting (24+ can), so ask the same API Windows uses, through PowerShell
    private static final String QUERY = "Add-Type -Namespace LuminaMotion -Name U -MemberDefinition "
            + "'[DllImport(\"user32.dll\")] public static extern bool SystemParametersInfo(int a, int b, ref bool c, int d);'; "
            + "$v = $false; [void][LuminaMotion.U]::SystemParametersInfo(0x1042, 0, [ref]$v, 0); $v";

    private static volatile boolean windowsAnimates = true;

    private Motion() {}

    public static boolean reduced() {
        return switch (AppSettings.getMotion()) {
            case FULL -> false;
            case REDUCED -> true;
            case SYSTEM -> !windowsAnimates;
        };
    }

    /** What Windows says, for the hint in Settings. */
    public static boolean windowsAnimates() {
        return windowsAnimates;
    }

    /** Asks Windows once, in the background. Until it answers, animations stay on. */
    public static void readWindowsSetting() {
        Thread t = new Thread(() -> {
            try {
                String encoded = Base64.getEncoder().encodeToString(QUERY.getBytes(StandardCharsets.UTF_16LE));
                Processes.Result r = Processes.run(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-EncodedCommand", encoded), Duration.ofSeconds(15));
                if (r.succeeded()) windowsAnimates = parse(r.output());
            } catch (Exception e) {
                Log.error("Could not read the Windows animation setting", e);
            }
        }, "motion-setting");
        t.setDaemon(true);
        t.start();
    }

    /** PowerShell prints True or False; anything else keeps animations on. */
    static boolean parse(String output) {
        return !output.strip().equalsIgnoreCase("False");
    }
}
