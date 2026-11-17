package com.javafxapp.javafxapp.common;

import com.javafxapp.javafxapp.config.AppSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Log {

    private static final Logger LOGGER = LoggerFactory.getLogger("Lumina");

    private Log() {}

    public static void info(String message) {
        if (AppSettings.isLogToFile()) LOGGER.info(message);
    }

    public static void error(String message) {
        if (AppSettings.isLogToFile()) LOGGER.error(message);
    }

    public static void error(String message, Throwable t) {
        if (AppSettings.isLogToFile()) LOGGER.error(message, t);
    }
}
