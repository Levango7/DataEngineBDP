package com.levango7.dataenginebdp.governance.collector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CollectionResult} 单元测试。
 */
class CollectionResultTest {

    @Test
    @DisplayName("success 工厂方法应创建成功结果")
    void success_shouldCreateSuccessResult() {
        CollectionResult r = CollectionResult.success(1L, "hive-prod", "HIVE");
        assertTrue(r.isSuccess());
        assertEquals(1L, r.getSourceId());
        assertEquals("hive-prod", r.getSourceName());
        assertEquals("HIVE", r.getSourceType());
        assertNotNull(r.getStartedAt());
        assertNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("failure 工厂方法应创建失败结果")
    void failure_shouldCreateFailureResult() {
        CollectionResult r = CollectionResult.failure(2L, "doris-dev", "DORIS", "connection refused");
        assertFalse(r.isSuccess());
        assertEquals(2L, r.getSourceId());
        assertEquals("doris-dev", r.getSourceName());
        assertEquals("DORIS", r.getSourceType());
        assertEquals("connection refused", r.getErrorMessage());
    }

    @Test
    @DisplayName("markFinished 应计算耗时并汇总表/列计数")
    void markFinished_shouldComputeDurationAndCounts() {
        CollectionResult r = CollectionResult.success(1L, "src", "HIVE");

        TableMetadata t1 = new TableMetadata();
        t1.setColumns(List.of(
                new ColumnMetadata("id", "INT", null, false, false, 1),
                new ColumnMetadata("name", "STRING", null, true, false, 2)
        ));
        TableMetadata t2 = new TableMetadata();
        t2.setColumns(List.of(
                new ColumnMetadata("ts", "BIGINT", null, false, false, 1)
        ));
        List<TableMetadata> tables = new ArrayList<>();
        tables.add(t1);
        tables.add(t2);
        r.setTables(tables);

        r.markFinished();
        assertNotNull(r.getFinishedAt());
        assertTrue(r.getDurationMs() >= 0);
        assertEquals(2, r.getTableCount());
        assertEquals(3, r.getColumnCount());
    }

    @Test
    @DisplayName("markFinished 在 tables 为 null 时不应抛异常")
    void markFinished_shouldHandleNullTables() {
        CollectionResult r = CollectionResult.success(1L, "src", "HIVE");
        r.setTables(null);
        assertDoesNotThrow(r::markFinished);
        assertEquals(0, r.getTableCount());
        assertEquals(0, r.getColumnCount());
    }
}