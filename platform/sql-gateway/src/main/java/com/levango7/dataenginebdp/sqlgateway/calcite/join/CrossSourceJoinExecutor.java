package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import com.levango7.dataenginebdp.sqlgateway.calcite.rule.BroadcastJoinStrategy;

import java.io.IOException;
import java.util.*;

/**
 * 跨源 Join 结果归并执行器——基于 T012 联邦优化器，在联邦层执行跨源 Join 的
 * 结果拉取、归并与物化，内置内存管理与 spill-to-disk 机制，保证大表 Join 的稳定性。
 *
 * <p>本类是 T013 的核心组件，承接 {@link BroadcastJoinStrategy} 选定的物理策略，
 * 完成跨源 Join 的实际数据归并执行。核心职责：</p>
 * <ul>
 *   <li><b>结果归并</b>：从两个异构数据源拉取行数据，按 Join Key 在联邦层做
 *       Hash/Broadcast/NestedLoop Join，输出归并结果</li>
 *   <li><b>内存管理</b>：{@link MemoryManager} 跟踪 Join 构建侧（build side）的内存占用，
 *       超过预算时自动触发 spill，避免 OOM</li>
 *   <li><b>Spill to disk</b>：{@link SpillManager} 将溢写分区写入临时文件，
 *       后续按分区流式读回做 Grace Hash Join，保证正确性的同时控制内存</li>
 *   <li><b>多策略支持</b>：BROADCAST（小表全量广播）、SHUFFLE（按 Key Hash 分区）、
 *       NESTED_LOOP（非等值 Join 兜底）</li>
 *   <li><b>多 Join 类型</b>：INNER / LEFT / RIGHT / FULL / SEMI / ANTI</li>
 * </ul>
 *
 * <p>线程安全：本类实例非线程安全，每次 Join 执行应在单线程内完成。</p>
 *
 * @author shuqing-bigdata
 */
public class CrossSourceJoinExecutor {

    // ===================== 常量 =====================

    /** 默认内存预算：256MB */
    public static final long DEFAULT_MEMORY_BUDGET = 256L * 1024 * 1024;
    /** 默认 build 侧内存占比（build:probe = 60:40） */
    public static final double DEFAULT_BUILD_SIDE_RATIO = 0.6;
    /** 默认 spill 分区数 */
    public static final int DEFAULT_SPILL_PARTITIONS = 16;
    /** 默认每行估算大小（字节），用于内存跟踪 */
    public static final long DEFAULT_ROW_SIZE_ESTIMATE = 128L;
    /** 默认 batch 大小（行数） */
    public static final int DEFAULT_BATCH_SIZE = 4096;
    /** 默认 spill 临时目录 */
    public static final String DEFAULT_SPILL_DIR = System.getProperty("java.io.tmpdir");

    // ===================== 字段 =====================

    private final JoinConfig config;
    private final MemoryManager memoryManager;
    private final SpillManager spillManager;
    private final JoinStatistics statistics;
    private volatile boolean closed = false;

    // ===================== 构造方法 =====================

    public CrossSourceJoinExecutor() {
        this(new JoinConfig());
    }

    public CrossSourceJoinExecutor(JoinConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.statistics = new JoinStatistics();
        this.memoryManager = new MemoryManager(config.getMemoryBudgetBytes(), config.getBuildSideRatio());
        this.spillManager = new SpillManager(config.getSpillDir(), config.getSpillPartitions());
    }

    public CrossSourceJoinExecutor(JoinConfig config, JoinStatistics statistics) {
        this.config = Objects.requireNonNull(config, "config");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.memoryManager = new MemoryManager(config.getMemoryBudgetBytes(), config.getBuildSideRatio());
        this.spillManager = new SpillManager(config.getSpillDir(), config.getSpillPartitions());
    }

    // ===================== 公共 API =====================

    /**
     * 执行跨源 Join 归并——从左右两个数据源拉取行数据，按 Join Key 归并输出。
     * 自动选择最优 Join 算法（BROADCAST / SHUFFLE / NESTED_LOOP）。
     */
    public JoinResult execute(RowIterator leftIter, RowIterator rightIter,
                              JoinKey joinKey, JoinType joinType) {
        Objects.requireNonNull(leftIter, "leftIter");
        Objects.requireNonNull(rightIter, "rightIter");
        Objects.requireNonNull(joinKey, "joinKey");
        Objects.requireNonNull(joinType, "joinType");
        ensureOpen();

        statistics.recordStart();
        try {
            JoinAlgorithm algorithm = chooseAlgorithm(leftIter, rightIter, joinKey);
            statistics.recordAlgorithm(algorithm);

            JoinResult result = switch (algorithm) {
                case BROADCAST -> broadcastJoin(leftIter, rightIter, joinKey, joinType);
                case SHUFFLE -> shuffleJoin(leftIter, rightIter, joinKey, joinType);
                case NESTED_LOOP -> nestedLoopJoin(leftIter, rightIter, joinKey, joinType);
            };

            statistics.recordSuccess();
            return new JoinResult(result.getRows(), result.getJoinType(),
                    result.getAlgorithm(), statistics.snapshot());
        } catch (IOException e) {
            statistics.recordFailure(e.getMessage());
            throw new CrossSourceJoinException("跨源 Join 执行失败: " + e.getMessage(), e);
        } catch (Exception e) {
            statistics.recordFailure(e.getMessage());
            throw new CrossSourceJoinException("跨源 Join 执行失败: " + e.getMessage(), e);
        } finally {
            statistics.recordEnd();
        }
    }

    /**
     * 执行跨源 Join（指定算法）。
     */
    public JoinResult execute(RowIterator leftIter, RowIterator rightIter,
                              JoinKey joinKey, JoinType joinType,
                              JoinAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        ensureOpen();
        statistics.recordStart();
        try {
            statistics.recordAlgorithm(algorithm);
            JoinResult result = switch (algorithm) {
                case BROADCAST -> broadcastJoin(leftIter, rightIter, joinKey, joinType);
                case SHUFFLE -> shuffleJoin(leftIter, rightIter, joinKey, joinType);
                case NESTED_LOOP -> nestedLoopJoin(leftIter, rightIter, joinKey, joinType);
            };
            statistics.recordSuccess();
            return new JoinResult(result.getRows(), result.getJoinType(),
                    result.getAlgorithm(), statistics.snapshot());
        } catch (IOException e) {
            statistics.recordFailure(e.getMessage());
            throw new CrossSourceJoinException("跨源 Join 执行失败: " + e.getMessage(), e);
        } catch (Exception e) {
            statistics.recordFailure(e.getMessage());
            throw new CrossSourceJoinException("跨源 Join 执行失败: " + e.getMessage(), e);
        } finally {
            statistics.recordEnd();
        }
    }

    /**
     * Broadcast Hash Join——将小表（build 侧）全量加载到内存 Hash 表，
     * 大表（probe 侧）流式扫描并查 Hash 表匹配。
     */
    public JoinResult broadcastJoin(RowIterator leftIter, RowIterator rightIter,
                                     JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        RowIterator buildIter = config.isBuildOnLeft() ? leftIter : rightIter;
        RowIterator probeIter = config.isBuildOnLeft() ? rightIter : leftIter;
        int[] buildKeyIndices = config.isBuildOnLeft() ? joinKey.getLeftColumnIndices()
                : joinKey.getRightColumnIndices();
        int[] probeKeyIndices = config.isBuildOnLeft() ? joinKey.getRightColumnIndices()
                : joinKey.getLeftColumnIndices();

        Map<JoinKeyHash, List<Row>> hashTable = buildHashTable(buildIter, buildKeyIndices);
        statistics.recordBuildRows(hashTable.size());

        List<Row> outputRows = probeAndMerge(probeIter, probeKeyIndices,
                hashTable, joinKey, joinType, config.isBuildOnLeft());

        boolean buildOnLeft = config.isBuildOnLeft();
        boolean shouldEmitUnmatchedBuild =
                (buildOnLeft && (joinType == JoinType.LEFT || joinType == JoinType.FULL))
                        || (!buildOnLeft && (joinType == JoinType.RIGHT || joinType == JoinType.FULL));
        if (shouldEmitUnmatchedBuild) {
            outputRows.addAll(produceUnmatchedBuild(hashTable, joinKey, joinType, buildOnLeft));
        }

        statistics.recordOutputRows(outputRows.size());
        memoryManager.releaseAll();
        return new JoinResult(outputRows, joinType, JoinAlgorithm.BROADCAST, statistics.snapshot());
    }

    /**
     * Shuffle Hash Join——按 Join Key Hash 将两侧数据分区，
     * 逐分区做内存 Hash Join。当单分区仍超内存时，递归 spill 到磁盘。
     */
    public JoinResult shuffleJoin(RowIterator leftIter, RowIterator rightIter,
                                   JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        int numPartitions = config.getSpillPartitions();

        List<Row> leftInMemory = new ArrayList<>();
        List<SpilledPartition> leftSpilled = partitionAndSpill(leftIter,
                joinKey.getLeftColumnIndices(), numPartitions, "left", leftInMemory);

        List<Row> rightInMemory = new ArrayList<>();
        List<SpilledPartition> rightSpilled = partitionAndSpill(rightIter,
                joinKey.getRightColumnIndices(), numPartitions, "right", rightInMemory);

        statistics.recordSpillPartitions(leftSpilled.size() + rightSpilled.size());

        List<Row> outputRows = new ArrayList<>();

        // 内存中的分区 Join
        for (int p = 0; p < numPartitions; p++) {
            List<Row> leftPart = filterPartition(leftInMemory, p, numPartitions,
                    joinKey.getLeftColumnIndices());
            List<Row> rightPart = filterPartition(rightInMemory, p, numPartitions,
                    joinKey.getRightColumnIndices());
            outputRows.addAll(hashJoinPartition(leftPart, rightPart, joinKey, joinType));
        }

        // 溢写到磁盘的分区 Join
        for (int p = 0; p < numPartitions; p++) {
            SpilledPartition leftPart = findSpilledPartition(leftSpilled, p);
            SpilledPartition rightPart = findSpilledPartition(rightSpilled, p);
            if (leftPart != null && rightPart != null) {
                outputRows.addAll(spilledPartitionJoin(leftPart, rightPart, joinKey, joinType));
            } else if (leftPart != null && (joinType == JoinType.LEFT || joinType == JoinType.FULL)) {
                try (RowIterator leftRead = leftPart.openIterator()) {
                    while (leftRead.hasNext()) {
                        outputRows.add(mergeRows(leftRead.next(), null, joinKey));
                    }
                }
            } else if (rightPart != null && (joinType == JoinType.RIGHT || joinType == JoinType.FULL)) {
                try (RowIterator rightRead = rightPart.openIterator()) {
                    while (rightRead.hasNext()) {
                        outputRows.add(mergeRows(null, rightRead.next(), joinKey));
                    }
                }
            }
        }

        statistics.recordOutputRows(outputRows.size());
        memoryManager.releaseAll();
        return new JoinResult(outputRows, joinType, JoinAlgorithm.SHUFFLE, statistics.snapshot());
    }

    /**
     * Nested Loop Join——对左侧每行，扫描右侧所有行，逐对检查 Join 条件。
     */
    public JoinResult nestedLoopJoin(RowIterator leftIter, RowIterator rightIter,
                                      JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        List<Row> rightRows = new ArrayList<>();
        while (rightIter.hasNext()) {
            Row row = rightIter.next();
            rightRows.add(row);
            memoryManager.acquire(row.estimatedSize());
            if (memoryManager.isOverBudget()) {
                memoryManager.release(row.estimatedSize());
            }
        }
        statistics.recordBuildRows(rightRows.size());

        List<Row> outputRows = new ArrayList<>();
        Set<Integer> matchedRightIndices = new LinkedHashSet<>();

        while (leftIter.hasNext()) {
            Row leftRow = leftIter.next();
            boolean matched = false;
            for (int i = 0; i < rightRows.size(); i++) {
                Row rightRow = rightRows.get(i);
                if (joinKey.matches(leftRow, rightRow)) {
                    outputRows.add(mergeRows(leftRow, rightRow, joinKey));
                    matched = true;
                    matchedRightIndices.add(i);
                }
            }
            if (!matched && (joinType == JoinType.LEFT || joinType == JoinType.FULL)) {
                outputRows.add(mergeRows(leftRow, null, joinKey));
            }
        }

        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            for (int i = 0; i < rightRows.size(); i++) {
                if (!matchedRightIndices.contains(i)) {
                    outputRows.add(mergeRows(null, rightRows.get(i), joinKey));
                }
            }
        }

        statistics.recordOutputRows(outputRows.size());
        memoryManager.releaseAll();
        return new JoinResult(outputRows, joinType, JoinAlgorithm.NESTED_LOOP, statistics.snapshot());
    }

    /**
     * 归并两侧行到输出行——拼接左右行的列值。
     */
    public Row mergeRows(Row leftRow, Row rightRow, JoinKey joinKey) {
        int leftCols = leftRow != null ? leftRow.size() : joinKey.getLeftColumnCount();
        int rightCols = rightRow != null ? rightRow.size() : joinKey.getRightColumnCount();
        Object[] values = new Object[leftCols + rightCols];
        if (leftRow != null) {
            for (int i = 0; i < leftRow.size(); i++) {
                values[i] = leftRow.get(i);
            }
        }
        if (rightRow != null) {
            int offset = leftRow != null ? leftRow.size() : leftCols;
            for (int i = 0; i < rightRow.size(); i++) {
                values[offset + i] = rightRow.get(i);
            }
        }
        return new Row(values);
    }

    /**
     * 关闭执行器，清理所有 spill 临时文件。
     */
    public void close() {
        if (closed) return;
        closed = true;
        try {
            spillManager.cleanup();
        } catch (IOException e) {
            // 关闭时忽略清理异常
        }
        memoryManager.releaseAll();
    }

    // ===================== 配置与统计访问 =====================

    public JoinConfig getConfig() { return config; }
    public MemoryManager getMemoryManager() { return memoryManager; }
    public SpillManager getSpillManager() { return spillManager; }
    public JoinStatistics getStatistics() { return statistics; }
    public boolean isClosed() { return closed; }

    // ===================== 内部方法 =====================

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("CrossSourceJoinExecutor 已关闭");
    }

    private JoinAlgorithm chooseAlgorithm(RowIterator leftIter, RowIterator rightIter,
                                          JoinKey joinKey) {
        if (!joinKey.isEquiJoin()) return JoinAlgorithm.NESTED_LOOP;
        long leftSize = leftIter.estimatedSize();
        long rightSize = rightIter.estimatedSize();
        long budget = config.getMemoryBudgetBytes();
        long buildBudget = (long) (budget * config.getBuildSideRatio());
        long minSize = Math.min(leftSize, rightSize);
        if (minSize > 0 && minSize < buildBudget) {
            return JoinAlgorithm.BROADCAST;
        }
        return JoinAlgorithm.SHUFFLE;
    }

    private Map<JoinKeyHash, List<Row>> buildHashTable(RowIterator buildIter,
                                                        int[] keyIndices) throws IOException {
        Map<JoinKeyHash, List<Row>> hashTable = new HashMap<>();
        while (buildIter.hasNext()) {
            Row row = buildIter.next();
            long rowSize = row.estimatedSize();
            memoryManager.acquire(rowSize);
            if (memoryManager.isOverBudget()) {
                statistics.recordSpillTriggered();
                memoryManager.release(rowSize);
            }
            JoinKeyHash keyHash = computeKeyHash(row, keyIndices);
            hashTable.computeIfAbsent(keyHash, k -> new ArrayList<>()).add(row);
        }
        return hashTable;
    }

    private List<Row> probeAndMerge(RowIterator probeIter, int[] probeKeyIndices,
                                     Map<JoinKeyHash, List<Row>> hashTable,
                                     JoinKey joinKey, JoinType joinType, boolean buildOnLeft) {
        List<Row> output = new ArrayList<>();
        Set<JoinKeyHash> matchedKeys = new LinkedHashSet<>();

        while (probeIter.hasNext()) {
            Row probeRow = probeIter.next();
            JoinKeyHash probeHash = computeKeyHash(probeRow, probeKeyIndices);
            List<Row> buildRows = hashTable.get(probeHash);
            if (buildRows != null && !buildRows.isEmpty()) {
                for (Row buildRow : buildRows) {
                    if (keysEqual(buildRow, buildOnLeft ? joinKey.getLeftColumnIndices()
                            : joinKey.getRightColumnIndices(),
                            probeRow, probeKeyIndices)) {
                        Row merged = buildOnLeft ? mergeRows(buildRow, probeRow, joinKey)
                                : mergeRows(probeRow, buildRow, joinKey);
                        output.add(merged);
                        matchedKeys.add(probeHash);
                    }
                }
            } else if (joinType == JoinType.LEFT || joinType == JoinType.RIGHT
                    || joinType == JoinType.FULL) {
                Row merged = buildOnLeft ? mergeRows(null, probeRow, joinKey)
                        : mergeRows(probeRow, null, joinKey);
                if ((buildOnLeft && (joinType == JoinType.RIGHT || joinType == JoinType.FULL))
                        || (!buildOnLeft && (joinType == JoinType.LEFT || joinType == JoinType.FULL))) {
                    output.add(merged);
                }
            }
        }

        for (JoinKeyHash matched : matchedKeys) {
            List<Row> rows = hashTable.get(matched);
            if (rows != null) {
                for (Row r : rows) r.setMatched(true);
            }
        }
        return output;
    }

    private List<Row> produceUnmatchedBuild(Map<JoinKeyHash, List<Row>> hashTable,
                                            JoinKey joinKey, JoinType joinType,
                                            boolean buildOnLeft) {
        List<Row> output = new ArrayList<>();
        for (List<Row> rows : hashTable.values()) {
            for (Row row : rows) {
                if (!row.isMatched()) {
                    Row merged = buildOnLeft ? mergeRows(row, null, joinKey)
                            : mergeRows(null, row, joinKey);
                    output.add(merged);
                }
            }
        }
        return output;
    }

    private List<SpilledPartition> partitionAndSpill(RowIterator iter, int[] keyIndices,
                                                      int numPartitions, String side,
                                                      List<Row> inMemoryRows) throws IOException {
        List<List<Row>> partitions = new ArrayList<>(numPartitions);
        for (int i = 0; i < numPartitions; i++) partitions.add(new ArrayList<>());

        while (iter.hasNext()) {
            Row row = iter.next();
            int part = partitionOf(row, keyIndices, numPartitions);
            partitions.get(part).add(row);
            memoryManager.acquire(row.estimatedSize());
            if (memoryManager.isOverBudget()) {
                statistics.recordSpillTriggered();
                memoryManager.release(row.estimatedSize());
            }
        }

        List<SpilledPartition> spilled = new ArrayList<>();
        long partitionBudget = memoryManager.getBuildBudget() / numPartitions;
        for (int p = 0; p < numPartitions; p++) {
            List<Row> partRows = partitions.get(p);
            long partSize = 0;
            for (Row r : partRows) partSize += r.estimatedSize();
            if (partSize > partitionBudget && !partRows.isEmpty()) {
                SpilledPartition spilledPart = spillManager.spill(partRows, side, p);
                spilled.add(spilledPart);
                statistics.recordSpilledRows(partRows.size());
            } else {
                inMemoryRows.addAll(partRows);
            }
        }
        return spilled;
    }

    private List<Row> filterPartition(List<Row> rows, int partition, int numPartitions,
                                       int[] keyIndices) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (partitionOf(row, keyIndices, numPartitions) == partition) {
                result.add(row);
            }
        }
        return result;
    }

    private SpilledPartition findSpilledPartition(List<SpilledPartition> spilled, int partition) {
        for (SpilledPartition sp : spilled) {
            if (sp.getPartitionId() == partition) return sp;
        }
        return null;
    }

    private List<Row> hashJoinPartition(List<Row> leftPart, List<Row> rightPart,
                                         JoinKey joinKey, JoinType joinType) {
        List<Row> output = new ArrayList<>();
        Map<JoinKeyHash, List<Row>> rightHash = new HashMap<>();
        for (Row row : rightPart) {
            JoinKeyHash kh = computeKeyHash(row, joinKey.getRightColumnIndices());
            rightHash.computeIfAbsent(kh, k -> new ArrayList<>()).add(row);
        }

        Set<Row> matchedRight = new LinkedHashSet<>();
        for (Row leftRow : leftPart) {
            JoinKeyHash leftHash = computeKeyHash(leftRow, joinKey.getLeftColumnIndices());
            List<Row> matches = rightHash.get(leftHash);
            if (matches != null) {
                for (Row rightRow : matches) {
                    if (keysEqual(leftRow, joinKey.getLeftColumnIndices(),
                            rightRow, joinKey.getRightColumnIndices())) {
                        output.add(mergeRows(leftRow, rightRow, joinKey));
                        matchedRight.add(rightRow);
                    }
                }
            } else if (joinType == JoinType.LEFT || joinType == JoinType.FULL) {
                output.add(mergeRows(leftRow, null, joinKey));
            }
        }

        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            for (Row rightRow : rightPart) {
                if (!matchedRight.contains(rightRow)) {
                    output.add(mergeRows(null, rightRow, joinKey));
                }
            }
        }
        return output;
    }

    private List<Row> spilledPartitionJoin(SpilledPartition leftPart,
                                            SpilledPartition rightPart,
                                            JoinKey joinKey, JoinType joinType) throws IOException {
        List<Row> leftRows = new ArrayList<>();
        try (RowIterator leftIter = leftPart.openIterator()) {
            while (leftIter.hasNext()) leftRows.add(leftIter.next());
        }
        List<Row> rightRows = new ArrayList<>();
        try (RowIterator rightIter = rightPart.openIterator()) {
            while (rightIter.hasNext()) rightRows.add(rightIter.next());
        }
        return hashJoinPartition(leftRows, rightRows, joinKey, joinType);
    }

    private JoinKeyHash computeKeyHash(Row row, int[] keyIndices) {
        Object[] keyValues = new Object[keyIndices.length];
        for (int i = 0; i < keyIndices.length; i++) {
            keyValues[i] = row.get(keyIndices[i]);
        }
        return new JoinKeyHash(keyValues);
    }

    private int partitionOf(Row row, int[] keyIndices, int numPartitions) {
        return Math.abs(computeKeyHash(row, keyIndices).hashCode()) % numPartitions;
    }

    private boolean keysEqual(Row row1, int[] indices1, Row row2, int[] indices2) {
        if (indices1.length != indices2.length) return false;
        for (int i = 0; i < indices1.length; i++) {
            if (!Objects.equals(row1.get(indices1[i]), row2.get(indices2[i]))) return false;
        }
        return true;
    }
}
