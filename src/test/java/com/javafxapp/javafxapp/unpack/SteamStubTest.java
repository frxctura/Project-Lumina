package com.javafxapp.javafxapp.unpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SteamStubTest {

    @TempDir Path dir;

    /** A bare-bones PE file: DOS header, PE header, no optional header, one 40-byte entry per section name. */
    private static byte[] pe(String... sections) {
        int peOffset = 0x80;
        ByteBuffer b = ByteBuffer.allocate(peOffset + 24 + 40 * sections.length + 64).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short) 0x5A4D);
        b.putInt(0x3C, peOffset);
        b.putInt(peOffset, 0x00004550);
        b.putShort(peOffset + 6, (short) sections.length);
        b.putShort(peOffset + 20, (short) 0);
        for (int i = 0; i < sections.length; i++) {
            byte[] name = sections[i].getBytes(StandardCharsets.US_ASCII);
            b.position(peOffset + 24 + 40 * i).put(name);
        }
        return b.array();
    }

    @Test
    void detectsTheBindSection() throws IOException {
        Path wrapped = Files.write(dir.resolve("wrapped.exe"), pe(".text", ".data", ".bind"));
        Path plain = Files.write(dir.resolve("plain.exe"), pe(".text", ".data", ".rsrc"));
        assertTrue(SteamStub.isProtected(wrapped));
        assertFalse(SteamStub.isProtected(plain));
    }

    @Test
    void ignoresFilesThatAreNotExecutables() throws IOException {
        assertFalse(SteamStub.isProtected(Files.write(dir.resolve("empty.exe"), new byte[0])));
        assertFalse(SteamStub.isProtected(Files.write(dir.resolve("text.exe"), "not a program at all, just text padding".repeat(5).getBytes())));
        byte[] bad = pe(".bind");
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3C, Integer.MAX_VALUE); // PE header "beyond the file"
        assertFalse(SteamStub.isProtected(Files.write(dir.resolve("bad.exe"), bad)));
    }

    @Test
    void findsWrappedExecutablesInSubfoldersAndSkipsUnpackedOutput() throws IOException {
        Files.createDirectories(dir.resolve("bin"));
        Files.write(dir.resolve("bin").resolve("game.exe"), pe(".text", ".bind"));
        Files.write(dir.resolve("tool.exe"), pe(".text"));
        Files.write(dir.resolve("game.exe.unpacked.exe"), pe(".text", ".bind"));
        assertEquals(List.of(dir.resolve("bin").resolve("game.exe")), SteamStub.find(dir));
    }
}
