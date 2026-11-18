package com.javafxapp.javafxapp.ui.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotionTest {

    @Test
    void readsWhatPowerShellPrints() {
        assertTrue(Motion.parse("True"));
        assertFalse(Motion.parse("False\r\n"));
        assertFalse(Motion.parse("  false "));
        assertTrue(Motion.parse(""), "no answer: keep animating, that's the Windows default");
        assertTrue(Motion.parse("Add-Type : something broke"), "an error isn't a no");
    }
}
