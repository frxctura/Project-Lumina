package com.javafxapp.javafxapp.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void readsNestedValues() throws IOException {
        Map<String, Object> root = Json.parseObject("""
                {"$version": 4, "logging": false, "name": "a \\"b\\" \\u0041",
                 "list": [1, 2.5, null, true], "extra": {"480": {"dlcs": {"1": "x"}}}}""");
        assertEquals(4L, root.get("$version"));
        assertEquals(Boolean.FALSE, root.get("logging"));
        assertEquals("a \"b\" A", root.get("name"));
        assertEquals(List.of(1L, 2.5, true), ((List<?>) root.get("list")).stream().filter(v -> v != null).toList());
        assertEquals("x", ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("extra")).get("480")).get("dlcs")).get("1"));
    }

    @Test
    void writeThenReadKeepsEverythingInOrder() throws IOException {
        Map<String, Object> root = Json.parseObject("{\"b\": 1, \"a\": {\"z\": [], \"y\": {}}, \"s\": \"line\\nbreak\"}");
        Map<String, Object> again = Json.parseObject(Json.write(root));
        assertEquals(root, again);
        assertEquals(List.of("b", "a", "s"), List.copyOf(again.keySet()));
    }

    @Test
    void rejectsBrokenJson() {
        assertThrows(IOException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IOException.class, () -> Json.parse("{\"a\": 1"));
        assertThrows(IOException.class, () -> Json.parse("[1] x"));
        assertThrows(IOException.class, () -> Json.parseObject("[1]"));
    }
}
