package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.io.*;

/**
 * 行序列化/反序列化工具——SpillManager 和 SpilledPartition 共用。
 *
 * <p>二进制格式：</p>
 * <pre>
 *   行: [列数 int, 列1类型 byte, 列1值, ...]
 *   类型: 0=null, 1=int, 2=long, 3=double, 4=float, 5=String, 6=boolean, 7=short, 8=byte, 9=byte[]
 * </pre>
 */
final class SpillSerDe {
    private SpillSerDe() {}

    /** 序列化一行，返回写入字节数 */
    static long writeRow(DataOutputStream dos, Row row) throws IOException {
        long bytes = 0;
        Object[] values = row.getValues();
        dos.writeInt(values.length);
        bytes += 4;
        for (Object v : values) {
            bytes += writeValue(dos, v);
        }
        return bytes;
    }

    /** 反序列化一行 */
    static Row readRow(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        Object[] values = new Object[len];
        for (int i = 0; i < len; i++) {
            values[i] = readValue(dis);
        }
        return new Row(values);
    }

    /** 序列化单个值，返回写入字节数 */
    static long writeValue(DataOutputStream dos, Object v) throws IOException {
        if (v == null) {
            dos.writeByte(0);
            return 1;
        } else if (v instanceof Integer i) {
            dos.writeByte(1); dos.writeInt(i); return 5;
        } else if (v instanceof Long l) {
            dos.writeByte(2); dos.writeLong(l); return 9;
        } else if (v instanceof Double d) {
            dos.writeByte(3); dos.writeDouble(d); return 9;
        } else if (v instanceof Float f) {
            dos.writeByte(4); dos.writeFloat(f); return 5;
        } else if (v instanceof String s) {
            dos.writeByte(5); dos.writeUTF(s); return 3 + s.length();
        } else if (v instanceof Boolean b) {
            dos.writeByte(6); dos.writeBoolean(b); return 2;
        } else if (v instanceof Short s) {
            dos.writeByte(7); dos.writeShort(s); return 3;
        } else if (v instanceof Byte b) {
            dos.writeByte(8); dos.writeByte(b); return 2;
        } else if (v instanceof byte[] bytes) {
            dos.writeByte(9); dos.writeInt(bytes.length); dos.write(bytes);
            return 5 + bytes.length;
        } else {
            dos.writeByte(5); dos.writeUTF(v.toString());
            return 3 + v.toString().length();
        }
    }

    /** 反序列化单个值（先读类型 byte） */
    static Object readValue(DataInputStream dis) throws IOException {
        int type = dis.readByte();
        return readValueByType(dis, type);
    }

    /** 按已知类型反序列化单个值 */
    static Object readValueByType(DataInputStream dis, int type) throws IOException {
        return switch (type) {
            case 0 -> null;
            case 1 -> dis.readInt();
            case 2 -> dis.readLong();
            case 3 -> dis.readDouble();
            case 4 -> dis.readFloat();
            case 5 -> dis.readUTF();
            case 6 -> dis.readBoolean();
            case 7 -> dis.readShort();
            case 8 -> dis.readByte();
            case 9 -> {
                int blen = dis.readInt();
                byte[] bytes = new byte[blen];
                dis.readFully(bytes);
                yield bytes;
            }
            default -> throw new IOException("未知序列化类型: " + type);
        };
    }
}