package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spill 管理器——管理磁盘溢写临时文件。
 *
 * <p>溢写文件格式（二进制）：</p>
 * <pre>
 *   [行数 int]
 *   [行1: 列数 int, 列1类型 byte, 列1值, 列2类型 byte, 列2值, ...]
 *   [行2: ...]
 *   ...
 * </pre>
 */
public class SpillManager {
    private final String spillDir;
    private final int numPartitions;
    private final List<File> spillFiles = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong totalSpilledBytes = new AtomicLong(0);
    private final AtomicLong totalSpillWriteCount = new AtomicLong(0);
    private final AtomicLong totalSpillReadCount = new AtomicLong(0);

    public SpillManager(String spillDir, int numPartitions) {
        this.spillDir = Objects.requireNonNull(spillDir);
        this.numPartitions = numPartitions;
    }

    public String getSpillDir() { return spillDir; }
    public int getNumPartitions() { return numPartitions; }
    public long getTotalSpilledBytes() { return totalSpilledBytes.get(); }
    public long getTotalSpillWriteCount() { return totalSpillWriteCount.get(); }
    public long getTotalSpillReadCount() { return totalSpillReadCount.get(); }
    public int getSpillFileCount() { return spillFiles.size(); }

    /**
     * 从溢写文件流式读取行。
     */
    public RowIterator readSpilled(File file) throws IOException {
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(file)));
        int rowCount = dis.readInt();
        totalSpillReadCount.incrementAndGet();
        return new RowIterator() {
            private int read = 0;
            @Override public boolean hasNext() { return read < rowCount; }
            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                try { return readRow(dis); } catch (java.io.IOException e) { throw new UncheckedIOException(e); }
                finally { read++; }
            }
            @Override public long estimatedSize() { return -1; }
            @Override public void close() { try { dis.close(); } catch (java.io.IOException ignored) {} }
        };
    }

    /**
     * 将行列表溢写到磁盘。
     */
    public SpilledPartition spill(List<Row> rows, String side, int partition) throws IOException {
        String fileName = "spill-" + UUID.randomUUID() + "-" + side + "-" + partition + ".bin";
        File file = new File(spillDir, fileName);
        spillFiles.add(file);

        long bytesWritten = 0;
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            dos.writeInt(rows.size());
            bytesWritten += 4;
            for (Row row : rows) {
                bytesWritten += writeRow(dos, row);
            }
        }
        totalSpilledBytes.addAndGet(bytesWritten);
        totalSpillWriteCount.incrementAndGet();
        return new SpilledPartition(file, side, partition, rows.size(), bytesWritten);
    }

    /**
     * 清理所有溢写文件。
     */
    public void cleanup() throws IOException {
        synchronized (spillFiles) {
            for (File file : spillFiles) {
                Files.deleteIfExists(file.toPath());
            }
            spillFiles.clear();
        }
    }

    // ===================== 序列化 =====================

    private long writeRow(DataOutputStream dos, Row row) throws IOException {
        long bytes = 0;
        Object[] values = row.getValues();
        dos.writeInt(values.length);
        bytes += 4;
        for (Object v : values) {
            bytes += writeValue(dos, v);
        }
        return bytes;
    }

    private long writeValue(DataOutputStream dos, Object v) throws IOException {
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

    private Row readRow(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        Object[] values = new Object[len];
        for (int i = 0; i < len; i++) {
            values[i] = readValue(dis);
        }
        return new Row(values);
    }

    private Object readValue(DataInputStream dis) throws IOException {
        int type = dis.readByte();
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
