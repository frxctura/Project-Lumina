package com.javafxapp.javafxapp.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** What the running app knows about itself. The version comes from build.gradle, through version.properties. */
public final class AppInfo {

    private static final String VERSION = readVersion();

    private AppInfo() {}

    public static String version() {
        return VERSION;
    }

    private static String readVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/javafxapp/javafxapp/version.properties")) {
            if (in == null) return "dev";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "dev").trim();
            // run straight from an IDE without Gradle, nothing filled the placeholder in
            return v.isEmpty() || v.startsWith("$") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }
}
