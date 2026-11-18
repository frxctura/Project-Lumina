package com.javafxapp.javafxapp.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class ProcessesTest {

    @Test
    void capturesOutputAndExitCode() throws Exception {
        Processes.Result ok = Processes.run(new ProcessBuilder("cmd.exe", "/c", "echo hello"), Duration.ofSeconds(20));
        assertTrue(ok.succeeded());
        assertEquals("hello", ok.output());

        Processes.Result failed = Processes.run(new ProcessBuilder("cmd.exe", "/c", "echo oops & exit 3"), Duration.ofSeconds(20));
        assertFalse(failed.succeeded());
        assertEquals(3, failed.exitCode());
        assertEquals("oops", failed.output());
    }

    @Test
    void stopsAProgramThatNeverFinishes() throws Exception {
        long start = System.nanoTime();
        // prints, then waits half a minute: reading its output "until the end" would have waited all of that
        Processes.Result result = Processes.run(
                new ProcessBuilder("cmd.exe", "/c", "echo started & ping -n 30 127.0.0.1 >nul"), Duration.ofMillis(700));
        double seconds = (System.nanoTime() - start) / 1e9;

        assertTrue(result.timedOut());
        assertFalse(result.succeeded());
        assertTrue(seconds < 10, "gave up after " + seconds + "s");
        assertTrue(result.output().contains("started"), "what it printed before is kept: " + result.output());
    }

    @Test
    void reportsAProgramThatCannotStart() {
        assertThrows(java.io.IOException.class,
                () -> Processes.run(new ProcessBuilder("definitely-not-a-program.exe"), Duration.ofSeconds(5)));
    }
}
