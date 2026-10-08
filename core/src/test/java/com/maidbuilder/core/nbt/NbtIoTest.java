package com.maidbuilder.core.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NbtIoTest {
    private static NbtCompound roundTrip(NbtCompound tag, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return NbtIo.read(new ByteArrayInputStream(out.toByteArray()), limit);
    }

    @Test
    void roundTripsAllTagTypes() throws IOException {
        NbtList list = new NbtList(NbtType.END).add(new NbtCompound().put("Name", "minecraft:stone"));
        NbtCompound tag = new NbtCompound()
                .put("b", (byte) 1).put("s", (short) 2).put("i", 3).put("l", 4L)
                .put("f", 5f).put("d", 6d).put("ba", new byte[]{7, 8}).put("str", "héllo")
                .put("list", list).put("ia", new int[]{9}).put("la", new long[]{Long.MIN_VALUE, -1L})
                .put("empty", new NbtList(NbtType.END))
                .put("nested", new NbtCompound().put("x", -5));

        NbtCompound read = roundTrip(tag, 1 << 20);
        assertEquals(tag, read);
        assertEquals(3, read.getInt("i"));
        assertEquals("héllo", read.getString("str"));
        assertArrayEquals(new long[]{Long.MIN_VALUE, -1L}, read.getLongArray("la"));
        assertEquals("minecraft:stone", read.getList("list").getCompound(0).getString("Name"));
        assertEquals(-5, read.getCompound("nested").getInt("x"));
    }

    @Test
    void enforcesSizeLimit() {
        NbtCompound tag = new NbtCompound().put("big", new long[10_000]);
        assertThrows(IOException.class, () -> roundTrip(tag, 1000));
    }

    @Test
    void lenientGettersReturnDefaults() {
        NbtCompound tag = new NbtCompound().put("s", "x");
        assertEquals(0, tag.getInt("s"));
        assertEquals(0, tag.getInt("missing"));
        assertEquals("", tag.getString("missing"));
        assertEquals(0, tag.getCompound("missing").size());
        assertEquals(0, tag.getList("missing").size());
    }
}
