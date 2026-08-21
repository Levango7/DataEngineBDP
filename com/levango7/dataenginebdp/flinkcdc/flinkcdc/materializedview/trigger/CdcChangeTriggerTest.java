package com.shuqing.bigdata.flinkcdc.materializedview.trigger;

import com.shuqing.bigdata.flinkcdc.materializedview.model.AggregationType;
import com.shuqing.bigdata.flinkcdc.materializedview.model.MaterializedViewDef;
import com.shuqing.bigdata.flinkcdc.materializedview.model.RefreshPolicy;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CdcChangeTrigger} 单元测试。
 *
 * @author shuqing-bigdata
 */
class CdcChangeTriggerTest {

    private MaterializedViewDef eventTriggeredView(String name, int threshold) {
        return MaterializedViewDef.builder()
                .name(name).database("d").targetTable("t_" + name)
                .addSourceTable("shop.orders")
                .addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(RefreshPolicy.eventTriggered(threshold, Duration.ofMillis(100)))
                .build();
    }

    private ChangeRecord insertRecord(String db, String table) {
        Map<String, Object> after = Map.of("region", "east", "total", 100);
        Map<String, Object> source = Map.of("db", db, "table", table);
        return new ChangeRecord(null, after, "c", source, System.currentTimeMillis());
    }

    @Nested
    @DisplayName("初始化与映射")
    class InitTest {

        @Test
        @DisplayName("仅注册 EVENT_TRIGGERED 模式视图")
        void init_onlyEventTriggered() {
            MaterializedViewDef eventView = eventTriggeredView("mv_event", 10);
            MaterializedViewDef scheduledView = MaterializedViewDef.builder()
                    .name("mv_sched").database("d").targetTable("t")
                    .addSourceTable("shop.products").addDimension("cat")
                    .addMetric("cnt", AggregationType.COUNT)
                    .refreshPolicy(RefreshPolicy.scheduled(Duration.ofMinutes(5)))
                    .build();
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(eventView, scheduledView));
            assertThat(trigger.getTableToViewMapping()).containsKey("shop.orders");
            assertThat(trigger.getTableToViewMapping()).doesNotContainKey("shop.products");
        }

        @Test
        @DisplayName("禁用的视图不注册")
        void init_disabledView() {
            MaterializedViewDef view = eventTriggeredView("mv", 10);
            view.setEnabled(false);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            assertThat(trigger.getTableToViewMapping()).isEmpty();
        }
    }

    @Nested
    @DisplayName("onChange — 变更处理")
    class OnChangeTest {

        @Test
        @DisplayName("达到阈值触发刷新")
        void onChange_reachThreshold() {
            MaterializedViewDef view = eventTriggeredView("mv", 3);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            AtomicInteger eventCount = new AtomicInteger(0);
            trigger.registerHandler(e -> eventCount.incrementAndGet());
            trigger.start();

            trigger.onChange(insertRecord("shop", "orders"));
            trigger.onChange(insertRecord("shop", "orders"));
            assertThat(eventCount.get()).isZero();  // 还未达到阈值

            trigger.onChange(insertRecord("shop", "orders"));
            assertThat(eventCount.get()).isEqualTo(1);  // 达到阈值触发
        }

        @Test
        @DisplayName("未启动时不处理变更")
        void onChange_notRunning() {
            MaterializedViewDef view = eventTriggeredView("mv", 1);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            AtomicInteger eventCount = new AtomicInteger(0);
            trigger.registerHandler(e -> eventCount.incrementAndGet());
            // 未调用 start()
            trigger.onChange(insertRecord("shop", "orders"));
            assertThat(eventCount.get()).isZero();
        }

        @Test
        @DisplayName("不匹配的表名不触发")
        void onChange_unmatchedTable() {
            MaterializedViewDef view = eventTriggeredView("mv", 1);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            AtomicInteger eventCount = new AtomicInteger(0);
            trigger.registerHandler(e -> eventCount.incrementAndGet());
            trigger.start();

            trigger.onChange(insertRecord("shop", "products"));
            assertThat(eventCount.get()).isZero();
        }

        @Test
        @DisplayName("触发后计数器重置")
        void onChange_counterReset() {
            MaterializedViewDef view = eventTriggeredView("mv", 2);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            trigger.registerHandler(e -> {});
            trigger.start();

            trigger.onChange(insertRecord("shop", "orders"));
            trigger.onChange(insertRecord("shop", "orders"));
            assertThat(trigger.getChangeCount("mv")).isZero();  // 触发后重置

            trigger.onChange(insertRecord("shop", "orders"));
            assertThat(trigger.getChangeCount("mv")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("shouldTrigger — 去抖检查")
    class ShouldTriggerTest {

        @Test
        @DisplayName("未达阈值 — 返回 false")
        void shouldTrigger_belowThreshold() {
            MaterializedViewDef view = eventTriggeredView("mv", 100);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            assertThat(trigger.shouldTrigger("mv", 50, view.getRefreshPolicy())).isFalse();
        }

        @Test
        @DisplayName("达到阈值 — 返回 true")
        void shouldTrigger_atThreshold() {
            MaterializedViewDef view = eventTriggeredView("mv", 100);
            CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(view));
            assertThat(trigger.shouldTrigger("mv", 100, view.getRefreshPolicy())).isTrue();
        }
    }

    @Nested
    @DisplayName("extractTableName — 表名提取")
    class ExtractTableNameTest {

        @Test
        @DisplayName("正常提取 db.table")
        void extractTableName_normal() {
            ChangeRecord record = insertRecord("shop", "orders");
            assertThat(CdcChangeTrigger.extractTableName(record)).isEqualTo("shop.orders");
        }

        @Test
        @DisplayName("source 为 null — 返回 null")
        void extractTableName_nullSource() {
            ChangeRecord record = new ChangeRecord(null, Map.of(), "c", null, 0L);
            assertThat(CdcChangeTrigger.extractTableName(record)).isNull();
        }

        @Test
        @DisplayName("db/table 缺失 — 返回 null")
        void extractTableName_missingFields() {
            ChangeRecord record = new ChangeRecord(null, Map.of(), "c", Map.of("db", "shop"), 0L);
            assertThat(CdcChangeTrigger.extractTableName(record)).isNull();
        }
    }

    @Test
    @DisplayName("生命周期 — start/stop/isRunning")
    void lifecycle() {
        CdcChangeTrigger trigger = new CdcChangeTrigger(List.of(eventTriggeredView("mv", 1)));
        assertThat(trigger.isRunning()).isFalse();
        trigger.start();
        assertThat(trigger.isRunning()).isTrue();
        trigger.start();  // 重复启动无效果
        assertThat(trigger.isRunning()).isTrue();
        trigger.stop();
        assertThat(trigger.isRunning()).isFalse();
    }
}