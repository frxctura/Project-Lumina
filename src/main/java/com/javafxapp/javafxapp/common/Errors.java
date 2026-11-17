package com.javafxapp.javafxapp.common;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

public final class Errors {

    private Errors() {}

    public static String describe(Throwable t) {
        String msg = String.valueOf(t.getMessage());
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
