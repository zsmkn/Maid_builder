package com.maidbuilder.core.nbt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes big-endian Java-edition NBT, optionally gzip-compressed.
 * Enforces size / depth limits so untrusted uploads cannot exhaust memory.
 */
public final class NbtIo {
    private static final int MAX_DEPTH = 512;
    /**
     * Arrays are read this many bytes at a time, so memory is only taken for data that is really
     * there: a file of a few bytes cannot make the reader allocate what its length field claims.
     */
    private static final int CHUNK_BYTES = 1 << 16;

    private final long maxBytes;
    private long budget;

    private NbtIo(long maxBytes) {
        this.maxBytes = maxBytes;
        this.budget = maxBytes;
    }

    /** Reads a root compound, auto-detecting gzip. {@code maxBytes} bounds the decoded payload size. */
    public static NbtCompound read(InputStream in, long maxBytes) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in);
        buffered.mark(2);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        InputStream source = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(buffered) : buffered;
        return new NbtIo(maxBytes).readRoot(new DataInputStream(source));
    }

    public static NbtCompound read(InputStream in) throws IOException {
        return read(in, 512L * 1024 * 1024);
    }

    /** Writes a gzip-compressed root compound with an empty root name. */
    public static void writeCompressed(NbtCompound root, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(gzip));
        data.writeByte(NbtType.COMPOUND);
        data.writeUTF("");
        writePayload(data, root);
        data.flush();
        gzip.finish();
    }

    private NbtCompound readRoot(DataInput in) throws IOException {
        byte type = in.readByte();
        if (type != NbtType.COMPOUND) {
            throw new IOException("Root tag must be a compound, got type " + type);
        }
        in.readUTF();
        return (NbtCompound) readPayload(in, type, 0);
    }

    private void charge(long bytes) throws IOException {
        budget -= bytes;
        if (budget < 0) {
            // shown to the player whose upload is turned down
            String limit = maxBytes >= 1 << 20 ? (maxBytes >> 20) + " MB" : maxBytes + " bytes";
            throw new IOException("the file is larger than " + limit + " once unpacked (or is damaged)");
        }
    }

    private int readLength(DataInput in, int elementBytes) throws IOException {
        int len = in.readInt();
        if (len < 0) throw new IOException("Negative NBT array length");
        charge((long) len * elementBytes);
        return len;
    }

    /** The next size of an array that is full at {@code size} and will hold {@code len} in the end. */
    private static int grown(int size, int len) {
        return (int) Math.min(len, size * 2L);
    }

    private Object readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case NbtType.BYTE: charge(1); return in.readByte();
            case NbtType.SHORT: charge(2); return in.readShort();
            case NbtType.INT: charge(4); return in.readInt();
            case NbtType.LONG: charge(8); return in.readLong();
            case NbtType.FLOAT: charge(4); return in.readFloat();
            case NbtType.DOUBLE: charge(8); return in.readDouble();
            case NbtType.BYTE_ARRAY: {
                int len = readLength(in, 1);
                byte[] arr = new byte[Math.min(len, CHUNK_BYTES)];
                for (int done = 0; done < len; ) {
                    if (done == arr.length) arr = java.util.Arrays.copyOf(arr, grown(done, len));
                    in.readFully(arr, done, arr.length - done);
                    done = arr.length;
                }
                return arr;
            }
            case NbtType.STRING: {
                String s = in.readUTF();
                charge(2L + s.length() * 2L);
                return s;
            }
            case NbtType.LIST: {
                byte elemType = in.readByte();
                int len = readLength(in, 4);
                NbtList list = new NbtList(elemType);
                if (elemType == NbtType.END && len > 0) throw new IOException("Non-empty list of END tags");
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType, depth + 1));
                }
                return list;
            }
            case NbtType.COMPOUND: {
                NbtCompound compound = new NbtCompound();
                charge(48);
                while (true) {
                    byte childType = in.readByte();
                    if (childType == NbtType.END) break;
                    String name = in.readUTF();
                    charge(36L + name.length() * 2L);
                    compound.put(name, readPayload(in, childType, depth + 1));
                }
                return compound;
            }
            case NbtType.INT_ARRAY: {
                int len = readLength(in, 4);
                int[] arr = new int[Math.min(len, CHUNK_BYTES / 4)];
                for (int i = 0; i < len; i++) {
                    if (i == arr.length) arr = java.util.Arrays.copyOf(arr, grown(i, len));
                    arr[i] = in.readInt();
                }
                return arr;
            }
            case NbtType.LONG_ARRAY: {
                int len = readLength(in, 8);
                long[] arr = new long[Math.min(len, CHUNK_BYTES / 8)];
                for (int i = 0; i < len; i++) {
                    if (i == arr.length) arr = java.util.Arrays.copyOf(arr, grown(i, len));
                    arr[i] = in.readLong();
                }
                return arr;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void writePayload(DataOutput out, Object value) throws IOException {
        if (value instanceof Byte b) {
            out.writeByte(b);
        } else if (value instanceof Short s) {
            out.writeShort(s);
        } else if (value instanceof Integer i) {
            out.writeInt(i);
        } else if (value instanceof Long l) {
            out.writeLong(l);
        } else if (value instanceof Float f) {
            out.writeFloat(f);
        } else if (value instanceof Double d) {
            out.writeDouble(d);
        } else if (value instanceof byte[] arr) {
            out.writeInt(arr.length);
            out.write(arr);
        } else if (value instanceof String s) {
            out.writeUTF(s);
        } else if (value instanceof NbtList list) {
            out.writeByte(list.size() == 0 ? NbtType.END : list.elementType());
            out.writeInt(list.size());
            for (Object o : list) writePayload(out, o);
        } else if (value instanceof NbtCompound compound) {
            for (var e : compound.asMap().entrySet()) {
                out.writeByte(NbtType.of(e.getValue()));
                out.writeUTF(e.getKey());
                writePayload(out, e.getValue());
            }
            out.writeByte(NbtType.END);
        } else if (value instanceof int[] arr) {
            out.writeInt(arr.length);
            for (int i : arr) out.writeInt(i);
        } else if (value instanceof long[] arr) {
            out.writeInt(arr.length);
            for (long l : arr) out.writeLong(l);
        } else {
            throw new IOException("Not an NBT value: " + value.getClass());
        }
    }
}
