package com.javafxapp.javafxapp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppInfoTest {

    @Test
    void theVersionComesFromTheBuild() {
        // Gradle fills version.properties in from build.gradle, so a plain "dev" or a leftover placeholder means it broke
        assertTrue(AppInfo.version().matches("\\d+\\.\\d+(\\.\\d+)?.*"), "version was: " + AppInfo.version());
        assertFalse(AppInfo.version().contains("$"));
    }
}
