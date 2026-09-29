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
            throw new IOException("NBT data exceeds the size limit of " + maxBytes + " bytes");
        }
    }

    private int readLength(DataInput in, int elementBytes) throws IOException {
        int len = in.readInt();
        if (len < 0) throw new IOException("Negative NBT array length");
        charge((long) len * elementBytes);
        return len;
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
                byte[] arr = new byte[readLength(in, 1)];
                in.readFully(arr);
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
                int[] arr = new int[readLength(in, 4)];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readInt();
                return arr;
            }
            case NbtType.LONG_ARRAY: {
                long[] arr = new long[readLength(in, 8)];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readLong();
                return arr;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void writePayload(DataOutput out, Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] arr -> {
                out.writeInt(arr.length);
                out.write(arr);
            }
            case String s -> out.writeUTF(s);
            case NbtList list -> {
                out.writeByte(list.size() == 0 ? NbtType.END : list.elementType());
                out.writeInt(list.size());
                for (Object o : list) writePayload(out, o);
            }
            case NbtCompound compound -> {
                for (var e : compound.asMap().entrySet()) {
                    out.writeByte(NbtType.of(e.getValue()));
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(NbtType.END);
            }
            case int[] arr -> {
                out.writeInt(arr.length);
                for (int i : arr) out.writeInt(i);
            }
            case long[] arr -> {
                out.writeInt(arr.length);
                for (long l : arr) out.writeLong(l);
            }
            default -> throw new IOException("Not an NBT value: " + value.getClass());
        }
    }
}
