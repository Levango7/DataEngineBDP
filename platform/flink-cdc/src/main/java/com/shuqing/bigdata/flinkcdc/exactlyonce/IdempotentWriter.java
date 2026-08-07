package com.shuqing.bigdata.flinkcdc.exactlyonce;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 幂等写入器，基于主键或版本号对 {@link ChangeRecord} 去重，保证故障恢复后重放无副作用。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>主键去重（{@link ExactlyOnceConfig.IdempotentStrategy#PRIMARY_KEY}）</b>：
 *       相同主键的记录只保留最新版本，按写入顺序后者覆盖前者</li>
 *   <li><b>版本号去重（{@link ExactlyOnceConfig.IdempotentStrategy#VERSION}）</b>：
 *       版本号单调递增，仅接受比已写入版本更高的记录</li>
 *   <li><b>事务 LSN 去重（{@link ExactlyOnceConfig.IdempotentStrategy#TXN_LSN}）</b>：
 *       按 Binlog 文件+位点去重，仅接受比已写入 LSN 更靠后的记录</li>
 * </ul>
 *
 * <p><b>exactly-once 保障原理：</b></p>
 * <p>故障恢复后，Flink 从最近 checkpoint 重放记录。幂等写入器通过去重保证：
 * 重放的记录与首次写入的记录产生相同结果，不会因重复写入导致数据重复或状态错乱。
 * 配合 {@link TransactionalSink} 的事务原子性，实现端到端 exactly-once。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * IdempotentWriter writer = IdempotentWriter.builder()
 *     .strategy(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY)
 *     .primaryKeyColumns("id")
 *     .build();
 *
 * // 写入（自动去重）
 * writer.write(record1);
 * writer.write(record2);  // 若与 record1 主键相同，覆盖
 *
 * // 获取去重后的有效记录
 * List<ChangeRecord> effective = writer.drainPending();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class IdempotentWriter implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(IdempotentWriter.class);

    private final ExactlyOnceConfig.IdempotentStrategy strategy;
    private final List<String> primaryKeyColumns;
    private final String versionColumn;

    /** 已写入记录的去重键 → 最新版本号（VERSION 策略）/ LSN（TXN_LSN 策略）。 */
    private final Map<String, Long> committedVersion = new HashMap<>();
    /** 已写入记录的去重键 → 已提交记录的 LSN（TXN_LSN 策略）。 */
    private final Map<String, Long> committedLsn = new HashMap<>();
    /** 当前待写入（去重后）的记录缓冲：去重键 → 记录。 */
    private final Map<String, ChangeRecord> pendingBuffer = new LinkedHashMap<>();
    /** 已写入的记录总数（含被覆盖的，用于统计）。 */
    private final AtomicLong totalAttempted = new AtomicLong(0);
    /** 实际写入的记录总数（去重后）。 */
    private final AtomicLong totalEffective = new AtomicLong(0);
    /** 被去重丢弃的记录总数。 */
    private final AtomicLong totalDeduplicated = new AtomicLong(0);

    private IdempotentWriter(ExactlyOnceConfig.IdempotentStrategy strategy,
                             List<String> primaryKeyColumns,
                             String versionColumn) {
        this.strategy = strategy;
        this.primaryKeyColumns = primaryKeyColumns;
        this.versionColumn = versionColumn;
    }

    /**
     * 写入单条记录，自动去重。
     *
     * @param record 变更记录
     * @return true 表示记录被接受（新记录或更高版本）；false 表示被去重丢弃
     */
    public boolean write(ChangeRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        totalAttempted.incrementAndGet();

        String dedupKey = computeDedupKey(record);
        if (dedupKey == null) {
            log.warn("记录无法计算去重键，按非幂等方式写入: {}", record);
            pendingBuffer.put("__no_key__" + System.nanoTime(), record);
            totalEffective.incrementAndGet();
            return true;
        }

        switch (strategy) {
            case PRIMARY_KEY -> {
                // 主键去重：后者覆盖前者，返回 true 表示记录被接受（覆盖旧版本）
                ChangeRecord previous = pendingBuffer.put(dedupKey, record);
                if (previous != null) {
                    totalDeduplicated.incrementAndGet();
                    log.trace("主键去重：覆盖旧记录 key={}", dedupKey);
                } else {
                    totalEffective.incrementAndGet();
                }
                return true;
            }
            case VERSION -> {
                long version = extractVersion(record);
                Long committed = committedVersion.get(dedupKey);
                ChangeRecord pending = pendingBuffer.get(dedupKey);
                long pendingVersion = pending == null ? Long.MIN_VALUE : extractVersion(pending);
                long baseline = Math.max(
                        committed == null ? Long.MIN_VALUE : committed,
                        pendingVersion);
                if (version > baseline) {
                    pendingBuffer.put(dedupKey, record);
                    totalEffective.incrementAndGet();
                    return true;
                } else {
                    totalDeduplicated.incrementAndGet();
                    log.trace("版本号去重：丢弃旧版本 key={}, version={}, baseline={}",
                            dedupKey, version, baseline);
                    return false;
                }
            }
            case TXN_LSN -> {
                long lsn = extractLsn(record);
                Long committed = committedLsn.get(dedupKey);
                ChangeRecord pending = pendingBuffer.get(dedupKey);
                long pendingLsn = pending == null ? Long.MIN_VALUE : extractLsn(pending);
                long baseline = Math.max(
                        committed == null ? Long.MIN_VALUE : committed,
                        pendingLsn);
                if (lsn > baseline) {
                    pendingBuffer.put(dedupKey, record);
                    totalEffective.incrementAndGet();
                    return true;
                } else {
                    totalDeduplicated.incrementAndGet();
                    log.trace("LSN 去重：丢弃旧位点 key={}, lsn={}, baseline={}",
                            dedupKey, lsn, baseline);
                    return false;
                }
            }
            default -> {
                pendingBuffer.put(dedupKey + ":" + System.nanoTime(), record);
                totalEffective.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * 批量写入记录，自动去重。
     *
     * @param records 记录集合
     * @return 实际被接受的记录数
     */
    public int writeAll(Collection<ChangeRecord> records) {
        Objects.requireNonNull(records, "records 不能为 null");
        int accepted = 0;
        for (ChangeRecord record : records) {
            if (write(record)) {
                accepted++;
            }
        }
        return accepted;
    }

    /**
     * 获取当前去重后的待写入记录，并清空缓冲。
     *
     * @return 去重后的记录列表（按写入顺序）
     */
    public List<ChangeRecord> drainPending() {
        if (pendingBuffer.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChangeRecord> drained = new ArrayList<>(pendingBuffer.values());
        pendingBuffer.clear();
        return drained;
    }

    /**
     * 获取当前去重后的待写入记录（不清空缓冲，仅快照）。
     *
     * @return 去重后的记录列表（只读视图）
     */
    public List<ChangeRecord> snapshotPending() {
        return new ArrayList<>(pendingBuffer.values());
    }

    /**
     * 标记当前缓冲中的记录为已提交，更新去重基线。
     *
     * <p>在事务 commit 成功后调用，使后续重放的记录能被正确去重。</p>
     */
    public void markCommitted() {
        for (ChangeRecord record : pendingBuffer.values()) {
            String key = computeDedupKey(record);
            if (key == null) {
                continue;
            }
            if (strategy == ExactlyOnceConfig.IdempotentStrategy.VERSION) {
                committedVersion.put(key, extractVersion(record));
            } else if (strategy == ExactlyOnceConfig.IdempotentStrategy.TXN_LSN) {
                committedLsn.put(key, extractLsn(record));
            }
        }
        pendingBuffer.clear();
    }

    /**
     * 回滚当前缓冲（事务 abort 后调用）。
     */
    public void rollback() {
        pendingBuffer.clear();
    }

    /**
     * 计算记录的去重键。
     *
     * @param record 变更记录
     * @return 去重键；无法计算返回 {@code null}
     */
    String computeDedupKey(ChangeRecord record) {
        switch (strategy) {
            case PRIMARY_KEY, VERSION -> {
                if (primaryKeyColumns.isEmpty()) {
                    return null;
                }
                Map<String, Object> payload = selectPayload(record);
                if (payload == null) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (String col : primaryKeyColumns) {
                    Object val = payload.get(col);
                    if (val == null) {
                        return null;
                    }
                    if (sb.length() > 0) {
                        sb.append('|');
                    }
                    sb.append(val);
                }
                return sb.toString();
            }
            case TXN_LSN -> {
                Map<String, Object> source = record.getSource();
                if (source == null) {
                    return null;
                }
                Object file = source.get("file");
                return file == null ? null : String.valueOf(file);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 选择记录的有效载荷（after 优先，DELETE 时用 before）。
     *
     * @param record 变更记录
     * @return 载荷 Map；不存在返回 {@code null}
     */
    private Map<String, Object> selectPayload(ChangeRecord record) {
        if (record.getAfter() != null) {
            return record.getAfter();
        }
        if (record.getBefore() != null) {
            return record.getBefore();
        }
        return null;
    }

    /**
     * 提取记录的版本号。
     *
     * @param record 变更记录
     * @return 版本号；无法提取返回 {@code Long.MIN_VALUE}
     */
    long extractVersion(ChangeRecord record) {
        Map<String, Object> payload = selectPayload(record);
        if (payload == null || versionColumn == null) {
            return Long.MIN_VALUE;
        }
        Object val = payload.get(versionColumn);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(String.valueOf(val));
            } catch (NumberFormatException e) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * 提取记录的 Binlog LSN（file + pos）。
     *
     * @param record 变更记录
     * @return LSN；无法提取返回 {@code Long.MIN_VALUE}
     */
    long extractLsn(ChangeRecord record) {
        Map<String, Object> source = record.getSource();
        if (source == null) {
            return Long.MIN_VALUE;
        }
        Object pos = source.get("pos");
        if (pos instanceof Number n) {
            return n.longValue();
        }
        if (pos != null) {
            try {
                return Long.parseLong(String.valueOf(pos));
            } catch (NumberFormatException e) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    // ===== 统计访问器 =====

    public long getTotalAttempted() {
        return totalAttempted.get();
    }

    public long getTotalEffective() {
        return totalEffective.get();
    }

    public long getTotalDeduplicated() {
        return totalDeduplicated.get();
    }

    public int getPendingCount() {
        return pendingBuffer.size();
    }

    public ExactlyOnceConfig.IdempotentStrategy getStrategy() {
        return strategy;
    }

    public List<String> getPrimaryKeyColumns() {
        return Collections.unmodifiableList(primaryKeyColumns);
    }

    public String getVersionColumn() {
        return versionColumn;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * IdempotentWriter 构造器。
     */
    public static final class Builder {
        private ExactlyOnceConfig.IdempotentStrategy strategy =
                ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY;
        private List<String> primaryKeyColumns = new ArrayList<>();
        private String versionColumn;

        /** 去重策略。 */
        public Builder strategy(ExactlyOnceConfig.IdempotentStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy);
            return this;
        }

        /** 主键列（逗号分隔）。 */
        public Builder primaryKeyColumns(String columns) {
            Objects.requireNonNull(columns, "primaryKeyColumns 不能为 null");
            this.primaryKeyColumns = parseColumns(columns);
            return this;
        }

        /** 主键列（数组）。 */
        public Builder primaryKeyColumns(String... columns) {
            Objects.requireNonNull(columns, "primaryKeyColumns 不能为 null");
            this.primaryKeyColumns = Arrays.asList(columns);
            return this;
        }

        /** 版本号列。 */
        public Builder versionColumn(String column) {
            this.versionColumn = column;
            return this;
        }

        /** 从 ExactlyOnceConfig 复制配置。 */
        public Builder fromConfig(ExactlyOnceConfig config) {
            Objects.requireNonNull(config, "config 不能为 null");
            this.strategy = config.getIdempotentStrategy();
            if (config.getPrimaryKeyColumns() != null) {
                this.primaryKeyColumns = parseColumns(config.getPrimaryKeyColumns());
            }
            this.versionColumn = config.getVersionColumn();
            return this;
        }

        /** 构建 IdempotentWriter。 */
        public IdempotentWriter build() {
            return new IdempotentWriter(strategy,
                    new ArrayList<>(primaryKeyColumns),
                    versionColumn);
        }

        private static List<String> parseColumns(String columns) {
            if (columns.isEmpty()) {
                return new ArrayList<>();
            }
            String[] parts = columns.split(",");
            Set<String> seen = new HashSet<>();
            List<String> result = new ArrayList<>();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && seen.add(trimmed)) {
                    result.add(trimmed);
                }
            }
            return result;
        }
    }
}