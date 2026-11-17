package com.javafxapp.javafxapp.library;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class Vdf {

    private enum Kind { STRING, OPEN, CLOSE }

    private record Token(Kind kind, String text) {}

    private final String text;
    private int pos;

    private Vdf(String text) {
        this.text = text;
    }

    public static Map<String, Object> parse(String text) {
        return new Vdf(text).readObject();
    }

    public static Optional<Map<String, Object>> object(Map<String, Object> map, String key) {
        return map.get(key) instanceof Map<?, ?> m ? Optional.of(cast(m)) : Optional.empty();
    }

    public static Optional<String> string(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        while (true) {
            Token key = next();
            if (key == null || key.kind() == Kind.CLOSE) return map;
            if (key.kind() == Kind.OPEN) {
                readObject(); // value without a key: skip it
                continue;
            }
            Token value = next();
            if (value == null || value.kind() == Kind.CLOSE) return map;
            map.putIfAbsent(key.text(), value.kind() == Kind.OPEN ? readObject() : value.text());
        }
    }

    private Token next() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (text.startsWith("//", pos)) {
                while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            } else if (c == '{' || c == '}') {
                pos++;
                return new Token(c == '{' ? Kind.OPEN : Kind.CLOSE, null);
            } else if (c == '"') {
                return new Token(Kind.STRING, quoted());
            } else {
                String word = unquoted();
                if (!(word.startsWith("[") && word.endsWith("]"))) return new Token(Kind.STRING, word); // skip [$WIN32] conditionals
            }
        }
        return null;
    }

    private String quoted() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < text.length()) {
                char e = text.charAt(pos++);
                sb.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; default -> e; });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String unquoted() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c) || c == '{' || c == '}' || c == '"') break;
            pos++;
        }
        return text.substring(start, pos);
    }
}
