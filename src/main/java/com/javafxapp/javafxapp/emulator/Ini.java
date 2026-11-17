package com.javafxapp.javafxapp.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// edits in place so keys and comments we don't know about survive
final class Ini {

    private Ini() {}

    static Map<String, Map<String, String>> read(Path file) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return sections;
        String section = "";
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) sections.computeIfAbsent(section, k -> new LinkedHashMap<>())
                    .put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return sections;
    }

    static void update(Path file, String section, Map<String, String> values) throws IOException {
        List<String> lines = Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>(values.keySet());
        int start = findSection(lines, section);
        if (start < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add("[" + section + "]");
            start = lines.size() - 1;
        }
        int end = sectionEnd(lines, start);
        for (int i = end - 1; i > start; i--) {
            String key = keyOf(lines.get(i));
            if (key == null || !values.containsKey(key)) continue;
            pending.remove(key);
            String v = values.get(key);
            if (v == null || v.isBlank()) lines.remove(i);
            else lines.set(i, key + "=" + v);
        }
        int insertAt = sectionEnd(lines, start);
        while (insertAt - 1 > start && lines.get(insertAt - 1).isBlank()) insertAt--;
        for (String key : pending) {
            String v = values.get(key);
            if (v != null && !v.isBlank()) lines.add(insertAt++, key + "=" + v);
        }
        Files.write(file, lines);
    }

    static void replaceSection(Path file, String section, Map<String, String> values) throws IOException {
        List<String> lines = Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        int start = findSection(lines, section);
        if (start >= 0) {
            int end = sectionEnd(lines, start);
            for (int i = end - 1; i > start; i--) if (keyOf(lines.get(i)) != null) lines.remove(i);
        }
        Files.write(file, lines);
        update(file, section, values);
    }

    private static int findSection(List<String> lines, String section) {
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).trim().equalsIgnoreCase("[" + section + "]")) return i;
        return -1;
    }

    private static int sectionEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) if (lines.get(i).trim().startsWith("[")) return i;
        return lines.size();
    }

    private static String keyOf(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith(";") || t.startsWith("[")) return null;
        int eq = t.indexOf('=');
        return eq > 0 ? t.substring(0, eq).trim() : null;
    }
}
