package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.io.*;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 溢写分区句柄——指向一个溢写到磁盘的分区文件。
 */
public class SpilledPartition {
    private final File file;
    private final String side;
    private final int partitionId;
    private final int rowCount;
    private final long bytes;

    public SpilledPartition(File file, String side, int partitionId, int rowCount, long bytes) {
        this.file = Objects.requireNonNull(file);
        this.side = side;
        this.partitionId = partitionId;
        this.rowCount = rowCount;
        this.bytes = bytes;
    }

    public File getFile() { return file; }
    public String getSide() { return side; }
    public int getPartitionId() { return partitionId; }
    public int getRowCount() { return rowCount; }
    public long getBytes() { return bytes; }

    /**
     * 打开分区文件的行迭代器。
     */
    public RowIterator openIterator() throws IOException {
        DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)));
        int totalRows = dis.readInt();
        return new RowIterator() {
            private int read = 0;

            @Override public boolean hasNext() { return read < totalRows; }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    int len = dis.readInt();
                    Object[] values = new Object[len];
                    for (int i = 0; i < len; i++) {
                        values[i] = readValueByType(dis, dis.readByte());
                    }
                    read++;
                    return new Row(values);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override public long estimatedSize() { return bytes; }

            @Override
            public void close() {
                try { dis.close(); } catch (IOException ignored) {}
            }
        };
    }

    private Object readValueByType(DataInputStream dis, int type) throws IOException {
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

    @Override
    public String toString() {
        return "SpilledPartition{file=" + file.getName()
                + ", side=" + side + ", partition=" + partitionId
                + ", rows=" + rowCount + ", bytes=" + bytes + '}';
    }
}
