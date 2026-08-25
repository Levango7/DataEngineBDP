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
                try { return SpillSerDe.readRow(dis); } catch (java.io.IOException e) { throw new UncheckedIOException(e); }
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
                bytesWritten += SpillSerDe.writeRow(dos, row);
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

}
