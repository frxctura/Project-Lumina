package com.javafxapp.javafxapp.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorsTest {

    @Test
    void neverShowsTheWordNull() {
        String npe = Errors.describe(new NullPointerException());
        assertTrue(npe.startsWith("An unexpected error happened inside Lumina (NullPointerException)."), npe);
        assertTrue(npe.contains("Copy diagnostics"), "says what to do about it: " + npe);
        assertFalse(Errors.describe(new IllegalStateException((String) null)).contains("null"));
        assertFalse(Errors.describe(new IOException("  ")).isBlank());
    }

    @Test
    void usesTheCauseWhenThereIsNoMessage() {
        Exception wrapped = new Exception(null, new AccessDeniedException("C:\\Games\\x.dll"));
        assertTrue(Errors.describe(wrapped).startsWith("Access denied"), Errors.describe(wrapped));
    }

    @Test
    void keepsTheUsualWording() {
        assertTrue(Errors.describe(new AccessDeniedException("a.dll")).startsWith("Access denied: a.dll"));
        assertEquals("Not found: b.dll", Errors.describe(new java.nio.file.NoSuchFileException("b.dll")));
        assertEquals("plain message", Errors.describe(new IOException("plain message")));
        assertTrue(Errors.describe(new IOException("Operation did not complete successfully because the file contains a virus")).startsWith("Blocked by antivirus"));
    }

    @Test
    void rateLimitGetsAnExplanation() {
        assertTrue(Http.failure(403, "0", "https://api.github.com/x").getMessage().contains("too many requests"));
        assertTrue(Http.failure(429, "", "https://example.com").getMessage().contains("too many requests"));
        assertEquals("HTTP 403 for https://example.com", Http.failure(403, "12", "https://example.com").getMessage(),
                "a 403 that isn't about the limit stays a plain 403");
        assertEquals("HTTP 404 for https://example.com", Http.failure(404, "", "https://example.com").getMessage());
    }
}
