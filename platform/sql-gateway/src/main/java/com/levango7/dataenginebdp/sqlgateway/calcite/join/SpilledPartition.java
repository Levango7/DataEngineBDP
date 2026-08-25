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
                    Row row = SpillSerDe.readRow(dis);
                    read++;
                    return row;
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


    @Override
    public String toString() {
        return "SpilledPartition{file=" + file.getName()
                + ", side=" + side + ", partition=" + partitionId
                + ", rows=" + rowCount + ", bytes=" + bytes + '}';
    }
}
