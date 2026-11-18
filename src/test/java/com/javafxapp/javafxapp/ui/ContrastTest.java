package com.javafxapp.javafxapp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads the color tokens out of styles.css and checks every pairing the app actually draws against WCAG 2.2 AA:
 * 4.5:1 for text, 3:1 for the edges of controls and for the focus ring. If someone changes a color and it drops below,
 * this says which pair and by how much.
 */
class ContrastTest {

    private static final double TEXT = 4.5, UI = 3.0;

    /** One theme's tokens, with references (-focus-ring: -accent-text) resolved and tints kept as rgba. */
    private record Theme(String name, Map<String, double[]> colors) {
        double[] get(String token) {
            double[] c = colors.get(token);
            assertNotNull(c, name + " has no " + token);
            return c;
        }

        /** A semi-transparent token as it looks painted over {@code under}. */
        double[] over(String token, String under) {
            double[] t = get(token), u = get(under);
            double a = t[3];
            return new double[]{t[0] * a + u[0] * (1 - a), t[1] * a + u[1] * (1 - a), t[2] * a + u[2] * (1 - a), 1};
        }
    }

    private static List<Theme> themes() throws IOException {
        String css;
        try (InputStream in = ContrastTest.class.getResourceAsStream("/com/javafxapp/javafxapp/styles/styles.css")) {
            assertNotNull(in, "styles.css is on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, double[]> dark = block(css, "\\.app-root \\{", new HashMap<>());
        Map<String, double[]> light = block(css, "\\.app-root\\.light \\{", new HashMap<>(dark));
        return List.of(new Theme("dark", dark), new Theme("light", light));
    }

    private static Map<String, double[]> block(String css, String selector, Map<String, double[]> into) {
        Matcher m = Pattern.compile(selector + "(.*?)\\}", Pattern.DOTALL).matcher(css);
        assertTrue(m.find(), "no " + selector + " block in styles.css");
        Map<String, String> refs = new HashMap<>();
        Matcher line = Pattern.compile("(-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(m.group(1));
        while (line.find()) {
            String name = line.group(1), value = line.group(2).trim();
            if (value.startsWith("-")) refs.put(name, value);
            else into.put(name, parse(value));
        }
        refs.forEach((name, target) -> into.put(name, into.get(target)));
        return into;
    }

    private static double[] parse(String value) {
        if (value.startsWith("#")) {
            int rgb = Integer.parseInt(value.substring(1), 16);
            return new double[]{rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 1};
        }
        Matcher m = Pattern.compile("rgba\\(\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*([\\d.]+)\\s*\\)").matcher(value);
        assertTrue(m.matches(), "can't read color " + value);
        return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                Double.parseDouble(m.group(4))};
    }

    static double ratio(double[] a, double[] b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(double[] c) {
        double[] l = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = c[i] / 255;
            l[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    }

    @Test
    void theRatioFormulaMatchesWcag() {
        assertEquals(21.0, ratio(new double[]{0, 0, 0, 1}, new double[]{255, 255, 255, 1}), 0.01);
        assertEquals(4.54, ratio(new double[]{118, 118, 118, 1}, new double[]{255, 255, 255, 1}), 0.01); // #767676 is the classic 4.5:1 grey
    }

    @Test
    void everyTextColorIsReadableWhereItIsUsed() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Theme t : themes()) {
            String[] surfaces = {"-bg", "-surface", "-surface-2", "-surface-3", "-status"};
            for (String s : surfaces) {
                check(failures, t, "-text", t.get(s), s, TEXT);
                check(failures, t, "-text-dim", t.get(s), s, TEXT);
                check(failures, t, "-text-faint", t.get(s), s, TEXT);
                check(failures, t, "-accent-text", t.get(s), s, TEXT);
            }
            check(failures, t, "-text", t.get("-control"), "-control", TEXT);
            check(failures, t, "-text-dim", t.get("-control"), "-control", TEXT);
            check(failures, t, "-text-faint", t.get("-control"), "-control (placeholders)", TEXT);
            check(failures, t, "-on-accent", t.get("-accent"), "-accent", TEXT);
            check(failures, t, "-on-accent", t.get("-accent-hover"), "-accent-hover", TEXT);
            check(failures, t, "-on-accent", t.get("-danger-fill"), "-danger-fill", TEXT);
            check(failures, t, "-on-accent", t.get("-danger-fill-hover"), "-danger-fill-hover", TEXT);
            check(failures, t, "-on-accent", t.get("-success-fill"), "-success-fill", TEXT);
            check(failures, t, "-on-success", t.get("-success"), "-success (done step)", TEXT);
            check(failures, t, "-on-danger", t.get("-danger"), "-danger (failed step)", TEXT);
            check(failures, t, "-log-text", t.get("-log-bg"), "-log-bg", TEXT);
            check(failures, t, "-danger", t.get("-log-bg"), "-log-bg (log errors)", TEXT);
            for (String s : new String[]{"-surface", "-surface-2"}) {
                check(failures, t, "-danger", t.get(s), s + " (missing paths)", TEXT);
                // badges: colored text on a see-through tint of the same color
                check(failures, t, "-success", t.over("-success-tint", s), "-success-tint over " + s, TEXT);
                check(failures, t, "-warn", t.over("-warn-tint", s), "-warn-tint over " + s, TEXT);
                check(failures, t, "-danger", t.over("-danger-tint", s), "-danger-tint over " + s, TEXT);
                check(failures, t, "-accent-text", t.over("-accent-tint", s), "-accent-tint over " + s, TEXT);
                check(failures, t, "-text-dim", t.over("-tint", s), "-tint over " + s, TEXT);
            }
        }
        assertTrue(failures.isEmpty(), "Text below 4.5:1:\n" + String.join("\n", failures));
    }

    @Test
    void controlEdgesAndTheFocusRingStandOut() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Theme t : themes()) {
            for (String s : new String[]{"-bg", "-surface", "-surface-2", "-surface-3"}) {
                check(failures, t, "-control-stroke", t.get(s), s + " (field and checkbox edges)", UI);
                check(failures, t, "-focus-ring", t.get(s), s + " (focus ring)", UI);
                check(failures, t, "-scroll-thumb", t.get(s), s + " (scroll bar)", UI);
            }
            check(failures, t, "-focus-ring", t.get("-control"), "-control (focus ring on a field)", UI);
        }
        assertTrue(failures.isEmpty(), "UI parts below 3:1:\n" + String.join("\n", failures));
    }

    private static void check(List<String> failures, Theme t, String fg, double[] bg, String bgName, double need) {
        double r = ratio(t.get(fg), bg);
        if (r < need) failures.add(String.format("  %s: %s on %s is %.2f:1, needs %.1f:1", t.name(), fg, bgName, r, need));
    }
}
