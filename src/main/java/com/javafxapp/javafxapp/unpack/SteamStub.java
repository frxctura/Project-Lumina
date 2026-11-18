package com.javafxapp.javafxapp.unpack;

import com.javafxapp.javafxapp.common.FileOps;
import com.javafxapp.javafxapp.common.Log;
import com.javafxapp.javafxapp.patching.SteamApi;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds executables wrapped in Steam's SteamStub DRM. The wrapper adds a section named {@code .bind} to the
 * Windows executable, so reading the section table is enough: nothing is run and nothing is changed.
 */
public final class SteamStub {

    static final String UNPACKED_SUFFIX = ".unpacked.exe";
    private static final String BIND_SECTION = ".bind";

    private SteamStub() {}

    /** Every executable under {@code gameDir} that still carries the SteamStub wrapper. */
    public static List<Path> find(Path gameDir) throws IOException {
        return FileOps.findFiles(gameDir, name -> name.endsWith(".exe") && !name.endsWith(UNPACKED_SUFFIX)).stream()
                .filter(SteamStub::isProtected)
                .toList();
    }

    public static boolean isProtected(Path exe) {
        try (RandomAccessFile file = new RandomAccessFile(exe.toFile(), "r")) {
            return hasSection(file, BIND_SECTION);
        } catch (IOException | RuntimeException e) {
            Log.error("Could not read the PE header of " + exe, e);
            return false;
        }
    }

    private static boolean hasSection(RandomAccessFile file, String name) throws IOException {
        if (file.length() < 0x40) return false;
        ByteBuffer dos = read(file, 0, 0x40);
        if (dos.getShort(0) != 0x5A4D) return false; // "MZ"
        long peOffset = Integer.toUnsignedLong(dos.getInt(0x3C));
        if (peOffset < 0x40 || peOffset + 24 > file.length()) return false;

        ByteBuffer pe = read(file, peOffset, 24);
        if (pe.getInt(0) != 0x00004550) return false; // "PE\0\0"
        int sections = Short.toUnsignedInt(pe.getShort(6));
        int optionalSize = Short.toUnsignedInt(pe.getShort(20));
        if (sections == 0 || sections > 96) return false;

        long table = peOffset + 24 + optionalSize;
        if (table + 40L * sections > file.length()) return false;
        ByteBuffer headers = read(file, table, 40 * sections);
        byte[] raw = new byte[8];
        for (int i = 0; i < sections; i++) {
            headers.position(i * 40).get(raw);
            int end = 0;
            while (end < raw.length && raw[end] != 0) end++;
            if (name.equals(new String(raw, 0, end, StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }

    private static ByteBuffer read(RandomAccessFile file, long position, int length) throws IOException {
        byte[] bytes = new byte[length];
        file.seek(position);
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Where the original of an unpacked executable is kept. */
    public static Path backupOf(Path exe) {
        return SteamApi.exeBackupOf(exe);
    }
}
