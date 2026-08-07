package com.shuqing.bigdata.sqlgateway.calcite.join;

import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinAlgorithm;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinConfig;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinKey;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinResult;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinStatistics;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.JoinType;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.MemoryManager;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.Row;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.RowIterator;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.SpillManager;
import com.shuqing.bigdata.sqlgateway.calcite.join.CrossSourceJoinExecutor.SpilledPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CrossSourceJoinExecutor} 单元测试——覆盖跨源 Join 归并、内存管理与 spill to disk。
 *
 * <p>测试覆盖维度：</p>
 * <ul>
 *   <li>基础 Join 功能：INNER/LEFT/RIGHT/FULL/SEMI/ANTI</li>
 *   <li>Join 算法：BROADCAST/SHUFFLE/NESTED_LOOP</li>
 *   <li>内存管理：预算跟踪、超限检测、释放</li>
 *   <li>Spill to disk：分区溢写、序列化、读回、清理</li>
 *   <li>配置与异常：参数校验、关闭后操作</li>
 *   <li>数据结构：Row/RowIterator/JoinKey/JoinKeyHash</li>
 *   <li>统计信息：记录与快照</li>
 *   <li>边界条件：空输入、null、单行</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class CrossSourceJoinExecutorTest {

    // ===================== 公共辅助方法 =====================

    /** 构造行 */
    private Row row(Object... values) {
        return new Row(values);
    }

    /** 构造行迭代器 */
    private RowIterator iter(List<Row> rows) {
        return new CrossSourceJoinExecutor.ListRowIterator(rows);
    }

    /** 构造行迭代器（带估算大小） */
    private RowIterator iter(List<Row> rows, long estimatedSize) {
        return new CrossSourceJoinExecutor.ListRowIterator(rows, estimatedSize);
    }

    /** 构造等值 JoinKey：左列[0] = 右列[0]，左2列右2列 */
    private JoinKey equiKey() {
        return new JoinKey(new int[]{0}, new int[]{0}, 2, 2);
    }

    /** 构造等值 JoinKey：左列[0] = 右列[0]，左3列右2列 */
    private JoinKey equiKey32() {
        return new JoinKey(new int[]{0}, new int[]{0}, 3, 2);
    }

    /** 构造非等值 JoinKey：左列[0] < 右列[0] */
    private JoinKey nonEquiKey() {
        return new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, "<");
    }

    /** 构造默认执行器 */
    private CrossSourceJoinExecutor executor() {
        return new CrossSourceJoinExecutor();
    }

    /** 构造小内存执行器（触发 spill） */
    private CrossSourceJoinExecutor smallMemoryExecutor() {
        JoinConfig config = new JoinConfig()
                .setMemoryBudgetBytes(512)  // 极小内存，强制 spill
                .setSpillPartitions(4);
        return new CrossSourceJoinExecutor(config);
    }

    // ===================== 基础 Join 功能测试 =====================

    @Nested
    @DisplayName("基础 Join 类型测试")
    class BasicJoinTypeTest {

        @Test
        @DisplayName("INNER Join：等值匹配输出归并行")
        void testInnerJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"), row(3, "carol"));
            List<Row> right = Arrays.asList(row(1, 100), row(2, 200), row(4, 400));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.INNER);

            assertEquals(2, result.getRowCount());
            assertEquals(JoinType.INNER, result.getJoinType());
            assertTrue(result.getStatistics().isSuccess());
            // 验证匹配的行：id=1 和 id=2
            List<Row> rows = result.getRows();
            assertRowContains(rows, 1, "alice", 1, 100);
            assertRowContains(rows, 2, "bob", 2, 200);
        }

        @Test
        @DisplayName("LEFT Join：左行全保留，右未匹配填 null")
        void testLeftJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"), row(3, "carol"));
            List<Row> right = Arrays.asList(row(1, 100), row(4, 400));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.LEFT);

            assertEquals(3, result.getRowCount());
            // id=3 未匹配，右填 null
            List<Row> rows = result.getRows();
            boolean hasNullRight = rows.stream()
                    .anyMatch(r -> r.get(0).equals(3) && r.get(2) == null && r.get(3) == null);
            assertTrue(hasNullRight, "LEFT Join 应保留未匹配左行，右填 null");
        }

        @Test
        @DisplayName("RIGHT Join：右行全保留，左未匹配填 null")
        void testRightJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"));
            List<Row> right = Arrays.asList(row(1, 100), row(4, 400));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.RIGHT);

            assertEquals(2, result.getRowCount());
            List<Row> rows = result.getRows();
            // id=4 未匹配，左填 null
            boolean hasNullLeft = rows.stream()
                    .anyMatch(r -> r.get(2).equals(4) && r.get(0) == null && r.get(1) == null);
            assertTrue(hasNullLeft, "RIGHT Join 应保留未匹配右行，左填 null");
        }

        @Test
        @DisplayName("FULL Join：双向未匹配全保留")
        void testFullJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"), row(5, "eve"));
            List<Row> right = Arrays.asList(row(1, 100), row(4, 400));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.FULL);

            // id=1 匹配(1) + id=2 左未匹配(1) + id=5 左未匹配(1) + id=4 右未匹配(1) = 4
            assertEquals(4, result.getRowCount());
        }

        @Test
        @DisplayName("空左输入 INNER Join 返回空结果")
        void testEmptyLeftInnerJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> right = Arrays.asList(row(1, 100));

            JoinResult result = executor.execute(iter(Collections.emptyList()), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(0, result.getRowCount());
            assertTrue(result.getStatistics().isSuccess());
        }

        @Test
        @DisplayName("空右输入 INNER Join 返回空结果")
        void testEmptyRightInnerJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"));

            JoinResult result = executor.execute(iter(left), iter(Collections.emptyList()),
                    equiKey(), JoinType.INNER);

            assertEquals(0, result.getRowCount());
        }

        @Test
        @DisplayName("双侧空输入返回空结果")
        void testBothEmptyJoin() {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.execute(iter(Collections.emptyList()),
                    iter(Collections.emptyList()), equiKey(), JoinType.INNER);
            assertEquals(0, result.getRowCount());
        }

        @Test
        @DisplayName("单行匹配 INNER Join")
        void testSingleRowMatch() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Collections.singletonList(row(1, "alice"));
            List<Row> right = Collections.singletonList(row(1, 100));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.INNER);

            assertEquals(1, result.getRowCount());
            Row r = result.getRows().get(0);
            assertEquals(1, r.get(0));
            assertEquals("alice", r.get(1));
            assertEquals(1, r.get(2));
            assertEquals(100, r.get(3));
        }

        @Test
        @DisplayName("多对多匹配：同一 Key 多行匹配")
        void testManyToManyMatch() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a1"), row(1, "a2"));
            List<Row> right = Arrays.asList(row(1, 10), row(1, 20));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.INNER);

            // 2 × 2 = 4 行
            assertEquals(4, result.getRowCount());
        }
    }

    // ===================== Join 算法测试 =====================

    @Nested
    @DisplayName("Join 算法选择测试")
    class JoinAlgorithmTest {

        @Test
        @DisplayName("指定 BROADCAST 算法执行")
        void testBroadcastAlgorithm() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"));
            List<Row> right = Arrays.asList(row(1, 100), row(2, 200));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(),
                    JoinType.INNER, JoinAlgorithm.BROADCAST);

            assertEquals(2, result.getRowCount());
            assertEquals(JoinAlgorithm.BROADCAST, result.getAlgorithm());
        }

        @Test
        @DisplayName("指定 SHUFFLE 算法执行")
        void testShuffleAlgorithm() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"), row(3, "carol"));
            List<Row> right = Arrays.asList(row(1, 100), row(3, 300));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(),
                    JoinType.INNER, JoinAlgorithm.SHUFFLE);

            assertEquals(2, result.getRowCount());
            assertEquals(JoinAlgorithm.SHUFFLE, result.getAlgorithm());
        }

        @Test
        @DisplayName("指定 NESTED_LOOP 算法执行等值 Join")
        void testNestedLoopEquiJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"));
            List<Row> right = Arrays.asList(row(1, 100), row(2, 200));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(),
                    JoinType.INNER, JoinAlgorithm.NESTED_LOOP);

            assertEquals(2, result.getRowCount());
            assertEquals(JoinAlgorithm.NESTED_LOOP, result.getAlgorithm());
        }

        @Test
        @DisplayName("非等值 Join 自动选择 NESTED_LOOP")
        void testNonEquiJoinAutoSelect() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(5, "b"));
            List<Row> right = Arrays.asList(row(3, 30), row(10, 100));

            JoinResult result = executor.execute(iter(left), iter(right), nonEquiKey(),
                    JoinType.INNER);

            assertEquals(JoinAlgorithm.NESTED_LOOP, result.getAlgorithm());
        }

        @Test
        @DisplayName("小表自动选择 BROADCAST")
        void testSmallTableAutoBroadcast() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"), row(2, "bob"));
            List<Row> right = Arrays.asList(row(1, 100));
            // 右侧估算 100 字节 < 内存预算
            JoinResult result = executor.execute(iter(left, 1000), iter(right, 100),
                    equiKey(), JoinType.INNER);

            assertEquals(JoinAlgorithm.BROADCAST, result.getAlgorithm());
        }

        @Test
        @DisplayName("大表自动选择 SHUFFLE")
        void testLargeTableAutoShuffle() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "alice"));
            List<Row> right = Arrays.asList(row(1, 100));
            // 两侧估算都很大 → SHUFFLE
            JoinResult result = executor.execute(iter(left, Long.MAX_VALUE),
                    iter(right, Long.MAX_VALUE), equiKey(), JoinType.INNER);

            assertEquals(JoinAlgorithm.SHUFFLE, result.getAlgorithm());
        }
    }

    // ===================== Nested Loop Join 测试 =====================

    @Nested
    @DisplayName("Nested Loop Join 测试")
    class NestedLoopJoinTest {

        @Test
        @DisplayName("非等值 Join：a.x < b.y")
        void testNonEquiLessThan() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(5, "b"));
            List<Row> right = Arrays.asList(row(3, 30), row(10, 100));

            JoinResult result = executor.execute(iter(left), iter(right), nonEquiKey(),
                    JoinType.INNER, JoinAlgorithm.NESTED_LOOP);

            // 1<3 ✓, 1<10 ✓, 5<3 ✗, 5<10 ✓ → 3 行
            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Nested Loop LEFT Join：未匹配保留左行")
        void testNestedLoopLeftJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(100, "b"));
            List<Row> right = Collections.singletonList(row(50, 500));

            JoinResult result = executor.execute(iter(left), iter(right), nonEquiKey(),
                    JoinType.LEFT, JoinAlgorithm.NESTED_LOOP);

            // 1<50 ✓, 100<50 ✗ → 1匹配 + 1未匹配 = 2
            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Nested Loop RIGHT Join：未匹配保留右行")
        void testNestedLoopRightJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Collections.singletonList(row(100, "a"));
            List<Row> right = Arrays.asList(row(50, 500), row(200, 2000));

            JoinResult result = executor.execute(iter(left), iter(right), nonEquiKey(),
                    JoinType.RIGHT, JoinAlgorithm.NESTED_LOOP);

            // 100<50 ✗, 100<200 ✓ → 1匹配 + 1未匹配 = 2
            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Nested Loop FULL Join")
        void testNestedLoopFullJoin() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(100, "a"), row(1, "b"));
            List<Row> right = Arrays.asList(row(50, 500));

            JoinResult result = executor.execute(iter(left), iter(right), nonEquiKey(),
                    JoinType.FULL, JoinAlgorithm.NESTED_LOOP);

            // 100<50 ✗(左未匹配), 1<50 ✓(匹配) → 1匹配 + 1左未匹配 + 0右未匹配 = 2
            assertEquals(2, result.getRowCount());
        }
    }

    // ===================== Shuffle Join 测试 =====================

    @Nested
    @DisplayName("Shuffle Join 测试")
    class ShuffleJoinTest {

        @Test
        @DisplayName("Shuffle INNER Join 基本匹配")
        void testShuffleInnerJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(
                    row(1, "a"), row(2, "b"), row(3, "c"), row(4, "d"), row(5, "e"));
            List<Row> right = Arrays.asList(
                    row(1, 10), row(3, 30), row(5, 50));

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Shuffle LEFT Join")
        void testShuffleLeftJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"), row(3, "c"));
            List<Row> right = Collections.singletonList(row(1, 10));

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.LEFT);

            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Shuffle FULL Join")
        void testShuffleFullJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 10), row(5, 50));

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.FULL);

            // 1匹配 + 1左未匹配(2) + 1右未匹配(5) = 3
            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Shuffle Join 空输入")
        void testShuffleEmptyInput() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.shuffleJoin(iter(Collections.emptyList()),
                    iter(Collections.emptyList()), equiKey(), JoinType.INNER);
            assertEquals(0, result.getRowCount());
        }
    }

    // ===================== 内存管理测试 =====================

    @Nested
    @DisplayName("内存管理测试")
    class MemoryManagerTest {

        @Test
        @DisplayName("内存预算跟踪：acquire/release")
        void testMemoryTracking() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            assertTrue(mm.acquire(300));
            assertEquals(300, mm.getUsedBytes());
            assertTrue(mm.acquire(300));
            assertEquals(600, mm.getUsedBytes());
            mm.release(200);
            assertEquals(400, mm.getUsedBytes());
        }

        @Test
        @DisplayName("内存超限检测")
        void testOverBudget() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(700);
            assertTrue(mm.isOverBuildBudget());  // 700 > 600
            assertFalse(mm.isOverBudget());       // 700 < 1000
            mm.acquire(400);
            assertTrue(mm.isOverBudget());        // 1100 > 1000
        }

        @Test
        @DisplayName("内存峰值记录")
        void testPeakBytes() {
            MemoryManager mm = new MemoryManager(10000, 0.6);
            mm.acquire(3000);
            mm.acquire(2000);
            mm.release(4000);
            mm.acquire(1000);
            assertEquals(5000, mm.getPeakBytes());
        }

        @Test
        @DisplayName("releaseAll 重置内存")
        void testReleaseAll() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(500);
            mm.releaseAll();
            assertEquals(0, mm.getUsedBytes());
        }

        @Test
        @DisplayName("build/probe 预算分配")
        void testBudgetSplit() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            assertEquals(600, mm.getBuildBudget());
            assertEquals(400, mm.getProbeBudget());
        }

        @Test
        @DisplayName("使用率计算")
        void testUsageRate() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(250);
            assertEquals(0.25, mm.getUsageRate(), 0.001);
        }

        @Test
        @DisplayName("acquire 负值返回 false")
        void testAcquireNegative() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            assertFalse(mm.acquire(-1));
            assertEquals(0, mm.getUsedBytes());
        }

        @Test
        @DisplayName("spill 触发计数")
        void testSpillTriggerCount() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.incrementSpillTrigger();
            mm.incrementSpillTrigger();
            assertEquals(2, mm.getSpillTriggerCount());
        }

        @Test
        @DisplayName("构造参数校验")
        void testConstructorValidation() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryManager(0, 0.6));
            assertThrows(IllegalArgumentException.class, () -> new MemoryManager(-1, 0.6));
        }

        @Test
        @DisplayName("toString 包含关键信息")
        void testToString() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(300);
            String s = mm.toString();
            assertTrue(s.contains("300"));
            assertTrue(s.contains("1000"));
        }
    }

    // ===================== Spill to Disk 测试 =====================

    @Nested
    @DisplayName("Spill to Disk 测试")
    class SpillManagerTest {

        private SpillManager spillManager;

        @BeforeEach
        void setUp() {
            spillManager = new SpillManager(System.getProperty("java.io.tmpdir"), 4);
        }

        @AfterEach
        void tearDown() throws IOException {
            spillManager.cleanup();
        }

        @Test
        @DisplayName("溢写与读回：行数据保持一致")
        void testSpillAndReadBack() throws IOException {
            List<Row> rows = Arrays.asList(
                    row(1, "alice", 100.0),
                    row(2, "bob", 200.0),
                    row(3, null, 300.0));

            SpilledPartition partition = spillManager.spill(rows, "left", 0);
            assertEquals(3, partition.getRowCount());
            assertEquals("left", partition.getSide());
            assertEquals(0, partition.getPartitionId());
            assertTrue(partition.getBytes() > 0);

            // 读回验证
            try (RowIterator readBack = partition.openIterator()) {
                List<Row> read = new ArrayList<>();
                while (readBack.hasNext()) {
                    read.add(readBack.next());
                }
                assertEquals(3, read.size());
                assertEquals(1, read.get(0).get(0));
                assertEquals("alice", read.get(0).get(1));
                assertEquals(100.0, read.get(0).get(2));
                assertEquals(2, read.get(1).get(0));
                assertEquals("bob", read.get(1).get(1));
                assertEquals(3, read.get(2).get(0));
                assertNull(read.get(2).get(1));
                assertEquals(300.0, read.get(2).get(2));
            }
        }

        @Test
        @DisplayName("溢写多种数据类型")
        void testSpillMultipleTypes() throws IOException {
            List<Row> rows = Collections.singletonList(
                    row(42, 100L, 3.14, "text", true, (short) 7, (byte) 1));

            SpilledPartition partition = spillManager.spill(rows, "test", 0);

            try (RowIterator readBack = partition.openIterator()) {
                Row r = readBack.next();
                assertEquals(42, r.get(0));
                assertEquals(100L, r.get(1));
                assertEquals(3.14, r.get(2));
                assertEquals("text", r.get(3));
                assertEquals(true, r.get(4));
                assertEquals((short) 7, r.get(5));
                assertEquals((byte) 1, r.get(6));
            }
        }

        @Test
        @DisplayName("溢写 byte[] 类型")
        void testSpillByteArray() throws IOException {
            List<Row> rows = Collections.singletonList(row(new byte[]{1, 2, 3, 4, 5}));
            SpilledPartition partition = spillManager.spill(rows, "test", 0);

            try (RowIterator readBack = partition.openIterator()) {
                Row r = readBack.next();
                byte[] bytes = (byte[]) r.get(0);
                assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, bytes);
            }
        }

        @Test
        @DisplayName("溢写空列表")
        void testSpillEmptyList() throws IOException {
            SpilledPartition partition = spillManager.spill(Collections.emptyList(), "test", 0);
            assertEquals(0, partition.getRowCount());

            try (RowIterator readBack = partition.openIterator()) {
                assertFalse(readBack.hasNext());
            }
        }

        @Test
        @DisplayName("溢写统计信息")
        void testSpillStatistics() throws IOException {
            List<Row> rows = Arrays.asList(row(1, "a"), row(2, "b"));
            spillManager.spill(rows, "left", 0);
            spillManager.spill(rows, "right", 1);

            assertEquals(2, spillManager.getSpillFileCount());
            assertTrue(spillManager.getTotalSpilledBytes() > 0);
            assertEquals(2, spillManager.getTotalSpillWriteCount());
        }

        @Test
        @DisplayName("清理临时文件")
        void testCleanup() throws IOException {
            List<Row> rows = Collections.singletonList(row(1, "a"));
            SpilledPartition p = spillManager.spill(rows, "test", 0);
            assertTrue(p.getFile().exists());

            spillManager.cleanup();
            assertFalse(p.getFile().exists());
            assertEquals(0, spillManager.getSpillFileCount());
        }

        @Test
        @DisplayName("SpilledPartition toString")
        void testSpilledPartitionToString() throws IOException {
            SpilledPartition p = spillManager.spill(Collections.singletonList(row(1)), "left", 2);
            String s = p.toString();
            assertTrue(s.contains("left"));
            assertTrue(s.contains("partition=2"));
        }

        @Test
        @DisplayName("readSpilled 方法读回")
        void testReadSpilledMethod() throws IOException {
            List<Row> rows = Arrays.asList(row(1, "a"), row(2, "b"));
            SpilledPartition p = spillManager.spill(rows, "test", 0);

            try (RowIterator iter = spillManager.readSpilled(p.getFile())) {
                List<Row> read = new ArrayList<>();
                while (iter.hasNext()) {
                    read.add(iter.next());
                }
                assertEquals(2, read.size());
                assertEquals(1, read.get(0).get(0));
                assertEquals("a", read.get(0).get(1));
            }
        }
    }

    // ===================== Spill 集成测试 =====================

    @Nested
    @DisplayName("Spill 集成测试")
    class SpillIntegrationTest {

        @Test
        @DisplayName("小内存执行 Shuffle Join 触发 spill")
        void testSmallMemoryShuffleJoin() {
            CrossSourceJoinExecutor executor = smallMemoryExecutor();
            List<Row> left = Arrays.asList(
                    row(1, "a"), row(2, "b"), row(3, "c"), row(4, "d"),
                    row(5, "e"), row(6, "f"), row(7, "g"), row(8, "h"));
            List<Row> right = Arrays.asList(
                    row(1, 10), row(3, 30), row(5, 50), row(7, 70));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(),
                    JoinType.INNER, JoinAlgorithm.SHUFFLE);

            assertEquals(4, result.getRowCount());
            assertTrue(result.getStatistics().isSuccess());
            executor.close();
        }

        @Test
        @DisplayName("小内存执行 BROADCAST Join")
        void testSmallMemoryBroadcastJoin() {
            CrossSourceJoinExecutor executor = smallMemoryExecutor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 10), row(2, 20));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(),
                    JoinType.INNER, JoinAlgorithm.BROADCAST);

            assertEquals(2, result.getRowCount());
            executor.close();
        }
    }

    // ===================== Row 测试 =====================

    @Nested
    @DisplayName("Row 数据结构测试")
    class RowTest {

        @Test
        @DisplayName("Row 基本操作")
        void testRowBasicOps() {
            Row r = row(1, "alice", 100.0);
            assertEquals(3, r.size());
            assertEquals(1, r.get(0));
            assertEquals("alice", r.get(1));
            assertEquals(100.0, r.get(2));
        }

        @Test
        @DisplayName("Row set 方法")
        void testRowSet() {
            Row r = row(1, "a");
            r.set(0, 2);
            r.set(1, "b");
            assertEquals(2, r.get(0));
            assertEquals("b", r.get(1));
        }

        @Test
        @DisplayName("Row estimatedSize")
        void testRowEstimatedSize() {
            Row r = row(1, "alice", 100.0);
            long size = r.estimatedSize();
            assertTrue(size > 0);
        }

        @Test
        @DisplayName("Row null 值大小")
        void testRowNullSize() {
            Row r = row(null, null);
            assertTrue(r.estimatedSize() > 0);
        }

        @Test
        @DisplayName("Row equals/hashCode")
        void testRowEquals() {
            Row r1 = row(1, "a");
            Row r2 = row(1, "a");
            Row r3 = row(2, "a");
            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
            assertNotEquals(r1, r3);
        }

        @Test
        @DisplayName("Row toList")
        void testRowToList() {
            Row r = row(1, "a", 2.0);
            List<Object> list = r.toList();
            assertEquals(3, list.size());
            assertEquals(1, list.get(0));
        }

        @Test
        @DisplayName("Row getValues 返回副本")
        void testRowGetValues() {
            Row r = row(1, "a");
            Object[] vals = r.getValues();
            vals[0] = 999;
            assertEquals(1, r.get(0));  // 原行不受影响
        }

        @Test
        @DisplayName("Row matched 标记")
        void testRowMatched() {
            Row r = row(1, "a");
            assertFalse(r.isMatched());
            r.setMatched(true);
            assertTrue(r.isMatched());
        }

        @Test
        @DisplayName("Row 从 List 构造")
        void testRowFromList() {
            Row r = new Row(Arrays.asList(1, "a", 2.0));
            assertEquals(3, r.size());
            assertEquals(1, r.get(0));
        }

        @Test
        @DisplayName("Row toString")
        void testRowToString() {
            Row r = row(1, "a");
            assertTrue(r.toString().contains("1"));
            assertTrue(r.toString().contains("a"));
        }
    }

    // ===================== RowIterator 测试 =====================

    @Nested
    @DisplayName("RowIterator 测试")
    class RowIteratorTest {

        @Test
        @DisplayName("ListRowIterator 基本迭代")
        void testListRowIterator() {
            List<Row> rows = Arrays.asList(row(1), row(2), row(3));
            RowIterator iter = new CrossSourceJoinExecutor.ListRowIterator(rows);

            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(3, count);
        }

        @Test
        @DisplayName("ListRowIterator next 超界抛异常")
        void testNextBeyondEnd() {
            RowIterator iter = new CrossSourceJoinExecutor.ListRowIterator(
                    Collections.singletonList(row(1)));
            iter.next();
            assertThrows(NoSuchElementException.class, iter::next);
        }

        @Test
        @DisplayName("ListRowIterator estimatedSize")
        void testEstimatedSize() {
            RowIterator iter = new CrossSourceJoinExecutor.ListRowIterator(
                    Collections.singletonList(row(1)), 500);
            assertEquals(500, iter.estimatedSize());
        }

        @Test
        @DisplayName("ListRowIterator 默认 estimatedSize 为 -1")
        void testDefaultEstimatedSize() {
            RowIterator iter = new CrossSourceJoinExecutor.ListRowIterator(
                    Collections.singletonList(row(1)));
            assertEquals(-1, iter.estimatedSize());
        }

        @Test
        @DisplayName("ListRowIterator close 不抛异常")
        void testClose() {
            RowIterator iter = new CrossSourceJoinExecutor.ListRowIterator(
                    Collections.singletonList(row(1)));
            assertDoesNotThrow(iter::close);
        }
    }

    // ===================== JoinKey 测试 =====================

    @Nested
    @DisplayName("JoinKey 测试")
    class JoinKeyTest {

        @Test
        @DisplayName("等值 JoinKey 属性")
        void testEquiJoinKey() {
            JoinKey key = new JoinKey(new int[]{0, 1}, new int[]{2, 3}, 4, 4);
            assertTrue(key.isEquiJoin());
            assertNull(key.getNonEquiCondition());
            assertArrayEquals(new int[]{0, 1}, key.getLeftColumnIndices());
            assertArrayEquals(new int[]{2, 3}, key.getRightColumnIndices());
            assertEquals(4, key.getLeftColumnCount());
            assertEquals(4, key.getRightColumnCount());
        }

        @Test
        @DisplayName("非等值 JoinKey")
        void testNonEquiJoinKey() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, "<");
            assertFalse(key.isEquiJoin());
            assertEquals("<", key.getNonEquiCondition());
        }

        @Test
        @DisplayName("JoinKey matches 等值匹配")
        void testMatchesEqui() {
            JoinKey key = equiKey();
            assertTrue(key.matches(row(1, "a"), row(1, 100)));
            assertFalse(key.matches(row(1, "a"), row(2, 100)));
        }

        @Test
        @DisplayName("JoinKey matches 非等值 <")
        void testMatchesLessThan() {
            JoinKey key = nonEquiKey();
            assertTrue(key.matches(row(1, "a"), row(5, 100)));
            assertFalse(key.matches(row(10, "a"), row(5, 100)));
        }

        @Test
        @DisplayName("JoinKey matches 非等值 <=")
        void testMatchesLessEqual() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, "<=");
            assertTrue(key.matches(row(5, "a"), row(5, 100)));
            assertTrue(key.matches(row(3, "a"), row(5, 100)));
            assertFalse(key.matches(row(10, "a"), row(5, 100)));
        }

        @Test
        @DisplayName("JoinKey matches 非等值 >")
        void testMatchesGreaterThan() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, ">");
            assertTrue(key.matches(row(10, "a"), row(5, 100)));
            assertFalse(key.matches(row(3, "a"), row(5, 100)));
        }

        @Test
        @DisplayName("JoinKey matches 非等值 >=")
        void testMatchesGreaterEqual() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, ">=");
            assertTrue(key.matches(row(5, "a"), row(5, 100)));
            assertTrue(key.matches(row(10, "a"), row(5, 100)));
            assertFalse(key.matches(row(3, "a"), row(5, 100)));
        }

        @Test
        @DisplayName("JoinKey matches 非等值 !=")
        void testMatchesNotEqual() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, "!=");
            assertTrue(key.matches(row(1, "a"), row(5, 100)));
            assertFalse(key.matches(row(5, "a"), row(5, 100)));
        }

        @Test
        @DisplayName("JoinKey 索引返回副本")
        void testIndicesAreCopies() {
            int[] left = {0};
            JoinKey key = new JoinKey(left, new int[]{0}, 2, 2);
            left[0] = 999;
            assertEquals(0, key.getLeftColumnIndices()[0]);
        }

        @Test
        @DisplayName("JoinKey matches 非数字非等值返回 false")
        void testNonNumericNonEqui() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, "<");
            assertFalse(key.matches(row("a", "x"), row("b", "y")));
        }

        @Test
        @DisplayName("JoinKey matches null 条件返回 false")
        void testNullConditionNonEqui() {
            JoinKey key = new JoinKey(new int[]{0}, new int[]{0}, 2, 2, false, null);
            assertFalse(key.matches(row(1, "a"), row(2, "b")));
        }
    }

    // ===================== JoinConfig 测试 =====================

    @Nested
    @DisplayName("JoinConfig 测试")
    class JoinConfigTest {

        @Test
        @DisplayName("默认配置值")
        void testDefaultConfig() {
            JoinConfig config = new JoinConfig();
            assertEquals(CrossSourceJoinExecutor.DEFAULT_MEMORY_BUDGET, config.getMemoryBudgetBytes());
            assertEquals(CrossSourceJoinExecutor.DEFAULT_BUILD_SIDE_RATIO, config.getBuildSideRatio());
            assertEquals(CrossSourceJoinExecutor.DEFAULT_SPILL_PARTITIONS, config.getSpillPartitions());
            assertEquals(CrossSourceJoinExecutor.DEFAULT_BATCH_SIZE, config.getBatchSize());
            assertEquals(CrossSourceJoinExecutor.DEFAULT_SPILL_DIR, config.getSpillDir());
            assertFalse(config.isBuildOnLeft());
            assertTrue(config.isSpillEnabled());
        }

        @Test
        @DisplayName("链式设置")
        void testChainedSet() {
            JoinConfig config = new JoinConfig()
                    .setMemoryBudgetBytes(1024)
                    .setBuildSideRatio(0.5)
                    .setSpillPartitions(8)
                    .setBatchSize(1024)
                    .setSpillDir("/tmp")
                    .setBuildOnLeft(true)
                    .setSpillEnabled(false);
            assertEquals(1024, config.getMemoryBudgetBytes());
            assertEquals(0.5, config.getBuildSideRatio());
            assertEquals(8, config.getSpillPartitions());
            assertEquals(1024, config.getBatchSize());
            assertTrue(config.isBuildOnLeft());
            assertFalse(config.isSpillEnabled());
        }

        @Test
        @DisplayName("参数校验：memoryBudgetBytes")
        void testInvalidMemoryBudget() {
            JoinConfig config = new JoinConfig();
            assertThrows(IllegalArgumentException.class,
                    () -> config.setMemoryBudgetBytes(0));
            assertThrows(IllegalArgumentException.class,
                    () -> config.setMemoryBudgetBytes(-1));
        }

        @Test
        @DisplayName("参数校验：buildSideRatio")
        void testInvalidBuildSideRatio() {
            JoinConfig config = new JoinConfig();
            assertThrows(IllegalArgumentException.class,
                    () -> config.setBuildSideRatio(0));
            assertThrows(IllegalArgumentException.class,
                    () -> config.setBuildSideRatio(1));
            assertThrows(IllegalArgumentException.class,
                    () -> config.setBuildSideRatio(-0.1));
            assertThrows(IllegalArgumentException.class,
                    () -> config.setBuildSideRatio(1.1));
        }

        @Test
        @DisplayName("参数校验：spillPartitions")
        void testInvalidSpillPartitions() {
            JoinConfig config = new JoinConfig();
            assertThrows(IllegalArgumentException.class,
                    () -> config.setSpillPartitions(0));
            assertThrows(IllegalArgumentException.class,
                    () -> config.setSpillPartitions(-1));
        }

        @Test
        @DisplayName("参数校验：batchSize")
        void testInvalidBatchSize() {
            JoinConfig config = new JoinConfig();
            assertThrows(IllegalArgumentException.class,
                    () -> config.setBatchSize(0));
        }
    }

    // ===================== JoinStatistics 测试 =====================

    @Nested
    @DisplayName("JoinStatistics 测试")
    class JoinStatisticsTest {

        @Test
        @DisplayName("统计记录与快照")
        void testStatisticsRecord() {
            JoinStatistics stats = new JoinStatistics();
            stats.recordStart();
            stats.recordAlgorithm(JoinAlgorithm.BROADCAST);
            stats.recordBuildRows(100);
            stats.recordOutputRows(50);
            stats.recordSuccess();
            stats.recordEnd();

            assertTrue(stats.isSuccess());
            assertEquals(JoinAlgorithm.BROADCAST, stats.getAlgorithm());
            assertEquals(100, stats.getBuildRows());
            assertEquals(50, stats.getOutputRows());
            assertTrue(stats.getDurationMillis() >= 0);

            var snapshot = stats.snapshot();
            assertTrue(snapshot.isSuccess());
            assertEquals(JoinAlgorithm.BROADCAST, snapshot.getAlgorithm());
            assertEquals(100, snapshot.getBuildRows());
            assertEquals(50, snapshot.getOutputRows());
        }

        @Test
        @DisplayName("失败记录")
        void testFailureRecord() {
            JoinStatistics stats = new JoinStatistics();
            stats.recordStart();
            stats.recordFailure("OOM");
            stats.recordEnd();

            assertFalse(stats.isSuccess());
            assertEquals("OOM", stats.getFailureReason());
        }

        @Test
        @DisplayName("spill 统计")
        void testSpillStatistics() {
            JoinStatistics stats = new JoinStatistics();
            stats.recordSpillTriggered();
            stats.recordSpillTriggered();
            stats.recordSpilledRows(500);
            stats.recordSpillPartitions(4);

            assertEquals(2, stats.getSpillTriggered());
            assertEquals(500, stats.getSpilledRows());
            assertEquals(4, stats.getSpillPartitions());
        }

        @Test
        @DisplayName("toString 包含信息")
        void testToString() {
            JoinStatistics stats = new JoinStatistics();
            stats.recordSuccess();
            stats.recordAlgorithm(JoinAlgorithm.SHUFFLE);
            String s = stats.toString();
            assertTrue(s.contains("success=true"));
            assertTrue(s.contains("SHUFFLE"));
        }

        @Test
        @DisplayName("未开始时 duration 为 0")
        void testZeroDuration() {
            JoinStatistics stats = new JoinStatistics();
            assertEquals(0, stats.getDurationNanos());
            assertEquals(0, stats.getDurationMillis());
        }
    }

    // ===================== JoinResult 测试 =====================

    @Nested
    @DisplayName("JoinResult 测试")
    class JoinResultTest {

        @Test
        @DisplayName("JoinResult 基本属性")
        void testJoinResultProperties() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 10), row(2, 20));

            JoinResult result = executor.execute(iter(left), iter(right), equiKey(), JoinType.INNER);

            assertEquals(JoinType.INNER, result.getJoinType());
            assertNotNull(result.getAlgorithm());
            assertNotNull(result.getStatistics());
            assertEquals(2, result.getRowCount());
            assertEquals(2, result.getRows().size());
        }

        @Test
        @DisplayName("JoinResult rows 不可变")
        void testUnmodifiableRows() {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.execute(
                    iter(Collections.singletonList(row(1, "a"))),
                    iter(Collections.singletonList(row(1, 10))),
                    equiKey(), JoinType.INNER);

            assertThrows(UnsupportedOperationException.class,
                    () -> result.getRows().add(row(99)));
        }

        @Test
        @DisplayName("JoinResult toString")
        void testToString() {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.execute(
                    iter(Collections.singletonList(row(1, "a"))),
                    iter(Collections.singletonList(row(1, 10))),
                    equiKey(), JoinType.INNER);
            String s = result.toString();
            assertTrue(s.contains("INNER"));
        }
    }

    // ===================== 异常与边界测试 =====================

    @Nested
    @DisplayName("异常与边界测试")
    class ExceptionAndBoundaryTest {

        @Test
        @DisplayName("关闭后执行抛异常")
        void testExecuteAfterClose() {
            CrossSourceJoinExecutor executor = executor();
            executor.close();
            assertTrue(executor.isClosed());
            assertThrows(IllegalStateException.class, () ->
                    executor.execute(iter(Collections.emptyList()),
                            iter(Collections.emptyList()), equiKey(), JoinType.INNER));
        }

        @Test
        @DisplayName("重复 close 不抛异常")
        void testDoubleClose() {
            CrossSourceJoinExecutor executor = executor();
            executor.close();
            assertDoesNotThrow(executor::close);
        }

        @Test
        @DisplayName("null 参数校验：leftIter")
        void testNullLeftIter() {
            CrossSourceJoinExecutor executor = executor();
            assertThrows(NullPointerException.class, () ->
                    executor.execute(null, iter(Collections.emptyList()),
                            equiKey(), JoinType.INNER));
        }

        @Test
        @DisplayName("null 参数校验：rightIter")
        void testNullRightIter() {
            CrossSourceJoinExecutor executor = executor();
            assertThrows(NullPointerException.class, () ->
                    executor.execute(iter(Collections.emptyList()), null,
                            equiKey(), JoinType.INNER));
        }

        @Test
        @DisplayName("null 参数校验：joinKey")
        void testNullJoinKey() {
            CrossSourceJoinExecutor executor = executor();
            assertThrows(NullPointerException.class, () ->
                    executor.execute(iter(Collections.emptyList()),
                            iter(Collections.emptyList()), null, JoinType.INNER));
        }

        @Test
        @DisplayName("null 参数校验：joinType")
        void testNullJoinType() {
            CrossSourceJoinExecutor executor = executor();
            assertThrows(NullPointerException.class, () ->
                    executor.execute(iter(Collections.emptyList()),
                            iter(Collections.emptyList()), equiKey(), null));
        }

        @Test
        @DisplayName("null 参数校验：config")
        void testNullConfig() {
            assertThrows(NullPointerException.class, () -> new CrossSourceJoinExecutor((JoinConfig) null));
        }

        @Test
        @DisplayName("null 参数校验：algorithm")
        void testNullAlgorithm() {
            CrossSourceJoinExecutor executor = executor();
            assertThrows(NullPointerException.class, () ->
                    executor.execute(iter(Collections.emptyList()),
                            iter(Collections.emptyList()), equiKey(), JoinType.INNER, null));
        }

        @Test
        @DisplayName("CrossSourceJoinException 构造")
        void testExceptionConstruction() {
            CrossSourceJoinExecutor.CrossSourceJoinException e1 =
                    new CrossSourceJoinExecutor.CrossSourceJoinException("msg");
            assertEquals("msg", e1.getMessage());

            Exception cause = new RuntimeException("cause");
            CrossSourceJoinExecutor.CrossSourceJoinException e2 =
                    new CrossSourceJoinExecutor.CrossSourceJoinException("msg", cause);
            assertEquals("msg", e2.getMessage());
            assertEquals(cause, e2.getCause());
        }
    }

    // ===================== JoinType 枚举测试 =====================

    @Nested
    @DisplayName("JoinType 枚举测试")
    class JoinTypeTest {

        @Test
        @DisplayName("JoinType SQL 表示")
        void testJoinTypeSql() {
            assertEquals("INNER JOIN", JoinType.INNER.sql());
            assertEquals("LEFT OUTER JOIN", JoinType.LEFT.sql());
            assertEquals("RIGHT OUTER JOIN", JoinType.RIGHT.sql());
            assertEquals("FULL OUTER JOIN", JoinType.FULL.sql());
            assertEquals("LEFT SEMI JOIN", JoinType.SEMI.sql());
            assertEquals("LEFT ANTI JOIN", JoinType.ANTI.sql());
        }

        @Test
        @DisplayName("JoinType isOuter")
        void testIsOuter() {
            assertFalse(JoinType.INNER.isOuter());
            assertTrue(JoinType.LEFT.isOuter());
            assertTrue(JoinType.RIGHT.isOuter());
            assertTrue(JoinType.FULL.isOuter());
            assertFalse(JoinType.SEMI.isOuter());
            assertFalse(JoinType.ANTI.isOuter());
        }
    }

    // ===================== JoinAlgorithm 枚举测试 =====================

    @Nested
    @DisplayName("JoinAlgorithm 枚举测试")
    class JoinAlgorithmTest2 {

        @Test
        @DisplayName("JoinAlgorithm description")
        void testDescription() {
            assertNotNull(JoinAlgorithm.BROADCAST.description());
            assertNotNull(JoinAlgorithm.SHUFFLE.description());
            assertNotNull(JoinAlgorithm.NESTED_LOOP.description());
            assertTrue(JoinAlgorithm.BROADCAST.description().contains("Broadcast"));
            assertTrue(JoinAlgorithm.SHUFFLE.description().contains("Shuffle"));
            assertTrue(JoinAlgorithm.NESTED_LOOP.description().contains("Nested"));
        }
    }

    // ===================== mergeRows 测试 =====================

    @Nested
    @DisplayName("mergeRows 测试")
    class MergeRowsTest {

        @Test
        @DisplayName("合并两行")
        void testMergeBoth() {
            CrossSourceJoinExecutor executor = executor();
            Row left = row(1, "alice");
            Row right = row(1, 100);
            Row merged = executor.mergeRows(left, right, equiKey());

            assertEquals(4, merged.size());
            assertEquals(1, merged.get(0));
            assertEquals("alice", merged.get(1));
            assertEquals(1, merged.get(2));
            assertEquals(100, merged.get(3));
        }

        @Test
        @DisplayName("合并左行与 null 右行")
        void testMergeLeftNullRight() {
            CrossSourceJoinExecutor executor = executor();
            Row left = row(1, "alice");
            Row merged = executor.mergeRows(left, null, equiKey());

            assertEquals(4, merged.size());
            assertEquals(1, merged.get(0));
            assertEquals("alice", merged.get(1));
            assertNull(merged.get(2));
            assertNull(merged.get(3));
        }

        @Test
        @DisplayName("合并 null 左行与右行")
        void testMergeNullLeftRight() {
            CrossSourceJoinExecutor executor = executor();
            Row right = row(1, 100);
            Row merged = executor.mergeRows(null, right, equiKey());

            assertEquals(4, merged.size());
            assertNull(merged.get(0));
            assertNull(merged.get(1));
            assertEquals(1, merged.get(2));
            assertEquals(100, merged.get(3));
        }

        @Test
        @DisplayName("合并不同列数")
        void testMergeDifferentCols() {
            CrossSourceJoinExecutor executor = executor();
            Row left = row(1, "alice", "extra");
            Row right = row(1, 100);
            Row merged = executor.mergeRows(left, right, equiKey32());

            assertEquals(5, merged.size());
            assertEquals(1, merged.get(0));
            assertEquals("alice", merged.get(1));
            assertEquals("extra", merged.get(2));
            assertEquals(1, merged.get(3));
            assertEquals(100, merged.get(4));
        }
    }

    // ===================== 访问器测试 =====================

    @Nested
    @DisplayName("访问器测试")
    class AccessorTest {

        @Test
        @DisplayName("getConfig 返回配置")
        void testGetConfig() {
            JoinConfig config = new JoinConfig().setMemoryBudgetBytes(2048);
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
            assertEquals(2048, executor.getConfig().getMemoryBudgetBytes());
        }

        @Test
        @DisplayName("getMemoryManager 返回管理器")
        void testGetMemoryManager() {
            CrossSourceJoinExecutor executor = executor();
            assertNotNull(executor.getMemoryManager());
            assertEquals(CrossSourceJoinExecutor.DEFAULT_MEMORY_BUDGET,
                    executor.getMemoryManager().getTotalBudget());
        }

        @Test
        @DisplayName("getSpillManager 返回管理器")
        void testGetSpillManager() {
            CrossSourceJoinExecutor executor = executor();
            assertNotNull(executor.getSpillManager());
        }

        @Test
        @DisplayName("getStatistics 返回统计器")
        void testGetStatistics() {
            CrossSourceJoinExecutor executor = executor();
            assertNotNull(executor.getStatistics());
        }
    }

    // ===================== 辅助断言 =====================

    private void assertRowContains(List<Row> rows, Object... expectedValues) {
        boolean found = rows.stream().anyMatch(r -> {
            if (r.size() < expectedValues.length) {
                return false;
            }
            for (int i = 0; i < expectedValues.length; i++) {
                if (!java.util.Objects.equals(expectedValues[i], r.get(i))) {
                    return false;
                }
            }
            return true;
        });
        assertTrue(found, "未找到期望行: " + Arrays.toString(expectedValues));
    }

    // ===================== 补充覆盖率测试 =====================

    @Nested
    @DisplayName("Broadcast Join 补充测试")
    class BroadcastJoinSupplementTest {

        @Test
        @DisplayName("Broadcast LEFT Join with buildOnLeft=false")
        void testBroadcastLeftJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"), row(3, "c"));
            List<Row> right = Collections.singletonList(row(1, 100));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.LEFT);

            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast RIGHT Join with buildOnLeft=false")
        void testBroadcastRightJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Collections.singletonList(row(1, "a"));
            List<Row> right = Arrays.asList(row(1, 100), row(5, 500));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.RIGHT);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast FULL Join with buildOnLeft=false")
        void testBroadcastFullJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(3, "c"));
            List<Row> right = Arrays.asList(row(1, 100), row(5, 500));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.FULL);

            // 1匹配 + 1左未匹配(3) + 1右未匹配(5) = 3
            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast INNER Join with buildOnLeft=true")
        void testBroadcastInnerBuildOnLeft() throws IOException {
            JoinConfig config = new JoinConfig().setBuildOnLeft(true);
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 100), row(2, 200));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast LEFT Join with buildOnLeft=true")
        void testBroadcastLeftBuildOnLeft() throws IOException {
            JoinConfig config = new JoinConfig().setBuildOnLeft(true);
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Collections.singletonList(row(1, 100));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.LEFT);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast RIGHT Join with buildOnLeft=true")
        void testBroadcastRightBuildOnLeft() throws IOException {
            JoinConfig config = new JoinConfig().setBuildOnLeft(true);
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 100), row(5, 500));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.RIGHT);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast FULL Join with buildOnLeft=true")
        void testBroadcastFullBuildOnLeft() throws IOException {
            JoinConfig config = new JoinConfig().setBuildOnLeft(true);
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(config);
            List<Row> left = Arrays.asList(row(1, "a"), row(3, "c"));
            List<Row> right = Arrays.asList(row(1, 100), row(5, 500));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.FULL);

            assertEquals(3, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast 空输入")
        void testBroadcastEmpty() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.broadcastJoin(iter(Collections.emptyList()),
                    iter(Collections.emptyList()), equiKey(), JoinType.INNER);
            assertEquals(0, result.getRowCount());
        }

        @Test
        @DisplayName("Broadcast 多对多匹配")
        void testBroadcastManyToMany() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a1"), row(1, "a2"));
            List<Row> right = Arrays.asList(row(1, 10), row(1, 20));

            JoinResult result = executor.broadcastJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(4, result.getRowCount());
        }
    }

    @Nested
    @DisplayName("Shuffle Join 补充测试")
    class ShuffleJoinSupplementTest {

        @Test
        @DisplayName("Shuffle RIGHT Join")
        void testShuffleRightJoin() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Collections.singletonList(row(1, "a"));
            List<Row> right = Arrays.asList(row(1, 10), row(5, 50));

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.RIGHT);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Shuffle Join 多对多")
        void testShuffleManyToMany() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a1"), row(1, "a2"));
            List<Row> right = Arrays.asList(row(1, 10), row(1, 20));

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(4, result.getRowCount());
        }

        @Test
        @DisplayName("Shuffle Join 大数据集触发内存跟踪")
        void testShuffleLargeDataset() throws IOException {
            CrossSourceJoinExecutor executor = new CrossSourceJoinExecutor(
                    new JoinConfig().setMemoryBudgetBytes(1024).setSpillPartitions(2));
            List<Row> left = new ArrayList<>();
            List<Row> right = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                left.add(row(i, "name" + i));
                right.add(row(i, i * 10));
            }

            JoinResult result = executor.shuffleJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(20, result.getRowCount());
            executor.close();
        }
    }

    @Nested
    @DisplayName("Nested Loop Join 补充测试")
    class NestedLoopSupplementTest {

        @Test
        @DisplayName("Nested Loop INNER Join 空输入")
        void testNestedLoopEmpty() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            JoinResult result = executor.nestedLoopJoin(iter(Collections.emptyList()),
                    iter(Collections.emptyList()), equiKey(), JoinType.INNER);
            assertEquals(0, result.getRowCount());
        }

        @Test
        @DisplayName("Nested Loop INNER 等值多匹配")
        void testNestedLoopEquiMulti() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 10), row(2, 20), row(3, 30));

            JoinResult result = executor.nestedLoopJoin(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            assertEquals(2, result.getRowCount());
        }

        @Test
        @DisplayName("Nested Loop 非等值空右输入 LEFT Join")
        void testNestedLoopEmptyRightLeft() throws IOException {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));

            JoinResult result = executor.nestedLoopJoin(iter(left),
                    iter(Collections.emptyList()), nonEquiKey(), JoinType.LEFT);

            assertEquals(2, result.getRowCount());
        }
    }

    @Nested
    @DisplayName("统计快照补充测试")
    class StatisticsSnapshotTest {

        @Test
        @DisplayName("JoinStatisticsSnapshot 所有 getter")
        void testSnapshotGetters() {
            var snap = new CrossSourceJoinExecutor.JoinStatisticsSnapshot(
                    100, true, null, JoinAlgorithm.BROADCAST,
                    50, 30, 2, 100, 4);
            assertEquals(100, snap.getDurationMillis());
            assertTrue(snap.isSuccess());
            assertNull(snap.getFailureReason());
            assertEquals(JoinAlgorithm.BROADCAST, snap.getAlgorithm());
            assertEquals(50, snap.getBuildRows());
            assertEquals(30, snap.getOutputRows());
            assertEquals(2, snap.getSpillTriggered());
            assertEquals(100, snap.getSpilledRows());
            assertEquals(4, snap.getSpillPartitions());
        }

        @Test
        @DisplayName("执行后统计快照包含完整信息")
        void testFullStatisticsAfterExecution() {
            CrossSourceJoinExecutor executor = executor();
            List<Row> left = Arrays.asList(row(1, "a"), row(2, "b"));
            List<Row> right = Arrays.asList(row(1, 10), row(2, 20));

            JoinResult result = executor.execute(iter(left), iter(right),
                    equiKey(), JoinType.INNER);

            var snap = result.getStatistics();
            assertTrue(snap.isSuccess());
            assertNotNull(snap.getAlgorithm());
            assertTrue(snap.getOutputRows() >= 0);
            assertTrue(snap.getBuildRows() >= 0);
        }
    }

    @Nested
    @DisplayName("SpillManager 补充测试")
    class SpillManagerSupplementTest {

        @Test
        @DisplayName("SpillManager 属性访问")
        void testSpillManagerProperties() {
            SpillManager sm = new SpillManager("/tmp/test", 8);
            assertEquals("/tmp/test", sm.getSpillDir());
            assertEquals(8, sm.getNumPartitions());
        }

        @Test
        @DisplayName("SpillManager 多次溢写统计")
        void testMultipleSpillStats() throws IOException {
            SpillManager sm = new SpillManager(System.getProperty("java.io.tmpdir"), 4);
            try {
                List<Row> rows = Arrays.asList(row(1, "a"), row(2, "b"));
                sm.spill(rows, "left", 0);
                sm.spill(rows, "left", 1);
                sm.spill(rows, "right", 0);

                assertEquals(3, sm.getSpillFileCount());
                assertEquals(3, sm.getTotalSpillWriteCount());
                assertTrue(sm.getTotalSpilledBytes() > 0);
            } finally {
                sm.cleanup();
            }
        }

        @Test
        @DisplayName("SpillManager readSpilled 统计计数")
        void testReadSpillStats() throws IOException {
            SpillManager sm = new SpillManager(System.getProperty("java.io.tmpdir"), 4);
            try {
                List<Row> rows = Arrays.asList(row(1, "a"), row(2, "b"));
                var p = sm.spill(rows, "test", 0);
                try (var iter = sm.readSpilled(p.getFile())) {
                    while (iter.hasNext()) {
                        iter.next();
                    }
                }
                assertEquals(1, sm.getTotalSpillReadCount());
            } finally {
                sm.cleanup();
            }
        }
    }

    @Nested
    @DisplayName("MemoryManager 补充测试")
    class MemoryManagerSupplementTest {

        @Test
        @DisplayName("release 超过 used 不变负")
        void testReleaseExceed() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(100);
            mm.release(500);
            assertEquals(0, mm.getUsedBytes());
        }

        @Test
        @DisplayName("release 零值不操作")
        void testReleaseZero() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(100);
            mm.release(0);
            assertEquals(100, mm.getUsedBytes());
        }

        @Test
        @DisplayName("isOverBuildBudget 未超")
        void testNotOverBuildBudget() {
            MemoryManager mm = new MemoryManager(1000, 0.6);
            mm.acquire(500);
            assertFalse(mm.isOverBuildBudget());
        }

        @Test
        @DisplayName("多次 acquire 更新峰值")
        void testMultipleAcquirePeak() {
            MemoryManager mm = new MemoryManager(10000, 0.6);
            mm.acquire(1000);
            mm.release(500);
            mm.acquire(2000);
            assertEquals(2500, mm.getPeakBytes());
        }
    }
}