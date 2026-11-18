package com.javafxapp.javafxapp.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for small config files. Objects become {@code LinkedHashMap} (keeping their order),
 * arrays {@code List}, numbers {@code Long} or {@code Double}, plus {@code String}, {@code Boolean} and null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String text) {
        this.s = text;
    }

    public static Object parse(String text) throws IOException {
        Json p = new Json(text);
        p.skip();
        Object value = p.value();
        p.skip();
        if (p.i != text.length()) throw p.error("Unexpected text after the JSON value");
        return value;
    }

    /** Parses an object, or throws when the text holds anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws IOException {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IOException("Expected a JSON object");
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    // Reading

    private Object value() throws IOException {
        if (i >= s.length()) throw error("Unexpected end of JSON");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skip();
        if (peek('}')) { i++; return map; }
        while (true) {
            skip();
            if (!peek('"')) throw error("Expected a property name");
            String key = string();
            skip();
            expect(':');
            skip();
            map.put(key, value());
            skip();
            if (peek(',')) { i++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() throws IOException {
        List<Object> list = new ArrayList<>();
        i++;
        skip();
        if (peek(']')) { i++; return list; }
        while (true) {
            skip();
            list.add(value());
            skip();
            if (peek(',')) { i++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() throws IOException {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("Bad \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad \\u escape");
                    }
                    i += 4;
                }
                default -> throw error("Bad escape \\" + e);
            }
        }
        throw error("Unterminated string");
    }

    private Object number() throws IOException {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String text = s.substring(start, i);
        try {
            return text.matches("-?\\d+") ? (Object) Long.parseLong(text) : (Object) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            i = start;
            throw error("Unexpected character");
        }
    }

    private Object literal(String word, Object result) throws IOException {
        if (!s.startsWith(word, i)) throw error("Unexpected character");
        i += word.length();
        return result;
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) throws IOException {
        if (!peek(c)) throw error("Expected '" + c + "'");
        i++;
    }

    private IOException error(String message) {
        return new IOException(message + " at position " + i);
    }

    // Writing

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder out, int depth) {
        if (v == null) out.append("null");
        else if (v instanceof String str) quote(str, out);
        else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) { out.append("{}"); return; }
            out.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                indent(out, depth + 1);
                quote(e.getKey(), out);
                out.append(": ");
                write(e.getValue(), out, depth + 1);
                out.append(++n < map.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) { out.append("[]"); return; }
            out.append("[\n");
            for (int n = 0; n < list.size(); n++) {
                indent(out, depth + 1);
                write(list.get(n), out, depth + 1);
                out.append(n + 1 < list.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else out.append(v); // Boolean, Long, Double
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
