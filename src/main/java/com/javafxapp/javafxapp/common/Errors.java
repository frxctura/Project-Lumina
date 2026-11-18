package com.javafxapp.javafxapp.common;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

public final class Errors {

    private Errors() {}

    public static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            // a NullPointerException and the like have no message, and "null" tells nobody anything
            Throwable cause = t.getCause();
            return cause != null && cause != t ? describe(cause) : "An unexpected error happened inside Lumina (" + t.getClass().getSimpleName() + "). "
                    + "Settings has a Copy diagnostics button, paste that into an issue on GitHub so it can be fixed.";
        }
        if (msg.toLowerCase().contains("virus") || msg.toLowerCase().contains("unwanted software"))
            return "Blocked by antivirus. Add an exclusion for the Lumina folder and the game directory.";
        if (t instanceof AccessDeniedException)
            return "Access denied: " + msg + " (is the game running, or does the folder need admin rights?)";
        if (t instanceof FileAlreadyExistsException)
            return "Already exists: " + msg;
        if (t instanceof NoSuchFileException)
            return "Not found: " + msg;
        if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException)
            return "Could not connect. Check your internet connection.";
        return msg;
    }
}
