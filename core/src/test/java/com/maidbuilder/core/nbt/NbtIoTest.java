package com.maidbuilder.core.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A few bytes claiming a huge array: the reader must find the data missing, not allocate for it first. */
    @Test
    void arrayLengthWithoutDataTakesNoMemory() throws IOException {
        for (byte type : new byte[]{NbtType.BYTE_ARRAY, NbtType.INT_ARRAY, NbtType.LONG_ARRAY}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.writeByte(NbtType.COMPOUND);
            out.writeUTF("");
            out.writeByte(type);
            out.writeUTF("a");
            out.writeInt(type == NbtType.BYTE_ARRAY ? Integer.MAX_VALUE - 8 : 250_000_000);
            out.writeLong(0);
            long before = Runtime.getRuntime().totalMemory();
            assertThrows(java.io.EOFException.class, () -> NbtIo.read(new ByteArrayInputStream(bytes.toByteArray()), 4L << 30));
            assertTrue(Runtime.getRuntime().totalMemory() - before < 256L << 20, "heap grew for an array that is not there");
        }
    }

    @Test
    void readsArraysLongerThanOneChunk() throws IOException {
        byte[] ba = new byte[200_001];
        int[] ia = new int[70_003];
        long[] la = new long[40_007];
        for (int i = 0; i < ba.length; i++) ba[i] = (byte) (i * 31);
        for (int i = 0; i < ia.length; i++) ia[i] = i * 7919;
        for (int i = 0; i < la.length; i++) la[i] = i * 1_000_003L;
        NbtCompound read = roundTrip(new NbtCompound().put("ba", ba).put("ia", ia).put("la", la), 1 << 22);
        assertArrayEquals(ba, (byte[]) read.get("ba"));
        assertArrayEquals(ia, (int[]) read.get("ia"));
        assertArrayEquals(la, read.getLongArray("la"));
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
