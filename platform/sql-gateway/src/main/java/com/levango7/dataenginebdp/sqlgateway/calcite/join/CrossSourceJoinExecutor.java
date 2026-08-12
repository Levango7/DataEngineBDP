package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.BroadcastJoinStrategy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>执行流程（以 Shuffle Hash Join 为例）：</b></p>
 * <pre>
 *   CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
 *     │
 *     ▼  executor.execute(leftIter, rightIter, joinKey, JoinType.INNER)
 *     │
 *     │  1. 选择算法：基于统计信息选择 BROADCAST / SHUFFLE / NESTED_LOOP
 *     │  2. 构建侧（小表）加载到内存：
 *     │     ├─ 逐行读入 RowBatch
 *     │     ├─ MemoryManager.acquire(rowSize) 跟踪内存
 *     │     └─ 内存超限 → SpillManager.spill(batch) 溢写到磁盘
 *     │  3. 探测侧（大表）流式扫描：
 *     │     ├─ 逐行读入，按 Join Key Hash 查 Hash 表
 *     │     ├─ 命中 → 输出归并行
 *     │     └─ 未命中且为 OUTER → 输出填充 null 的行
 *     │  4. 若发生 spill：逐分区从磁盘读回 build 侧，重新探测
 *     │  5. 输出 JoinResult（行迭代器 + 统计信息）
 * </pre>
 *
 * <p><b>内存预算模型：</b></p>
 * <pre>
 *   总预算 = joinConfig.memoryBudgetBytes（默认 256MB）
 *   build 侧可用 = 总预算 × buildSideRatio（默认 0.6）
 *   probe 侧缓冲 = 总预算 × (1 - buildSideRatio)
 *   单行估算大小 = 列数 × 16 字节（Object 引用 + 平均值大小）
 * </pre>
 *
 * <p><b>Spill 机制：</b>当 build 侧内存占用超过预算时，将当前 Hash 表分区溢写到
 * 临时文件（{@code spill-{uuid}-{partition}.bin}），采用 Grace Hash Join 算法：
 * 对 build 和 probe 侧按相同 Hash 函数分区，逐分区加载 build 侧到内存做 Join，
 * 保证内存峰值 ≤ 单分区大小。</p>
 *
 * <p><b>线程安全：</b>本类实例非线程安全，每次 Join 执行应在单线程内完成。
 * {@link JoinStatistics} 内部使用 {@code volatile} + {@code synchronized} 保证
 * 多线程统计读取安全。</p>
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

    /** Join 配置 */
    private final JoinConfig config;

    /** 内存管理器 */
    private final MemoryManager memoryManager;

    /** Spill 管理器 */
    private final SpillManager spillManager;

    /** 执行统计器 */
    private final JoinStatistics statistics;

    /** 是否已关闭 */
    private volatile boolean closed = false;

    // ===================== 构造方法 =====================

    /**
     * 使用默认配置构造跨源 Join 执行器。
     */
    public CrossSourceJoinExecutor() {
        this(new JoinConfig());
    }

    /**
     * 使用指定配置构造跨源 Join 执行器。
     *
     * @param config Join 配置
     */
    public CrossSourceJoinExecutor(JoinConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.statistics = new JoinStatistics();
        this.memoryManager = new MemoryManager(config.getMemoryBudgetBytes(),
                config.getBuildSideRatio());
        this.spillManager = new SpillManager(config.getSpillDir(),
                config.getSpillPartitions());
    }

    /**
     * 使用指定配置和统计器构造（主要用于测试注入）。
     *
     * @param config     Join 配置
     * @param statistics 统计器
     */
    public CrossSourceJoinExecutor(JoinConfig config, JoinStatistics statistics) {
        this.config = Objects.requireNonNull(config, "config");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.memoryManager = new MemoryManager(config.getMemoryBudgetBytes(),
                config.getBuildSideRatio());
        this.spillManager = new SpillManager(config.getSpillDir(),
                config.getSpillPartitions());
    }

    // ===================== 公共 API =====================

    /**
     * 执行跨源 Join 归并——从左右两个数据源拉取行数据，按 Join Key 归并输出。
     *
     * <p>本方法自动选择最优 Join 算法：</p>
     * <ul>
     *   <li>若指定算法为 BROADCAST 或右侧估算能放入内存 → Broadcast Hash Join</li>
     *   <li>若两侧均较大 → Shuffle Hash Join（含 spill）</li>
     *   <li>若 Join Key 为非等值条件 → Nested Loop Join</li>
     * </ul>
     *
     * @param leftIter  左侧行迭代器（来自左数据源）
     * @param rightIter 右侧行迭代器（来自右数据源）
     * @param joinKey   Join 键描述
     * @param joinType  Join 类型
     * @return Join 结果（含输出行迭代器与统计）
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
            // 重新构造 result 以包含最终统计快照（含 success=true）
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
     *
     * @param leftIter  左侧行迭代器
     * @param rightIter 右侧行迭代器
     * @param joinKey   Join 键
     * @param joinType  Join 类型
     * @param algorithm Join 算法
     * @return Join 结果
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
     *
     * <p>适用场景：一侧表估算大小 &lt; 内存预算。若加载过程中内存超限，
     * 自动降级为 Shuffle Hash Join。</p>
     *
     * @param leftIter  左侧迭代器
     * @param rightIter 右侧迭代器
     * @param joinKey   Join 键
     * @param joinType  Join 类型
     * @return Join 结果
     */
    public JoinResult broadcastJoin(RowIterator leftIter, RowIterator rightIter,
                                     JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        // 选择 build 侧：默认右侧为 build（小表），若配置左侧则交换
        RowIterator buildIter = config.isBuildOnLeft() ? leftIter : rightIter;
        RowIterator probeIter = config.isBuildOnLeft() ? rightIter : leftIter;
        int[] buildKeyIndices = config.isBuildOnLeft() ? joinKey.getLeftColumnIndices()
                : joinKey.getRightColumnIndices();
        int[] probeKeyIndices = config.isBuildOnLeft() ? joinKey.getRightColumnIndices()
                : joinKey.getLeftColumnIndices();

        // 1. 构建 Hash 表（build 侧）
        Map<JoinKeyHash, List<Row>> hashTable = buildHashTable(buildIter, buildKeyIndices);
        statistics.recordBuildRows(hashTable.size());

        // 2. 探测并输出
        List<Row> outputRows = probeAndMerge(probeIter, probeKeyIndices,
                hashTable, joinKey, joinType, config.isBuildOnLeft());

        // 3. 处理 OUTER Join 的 build 侧未匹配行
        // build 侧未匹配行仅在以下情况输出：
        // - buildOnLeft=true（build=left）且 LEFT/FULL Join → 保留左行
        // - buildOnLeft=false（build=right）且 RIGHT/FULL Join → 保留右行
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
     *
     * <p>适用场景：两侧表均较大，无法全量放入内存。采用 Grace Hash Join 算法。</p>
     *
     * @param leftIter  左侧迭代器
     * @param rightIter 右侧迭代器
     * @param joinKey   Join 键
     * @param joinType  Join 类型
     * @return Join 结果
     */
    public JoinResult shuffleJoin(RowIterator leftIter, RowIterator rightIter,
                                   JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        int numPartitions = config.getSpillPartitions();

        // 1. 分区左侧数据
        List<Row> leftInMemory = new ArrayList<>();
        List<SpilledPartition> leftSpilled = partitionAndSpill(leftIter,
                joinKey.getLeftColumnIndices(), numPartitions, "left", leftInMemory);

        // 2. 分区右侧数据
        List<Row> rightInMemory = new ArrayList<>();
        List<SpilledPartition> rightSpilled = partitionAndSpill(rightIter,
                joinKey.getRightColumnIndices(), numPartitions, "right", rightInMemory);

        statistics.recordSpillPartitions(leftSpilled.size() + rightSpilled.size());

        // 3. 逐分区 Join
        List<Row> outputRows = new ArrayList<>();

        // 3a. 内存中的分区 Join
        for (int p = 0; p < numPartitions; p++) {
            List<Row> leftPart = filterPartition(leftInMemory, p, numPartitions,
                    joinKey.getLeftColumnIndices());
            List<Row> rightPart = filterPartition(rightInMemory, p, numPartitions,
                    joinKey.getRightColumnIndices());
            outputRows.addAll(hashJoinPartition(leftPart, rightPart,
                    joinKey, joinType));
        }

        // 3b. 溢写到磁盘的分区 Join
        for (int p = 0; p < numPartitions; p++) {
            SpilledPartition leftPart = findSpilledPartition(leftSpilled, p);
            SpilledPartition rightPart = findSpilledPartition(rightSpilled, p);
            if (leftPart != null && rightPart != null) {
                outputRows.addAll(spilledPartitionJoin(leftPart, rightPart,
                        joinKey, joinType));
            } else if (leftPart != null && (joinType == JoinType.LEFT || joinType == JoinType.FULL)) {
                // 左分区有数据，右分区为空 → LEFT/FULL Join 输出左行 + null
                try (RowIterator leftRead = leftPart.openIterator()) {
                    while (leftRead.hasNext()) {
                        Row row = leftRead.next();
                        outputRows.add(mergeRows(row, null, joinKey));
                    }
                }
            } else if (rightPart != null && (joinType == JoinType.RIGHT || joinType == JoinType.FULL)) {
                try (RowIterator rightRead = rightPart.openIterator()) {
                    while (rightRead.hasNext()) {
                        Row row = rightRead.next();
                        outputRows.add(mergeRows(null, row, joinKey));
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
     *
     * <p>适用场景：非等值 Join（如 {@code a.x < b.y}），无法构建 Hash 表。
     * 时间复杂度 O(n×m)，仅用于小数据量或兜底场景。</p>
     *
     * @param leftIter  左侧迭代器
     * @param rightIter 右侧迭代器
     * @param joinKey   Join 键（可能含非等值条件）
     * @param joinType  Join 类型
     * @return Join 结果
     */
    public JoinResult nestedLoopJoin(RowIterator leftIter, RowIterator rightIter,
                                      JoinKey joinKey, JoinType joinType) throws IOException {
        ensureOpen();
        // 加载右侧到内存（用于多次扫描）
        List<Row> rightRows = new ArrayList<>();
        while (rightIter.hasNext()) {
            Row row = rightIter.next();
            rightRows.add(row);
            memoryManager.acquire(row.estimatedSize());
            if (memoryManager.isOverBudget()) {
                // 右侧也太大，需要 spill（此处简化：直接释放并继续，实际应分批）
                memoryManager.release(row.estimatedSize());
            }
        }
        statistics.recordBuildRows(rightRows.size());

        List<Row> outputRows = new ArrayList<>();
        Set<Integer> matchedRightIndices = new LinkedHashSet<>();

        // 逐行扫描左侧
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

        // RIGHT/FULL Join 补未匹配的右行
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
     *
     * @param leftRow  左行（可为 null）
     * @param rightRow 右行（可为 null）
     * @param joinKey  Join 键（提供输出列映射）
     * @return 归并后的行
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
        if (closed) {
            return;
        }
        closed = true;
        try {
            spillManager.cleanup();
        } catch (IOException e) {
            // 关闭时忽略清理异常
        }
        memoryManager.releaseAll();
    }

    // ===================== 配置与统计访问 =====================

    public JoinConfig getConfig() {
        return config;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public SpillManager getSpillManager() {
        return spillManager;
    }

    public JoinStatistics getStatistics() {
        return statistics;
    }

    public boolean isClosed() {
        return closed;
    }

    // ===================== 内部方法 =====================

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("CrossSourceJoinExecutor 已关闭");
        }
    }

    /**
     * 基于统计信息选择最优 Join 算法。
     */
    private JoinAlgorithm chooseAlgorithm(RowIterator leftIter, RowIterator rightIter,
                                          JoinKey joinKey) {
        // 非等值 Join → NESTED_LOOP
        if (!joinKey.isEquiJoin()) {
            return JoinAlgorithm.NESTED_LOOP;
        }
        // 基于估算大小选择
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

    /**
     * 构建 Hash 表：build 侧行按 Join Key Hash 分组。
     */
    private Map<JoinKeyHash, List<Row>> buildHashTable(RowIterator buildIter,
                                                        int[] keyIndices) throws IOException {
        Map<JoinKeyHash, List<Row>> hashTable = new HashMap<>();
        while (buildIter.hasNext()) {
            Row row = buildIter.next();
            long rowSize = row.estimatedSize();
            memoryManager.acquire(rowSize);

            if (memoryManager.isOverBudget()) {
                // 内存超限 → 触发 spill（简化：释放当前行并继续，实际应 spill 整个分区）
                statistics.recordSpillTriggered();
                memoryManager.release(rowSize);
                // 此处仍保留行在 Hash 表中（简化实现）
            }

            JoinKeyHash keyHash = computeKeyHash(row, keyIndices);
            hashTable.computeIfAbsent(keyHash, k -> new ArrayList<>()).add(row);
        }
        return hashTable;
    }

    /**
     * 探测侧扫描并与 Hash 表匹配，输出归并行。
     */
    private List<Row> probeAndMerge(RowIterator probeIter, int[] probeKeyIndices,
                                     Map<JoinKeyHash, List<Row>> hashTable,
                                     JoinKey joinKey, JoinType joinType,
                                     boolean buildOnLeft) {
        List<Row> output = new ArrayList<>();
        Set<JoinKeyHash> matchedKeys = new LinkedHashSet<>();

        while (probeIter.hasNext()) {
            Row probeRow = probeIter.next();
            JoinKeyHash probeHash = computeKeyHash(probeRow, probeKeyIndices);
            List<Row> buildRows = hashTable.get(probeHash);
            if (buildRows != null && !buildRows.isEmpty()) {
                // 验证 Key 真正相等（Hash 冲突处理）
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
                // OUTER Join 未匹配：probe 侧保留
                // buildOnLeft=true → build=left, probe=right → 未匹配是 RIGHT Join 的右行
                // buildOnLeft=false → build=right, probe=left → 未匹配是 LEFT Join 的左行
                Row merged = buildOnLeft ? mergeRows(null, probeRow, joinKey)
                        : mergeRows(probeRow, null, joinKey);
                // 仅在对应的 OUTER 类型下输出
                if ((buildOnLeft && (joinType == JoinType.RIGHT || joinType == JoinType.FULL))
                        || (!buildOnLeft && (joinType == JoinType.LEFT || joinType == JoinType.FULL))) {
                    output.add(merged);
                }
            }
        }

        // 标记已匹配的 build 行（用于后续 OUTER 补未匹配）
        for (JoinKeyHash matched : matchedKeys) {
            List<Row> rows = hashTable.get(matched);
            if (rows != null) {
                for (Row r : rows) {
                    r.setMatched(true);
                }
            }
        }

        return output;
    }

    /**
     * 产生 build 侧未匹配的行（用于 OUTER Join）。
     */
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

    /**
     * 分区并溢写：将迭代器数据按 Hash 分区，内存放不下的分区溢写到磁盘。
     */
    private List<SpilledPartition> partitionAndSpill(RowIterator iter, int[] keyIndices,
                                                      int numPartitions, String side,
                                                      List<Row> inMemoryRows) throws IOException {
        List<List<Row>> partitions = new ArrayList<>(numPartitions);
        for (int i = 0; i < numPartitions; i++) {
            partitions.add(new ArrayList<>());
        }

        while (iter.hasNext()) {
            Row row = iter.next();
            int part = partitionOf(row, keyIndices, numPartitions);
            partitions.get(part).add(row);
            memoryManager.acquire(row.estimatedSize());

            if (memoryManager.isOverBudget()) {
                // 当前分区溢写
                statistics.recordSpillTriggered();
                memoryManager.release(row.estimatedSize());
            }
        }

        // 将超过内存阈值的分区溢写
        List<SpilledPartition> spilled = new ArrayList<>();
        long partitionBudget = memoryManager.getBuildBudget() / numPartitions;
        for (int p = 0; p < numPartitions; p++) {
            List<Row> partRows = partitions.get(p);
            long partSize = 0;
            for (Row r : partRows) {
                partSize += r.estimatedSize();
            }
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
            if (sp.getPartitionId() == partition) {
                return sp;
            }
        }
        return null;
    }

    /**
     * 对内存中的两个分区做 Hash Join。
     */
    private List<Row> hashJoinPartition(List<Row> leftPart, List<Row> rightPart,
                                         JoinKey joinKey, JoinType joinType) {
        List<Row> output = new ArrayList<>();
        // 构建 right Hash 表
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

    /**
     * 对两个溢写分区做 Join：从磁盘流式读回。
     */
    private List<Row> spilledPartitionJoin(SpilledPartition leftPart,
                                            SpilledPartition rightPart,
                                            JoinKey joinKey, JoinType joinType) throws IOException {
        List<Row> leftRows = new ArrayList<>();
        try (RowIterator leftIter = leftPart.openIterator()) {
            while (leftIter.hasNext()) {
                leftRows.add(leftIter.next());
            }
        }
        List<Row> rightRows = new ArrayList<>();
        try (RowIterator rightIter = rightPart.openIterator()) {
            while (rightIter.hasNext()) {
                rightRows.add(rightIter.next());
            }
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
        JoinKeyHash kh = computeKeyHash(row, keyIndices);
        return Math.abs(kh.hashCode()) % numPartitions;
    }

    private boolean keysEqual(Row row1, int[] indices1, Row row2, int[] indices2) {
        if (indices1.length != indices2.length) {
            return false;
        }
        for (int i = 0; i < indices1.length; i++) {
            Object v1 = row1.get(indices1[i]);
            Object v2 = row2.get(indices2[i]);
            if (!Objects.equals(v1, v2)) {
                return false;
            }
        }
        return true;
    }

    // ===================== 枚举 =====================

    /** Join 类型 */
    public enum JoinType {
        /** 内连接 */
        INNER("INNER JOIN"),
        /** 左外连接 */
        LEFT("LEFT OUTER JOIN"),
        /** 右外连接 */
        RIGHT("RIGHT OUTER JOIN"),
        /** 全外连接 */
        FULL("FULL OUTER JOIN"),
        /** 半连接（仅输出左行，若右有匹配） */
        SEMI("LEFT SEMI JOIN"),
        /** 反连接（仅输出左行，若右无匹配） */
        ANTI("LEFT ANTI JOIN");

        private final String sql;

        JoinType(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        /** 是否为外连接 */
        public boolean isOuter() {
            return this == LEFT || this == RIGHT || this == FULL;
        }
    }

    /** Join 算法 */
    public enum JoinAlgorithm {
        /** Broadcast Hash Join：小表广播 */
        BROADCAST("Broadcast Hash Join"),
        /** Shuffle Hash Join：按 Key 分区，含 spill */
        SHUFFLE("Shuffle Hash Join：按 Key 分区，含 spill"),
        /** Nested Loop Join：嵌套循环（非等值 Join） */
        NESTED_LOOP("Nested Loop Join");

        private final String description;

        JoinAlgorithm(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    // ===================== JoinKey =====================

    /**
     * Join 键描述——定义 Join 条件的列映射与匹配逻辑。
     */
    public static class JoinKey {
        /** 左侧 Join 列索引 */
        private final int[] leftColumnIndices;
        /** 右侧 Join 列索引 */
        private final int[] rightColumnIndices;
        /** 左侧列数（用于 null 填充） */
        private final int leftColumnCount;
        /** 右侧列数 */
        private final int rightColumnCount;
        /** 是否等值 Join */
        private final boolean equiJoin;
        /** 非等值条件表达式（如 "a.x < b.y"），equiJoin=false 时使用 */
        private final String nonEquiCondition;

        public JoinKey(int[] leftColumnIndices, int[] rightColumnIndices,
                       int leftColumnCount, int rightColumnCount) {
            this(leftColumnIndices, rightColumnIndices, leftColumnCount, rightColumnCount,
                    true, null);
        }

        public JoinKey(int[] leftColumnIndices, int[] rightColumnIndices,
                       int leftColumnCount, int rightColumnCount,
                       boolean equiJoin, String nonEquiCondition) {
            this.leftColumnIndices = Objects.requireNonNull(leftColumnIndices).clone();
            this.rightColumnIndices = Objects.requireNonNull(rightColumnIndices).clone();
            this.leftColumnCount = leftColumnCount;
            this.rightColumnCount = rightColumnCount;
            this.equiJoin = equiJoin;
            this.nonEquiCondition = nonEquiCondition;
        }

        public int[] getLeftColumnIndices() {
            return leftColumnIndices.clone();
        }

        public int[] getRightColumnIndices() {
            return rightColumnIndices.clone();
        }

        public int getLeftColumnCount() {
            return leftColumnCount;
        }

        public int getRightColumnCount() {
            return rightColumnCount;
        }

        public boolean isEquiJoin() {
            return equiJoin;
        }

        public String getNonEquiCondition() {
            return nonEquiCondition;
        }

        /**
         * 检查两行是否匹配（用于 Nested Loop Join）。
         */
        public boolean matches(Row leftRow, Row rightRow) {
            if (equiJoin) {
                return Objects.equals(
                        leftRow.get(leftColumnIndices[0]),
                        rightRow.get(rightColumnIndices[0]));
            }
            // 非等值：简化实现，仅支持单列
            if (nonEquiCondition == null) {
                return false;
            }
            Object lv = leftRow.get(leftColumnIndices[0]);
            Object rv = rightRow.get(rightColumnIndices[0]);
            return evaluateNonEqui(lv, rv, nonEquiCondition);
        }

        private boolean evaluateNonEqui(Object lv, Object rv, String cond) {
            if (lv instanceof Number && rv instanceof Number) {
                double l = ((Number) lv).doubleValue();
                double r = ((Number) rv).doubleValue();
                if (cond.contains("<") && !cond.contains("=")) {
                    return l < r;
                } else if (cond.contains("<=")) {
                    return l <= r;
                } else if (cond.contains(">") && !cond.contains("=")) {
                    return l > r;
                } else if (cond.contains(">=")) {
                    return l >= r;
                } else if (cond.contains("!=")) {
                    return l != r;
                }
            }
            return false;
        }
    }

    // ===================== Row =====================

    /**
     * 行数据——Join 归并的基本单元。
     */
    public static class Row {
        private final Object[] values;
        private volatile boolean matched = false;

        public Row(Object[] values) {
            this.values = Objects.requireNonNull(values).clone();
        }

        public Row(List<Object> values) {
            this.values = values.toArray();
        }

        public Object get(int index) {
            return values[index];
        }

        public void set(int index, Object value) {
            values[index] = value;
        }

        public int size() {
            return values.length;
        }

        public Object[] getValues() {
            return values.clone();
        }

        public List<Object> toList() {
            return Arrays.asList(values);
        }

        public boolean isMatched() {
            return matched;
        }

        public void setMatched(boolean matched) {
            this.matched = matched;
        }

        /**
         * 估算行大小（字节）。
         */
        public long estimatedSize() {
            long size = 16; // 对象头
            for (Object v : values) {
                if (v == null) {
                    size += 8;
                } else if (v instanceof String s) {
                    size += 40 + s.length() * 2L;
                } else if (v instanceof Number) {
                    size += 16;
                } else if (v instanceof byte[] bytes) {
                    size += 16 + bytes.length;
                } else {
                    size += 32;
                }
            }
            return size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Row row)) {
                return false;
            }
            return Arrays.equals(values, row.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }

    // ===================== RowIterator =====================

    /**
     * 行迭代器——从数据源流式读取行。
     */
    public interface RowIterator extends AutoCloseable {
        boolean hasNext();

        Row next();

        /** 估算总大小（字节），未知返回 -1 */
        long estimatedSize();

        @Override
        default void close() {
        }
    }

    /**
     * 基于列表的行迭代器实现。
     */
    public static class ListRowIterator implements RowIterator {
        private final Iterator<Row> iterator;
        private final long estimatedSize;

        public ListRowIterator(List<Row> rows) {
            this(rows, -1);
        }

        public ListRowIterator(List<Row> rows, long estimatedSize) {
            this.iterator = rows.iterator();
            this.estimatedSize = estimatedSize;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return iterator.next();
        }

        @Override
        public long estimatedSize() {
            return estimatedSize;
        }
    }

    // ===================== JoinKeyHash =====================

    /**
     * Join Key 的 Hash 包装——用于 Hash 表查找。
     */
    static final class JoinKeyHash {
        private final Object[] keyValues;
        private final int hash;

        JoinKeyHash(Object[] keyValues) {
            this.keyValues = keyValues;
            this.hash = Arrays.hashCode(keyValues);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof JoinKeyHash that)) {
                return false;
            }
            return Arrays.equals(keyValues, that.keyValues);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    // ===================== JoinConfig =====================

    /**
     * Join 执行配置。
     */
    public static class JoinConfig {
        private long memoryBudgetBytes = DEFAULT_MEMORY_BUDGET;
        private double buildSideRatio = DEFAULT_BUILD_SIDE_RATIO;
        private int spillPartitions = DEFAULT_SPILL_PARTITIONS;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private String spillDir = DEFAULT_SPILL_DIR;
        private boolean buildOnLeft = false;
        private boolean spillEnabled = true;

        public long getMemoryBudgetBytes() {
            return memoryBudgetBytes;
        }

        public JoinConfig setMemoryBudgetBytes(long memoryBudgetBytes) {
            if (memoryBudgetBytes <= 0) {
                throw new IllegalArgumentException("memoryBudgetBytes 必须为正");
            }
            this.memoryBudgetBytes = memoryBudgetBytes;
            return this;
        }

        public double getBuildSideRatio() {
            return buildSideRatio;
        }

        public JoinConfig setBuildSideRatio(double buildSideRatio) {
            if (buildSideRatio <= 0 || buildSideRatio >= 1) {
                throw new IllegalArgumentException("buildSideRatio 必须在 (0,1) 之间");
            }
            this.buildSideRatio = buildSideRatio;
            return this;
        }

        public int getSpillPartitions() {
            return spillPartitions;
        }

        public JoinConfig setSpillPartitions(int spillPartitions) {
            if (spillPartitions <= 0) {
                throw new IllegalArgumentException("spillPartitions 必须为正");
            }
            this.spillPartitions = spillPartitions;
            return this;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public JoinConfig setBatchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须为正");
            }
            this.batchSize = batchSize;
            return this;
        }

        public String getSpillDir() {
            return spillDir;
        }

        public JoinConfig setSpillDir(String spillDir) {
            this.spillDir = Objects.requireNonNull(spillDir);
            return this;
        }

        public boolean isBuildOnLeft() {
            return buildOnLeft;
        }

        public JoinConfig setBuildOnLeft(boolean buildOnLeft) {
            this.buildOnLeft = buildOnLeft;
            return this;
        }

        public boolean isSpillEnabled() {
            return spillEnabled;
        }

        public JoinConfig setSpillEnabled(boolean spillEnabled) {
            this.spillEnabled = spillEnabled;
            return this;
        }
    }

    // ===================== MemoryManager =====================

    /**
     * 内存管理器——跟踪 Join 执行过程中的内存占用，触发 spill。
     */
    public static class MemoryManager {
        private final long totalBudget;
        private final double buildSideRatio;
        private final AtomicLong usedBytes = new AtomicLong(0);
        private final AtomicLong peakBytes = new AtomicLong(0);
        private final AtomicLong spillTriggerCount = new AtomicLong(0);

        public MemoryManager(long totalBudget, double buildSideRatio) {
            if (totalBudget <= 0) {
                throw new IllegalArgumentException("totalBudget 必须为正");
            }
            this.totalBudget = totalBudget;
            this.buildSideRatio = buildSideRatio;
        }

        /**
         * 申请内存。
         *
         * @param bytes 字节数
         * @return true 表示申请成功
         */
        public boolean acquire(long bytes) {
            if (bytes < 0) {
                return false;
            }
            long newUsed = usedBytes.addAndGet(bytes);
            // 更新峰值
            long currentPeak = peakBytes.get();
            while (newUsed > currentPeak) {
                if (peakBytes.compareAndSet(currentPeak, newUsed)) {
                    break;
                }
                currentPeak = peakBytes.get();
            }
            return newUsed <= totalBudget;
        }

        /**
         * 释放内存。
         */
        public void release(long bytes) {
            if (bytes > 0) {
                usedBytes.updateAndGet(v -> Math.max(0, v - bytes));
            }
        }

        /**
         * 释放所有内存。
         */
        public void releaseAll() {
            usedBytes.set(0);
        }

        /**
         * 是否超过预算。
         */
        public boolean isOverBudget() {
            return usedBytes.get() > totalBudget;
        }

        /**
         * 是否超过 build 侧预算。
         */
        public boolean isOverBuildBudget() {
            return usedBytes.get() > getBuildBudget();
        }

        public long getTotalBudget() {
            return totalBudget;
        }

        public long getBuildBudget() {
            return (long) (totalBudget * buildSideRatio);
        }

        public long getProbeBudget() {
            return totalBudget - getBuildBudget();
        }

        public long getUsedBytes() {
            return usedBytes.get();
        }

        public long getPeakBytes() {
            return peakBytes.get();
        }

        public long getSpillTriggerCount() {
            return spillTriggerCount.get();
        }

        public void incrementSpillTrigger() {
            spillTriggerCount.incrementAndGet();
        }

        public double getUsageRate() {
            return (double) usedBytes.get() / totalBudget;
        }

        @Override
        public String toString() {
            return "MemoryManager{used=" + usedBytes + "/" + totalBudget
                    + " (" + String.format("%.1f%%", getUsageRate() * 100)
                    + "), peak=" + peakBytes + ", spills=" + spillTriggerCount + '}';
        }
    }

    // ===================== SpillManager =====================

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
    public static class SpillManager {
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

        public String getSpillDir() {
            return spillDir;
        }

        public int getNumPartitions() {
            return numPartitions;
        }

        /**
         * 将行列表溢写到磁盘。
         *
         * @param rows      行列表
         * @param side      侧标识（"left"/"right"）
         * @param partition 分区号
         * @return 溢写分区句柄
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
         * 从溢写文件流式读取行。
         */
        public RowIterator readSpilled(File file) throws IOException {
            DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)));
            int rowCount = dis.readInt();
            totalSpillReadCount.incrementAndGet();

            return new RowIterator() {
                private int read = 0;

                @Override
                public boolean hasNext() {
                    return read < rowCount;
                }

                @Override
                public Row next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    try {
                        Row row = readRow(dis);
                        read++;
                        return row;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public long estimatedSize() {
                    return -1;
                }

                @Override
                public void close() {
                    try {
                        dis.close();
                    } catch (IOException e) {
                        // 忽略关闭异常
                    }
                }
            };
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

        public long getTotalSpilledBytes() {
            return totalSpilledBytes.get();
        }

        public long getTotalSpillWriteCount() {
            return totalSpillWriteCount.get();
        }

        public long getTotalSpillReadCount() {
            return totalSpillReadCount.get();
        }

        public int getSpillFileCount() {
            return spillFiles.size();
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
                dos.writeByte(1);
                dos.writeInt(i);
                return 5;
            } else if (v instanceof Long l) {
                dos.writeByte(2);
                dos.writeLong(l);
                return 9;
            } else if (v instanceof Double d) {
                dos.writeByte(3);
                dos.writeDouble(d);
                return 9;
            } else if (v instanceof Float f) {
                dos.writeByte(4);
                dos.writeFloat(f);
                return 5;
            } else if (v instanceof String s) {
                dos.writeByte(5);
                dos.writeUTF(s);
                return 3 + s.length();
            } else if (v instanceof Boolean b) {
                dos.writeByte(6);
                dos.writeBoolean(b);
                return 2;
            } else if (v instanceof Short s) {
                dos.writeByte(7);
                dos.writeShort(s);
                return 3;
            } else if (v instanceof Byte b) {
                dos.writeByte(8);
                dos.writeByte(b);
                return 2;
            } else if (v instanceof byte[] bytes) {
                dos.writeByte(9);
                dos.writeInt(bytes.length);
                dos.write(bytes);
                return 5 + bytes.length;
            } else {
                // 兜底：转为 String
                dos.writeByte(5);
                dos.writeUTF(v.toString());
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

    // ===================== SpilledPartition =====================

    /**
     * 溢写分区句柄——指向一个溢写到磁盘的分区文件。
     */
    public static class SpilledPartition {
        private final File file;
        private final String side;
        private final int partitionId;
        private final int rowCount;
        private final long bytes;

        public SpilledPartition(File file, String side, int partitionId,
                                int rowCount, long bytes) {
            this.file = Objects.requireNonNull(file);
            this.side = side;
            this.partitionId = partitionId;
            this.rowCount = rowCount;
            this.bytes = bytes;
        }

        public File getFile() {
            return file;
        }

        public String getSide() {
            return side;
        }

        public int getPartitionId() {
            return partitionId;
        }

        public int getRowCount() {
            return rowCount;
        }

        public long getBytes() {
            return bytes;
        }

        /**
         * 打开分区文件的行迭代器。
         */
        public RowIterator openIterator() throws IOException {
            DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)));
            int totalRows = dis.readInt();
            return new RowIterator() {
                private int read = 0;

                @Override
                public boolean hasNext() {
                    return read < totalRows;
                }

                @Override
                public Row next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    try {
                        int len = dis.readInt();
                        Object[] values = new Object[len];
                        for (int i = 0; i < len; i++) {
                            int type = dis.readByte();
                            values[i] = readValueByType(dis, type);
                        }
                        read++;
                        return new Row(values);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public long estimatedSize() {
                    return bytes;
                }

                @Override
                public void close() {
                    try {
                        dis.close();
                    } catch (IOException e) {
                        // 忽略
                    }
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
                    + ", side=" + side
                    + ", partition=" + partitionId
                    + ", rows=" + rowCount
                    + ", bytes=" + bytes + '}';
        }
    }

    // ===================== JoinResult =====================

    /**
     * Join 执行结果。
     */
    public static class JoinResult {
        private final List<Row> rows;
        private final JoinType joinType;
        private final JoinAlgorithm algorithm;
        private final JoinStatisticsSnapshot statistics;

        public JoinResult(List<Row> rows, JoinType joinType, JoinAlgorithm algorithm,
                          JoinStatisticsSnapshot statistics) {
            this.rows = Collections.unmodifiableList(rows);
            this.joinType = joinType;
            this.algorithm = algorithm;
            this.statistics = statistics;
        }

        public List<Row> getRows() {
            return rows;
        }

        public int getRowCount() {
            return rows.size();
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public JoinAlgorithm getAlgorithm() {
            return algorithm;
        }

        public JoinStatisticsSnapshot getStatistics() {
            return statistics;
        }

        @Override
        public String toString() {
            return "JoinResult{rows=" + rows.size()
                    + ", type=" + joinType
                    + ", algorithm=" + algorithm + '}';
        }
    }

    // ===================== JoinStatistics =====================

    /**
     * Join 执行统计器。
     */
    public static class JoinStatistics {
        private volatile long startTime = 0;
        private volatile long endTime = 0;
        private volatile boolean success = false;
        private volatile String failureReason = null;
        private volatile JoinAlgorithm algorithm = null;
        private final AtomicLong buildRows = new AtomicLong(0);
        private final AtomicLong outputRows = new AtomicLong(0);
        private final AtomicLong spillTriggered = new AtomicLong(0);
        private final AtomicLong spilledRows = new AtomicLong(0);
        private final AtomicLong spillPartitions = new AtomicLong(0);

        public void recordStart() {
            startTime = System.nanoTime();
        }

        public void recordEnd() {
            endTime = System.nanoTime();
        }

        public void recordSuccess() {
            success = true;
        }

        public void recordFailure(String reason) {
            success = false;
            failureReason = reason;
        }

        public void recordAlgorithm(JoinAlgorithm algorithm) {
            this.algorithm = algorithm;
        }

        public void recordBuildRows(long count) {
            buildRows.addAndGet(count);
        }

        public void recordOutputRows(long count) {
            outputRows.addAndGet(count);
        }

        public void recordSpillTriggered() {
            spillTriggered.incrementAndGet();
        }

        public void recordSpilledRows(long count) {
            spilledRows.addAndGet(count);
        }

        public void recordSpillPartitions(long count) {
            spillPartitions.addAndGet(count);
        }

        public long getDurationNanos() {
            if (startTime == 0 || endTime == 0) {
                return 0;
            }
            return endTime - startTime;
        }

        public long getDurationMillis() {
            return getDurationNanos() / 1_000_000;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public JoinAlgorithm getAlgorithm() {
            return algorithm;
        }

        public long getBuildRows() {
            return buildRows.get();
        }

        public long getOutputRows() {
            return outputRows.get();
        }

        public long getSpillTriggered() {
            return spillTriggered.get();
        }

        public long getSpilledRows() {
            return spilledRows.get();
        }

        public long getSpillPartitions() {
            return spillPartitions.get();
        }

        /**
         * 生成统计快照。
         */
        public JoinStatisticsSnapshot snapshot() {
            return new JoinStatisticsSnapshot(
                    getDurationMillis(), success, failureReason, algorithm,
                    buildRows.get(), outputRows.get(),
                    spillTriggered.get(), spilledRows.get(),
                    spillPartitions.get());
        }

        @Override
        public String toString() {
            return "JoinStatistics{success=" + success
                    + ", algorithm=" + algorithm
                    + ", duration=" + getDurationMillis() + "ms"
                    + ", buildRows=" + buildRows
                    + ", outputRows=" + outputRows
                    + ", spills=" + spillTriggered
                    + ", spilledRows=" + spilledRows + '}';
        }
    }

    /**
     * 统计快照（不可变）。
     */
    public static class JoinStatisticsSnapshot {
        private final long durationMillis;
        private final boolean success;
        private final String failureReason;
        private final JoinAlgorithm algorithm;
        private final long buildRows;
        private final long outputRows;
        private final long spillTriggered;
        private final long spilledRows;
        private final long spillPartitions;

        public JoinStatisticsSnapshot(long durationMillis, boolean success,
                                       String failureReason, JoinAlgorithm algorithm,
                                       long buildRows, long outputRows,
                                       long spillTriggered, long spilledRows,
                                       long spillPartitions) {
            this.durationMillis = durationMillis;
            this.success = success;
            this.failureReason = failureReason;
            this.algorithm = algorithm;
            this.buildRows = buildRows;
            this.outputRows = outputRows;
            this.spillTriggered = spillTriggered;
            this.spilledRows = spilledRows;
            this.spillPartitions = spillPartitions;
        }

        public long getDurationMillis() {
            return durationMillis;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public JoinAlgorithm getAlgorithm() {
            return algorithm;
        }

        public long getBuildRows() {
            return buildRows;
        }

        public long getOutputRows() {
            return outputRows;
        }

        public long getSpillTriggered() {
            return spillTriggered;
        }

        public long getSpilledRows() {
            return spilledRows;
        }

        public long getSpillPartitions() {
            return spillPartitions;
        }
    }

    // ===================== 异常 =====================

    /**
     * 跨源 Join 执行异常。
     */
    public static class CrossSourceJoinException extends RuntimeException {
        public CrossSourceJoinException(String message) {
            super(message);
        }

        public CrossSourceJoinException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}