package com.shuqing.bigdata.flinkcdc.materializedview.refresh;

import com.shuqing.bigdata.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.shuqing.bigdata.flinkcdc.materializedview.model.AggregationType;
import com.shuqing.bigdata.flinkcdc.materializedview.model.MaterializedViewDef;
import com.shuqing.bigdata.flinkcdc.materializedview.model.RefreshPolicy;
import com.shuqing.bigdata.flinkcdc.materializedview.trigger.RefreshEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ViewRefresher} 单元测试。
 *
 * @author shuqing-bigdata
 */
class ViewRefresherTest {

    private MaterializedViewDef sampleView(String name) {
        return MaterializedViewDef.builder()
                .name(name).database("report").targetTable("mv_" + name)
                .addSourceTable("shop.orders").addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(RefreshPolicy.manual())
                .build();
    }

    private MaterializedViewDef sampleViewWithRetry(String name, int maxRetries) {
        RefreshPolicy policy = RefreshPolicy.manual();
        policy.setAutoRetry(true);
        policy.setMaxRetries(maxRetries);
        return MaterializedViewDef.builder()
                .name(name).database("report").targetTable("mv_" + name)
                .addSourceTable("shop.orders").addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(policy)
                .build();
    }

    @Nested
    @DisplayName("refresh — 刷新执行")
    class RefreshTest {

        @Test
        @DisplayName("成功刷新 — 返回成功结果")
        void refresh_success() {
            MaterializedViewDef def = sampleView("mv1");
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            AtomicInteger sqlExecCount = new AtomicInteger(0);
            Function<String, Boolean> sqlExecutor = sql -> {
                sqlExecCount.incrementAndGet();
                return true;
            };
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sqlExecutor);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getRetryCount()).isZero();
            assertThat(result.getErrorMessage()).isNull();
            assertThat(sqlExecCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("视图未找到 — 返回失败结果")
        void refresh_viewNotFound() {
            ViewRefresher refresher = new ViewRefresher(
                    name -> null, new MaterializedViewConfig(), sql -> true);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("unknown", "test"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("未找到");
        }

        @Test
        @DisplayName("SQL 执行失败 — 无重试策略返回失败")
        void refresh_sqlFails_noRetry() {
            MaterializedViewDef def = sampleView("mv1");  // manual 默认不重试
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sql -> false);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getRetryCount()).isZero();
        }
    }

    @Nested
    @DisplayName("失败重试")
    class RetryTest {

        @Test
        @DisplayName("重试后成功 — 返回成功结果")
        void retry_thenSuccess() {
            MaterializedViewDef def = sampleViewWithRetry("mv1", 3);
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            AtomicInteger attempt = new AtomicInteger(0);
            Function<String, Boolean> sqlExecutor = sql -> attempt.incrementAndGet() >= 3;  // 第 3 次成功
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sqlExecutor);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getRetryCount()).isEqualTo(2);
            assertThat(attempt.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("超过最大重试次数 — 返回失败")
        void retry_exceedMax() {
            MaterializedViewDef def = sampleViewWithRetry("mv1", 2);
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            AtomicInteger attempt = new AtomicInteger(0);
            Function<String, Boolean> sqlExecutor = sql -> {
                attempt.incrementAndGet();
                return false;
            };
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sqlExecutor);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getRetryCount()).isEqualTo(2);
            assertThat(attempt.get()).isEqualTo(3);  // 初始 1 次 + 2 次重试
        }

        @Test
        @DisplayName("SQL 抛异常 — 重试")
        void retry_exception() {
            MaterializedViewDef def = sampleViewWithRetry("mv1", 3);
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            AtomicInteger attempt = new AtomicInteger(0);
            Function<String, Boolean> sqlExecutor = sql -> {
                if (attempt.incrementAndGet() < 2) {
                    throw new RuntimeException("DB 连接失败");
                }
                return true;
            };
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sqlExecutor);

            ViewRefresher.RefreshResult result = refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getRetryCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("buildRefreshSql — SQL 生成")
    class BuildRefreshSqlTest {

        @Test
        @DisplayName("生成 TRUNCATE + INSERT...SELECT")
        void buildRefreshSql() {
            MaterializedViewDef def = sampleView("mv1");
            ViewRefresher refresher = new ViewRefresher(
                    name -> def, new MaterializedViewConfig(), sql -> true);
            String sql = refresher.buildRefreshSql(def);
            assertThat(sql).contains("TRUNCATE TABLE `report`.`mv_mv1`");
            assertThat(sql).contains("INSERT INTO `report`.`mv_mv1`");
            assertThat(sql).contains("SELECT region, SUM(total) AS total");
        }
    }

    @Nested
    @DisplayName("状态查询")
    class StatusTest {

        @Test
        @DisplayName("getLastResult — 返回最近刷新结果")
        void getLastResult() {
            MaterializedViewDef def = sampleView("mv1");
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sql -> true);

            assertThat(refresher.getLastResult("mv1")).isNull();
            refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(refresher.getLastResult("mv1")).isNotNull();
            assertThat(refresher.getLastResult("mv1").isSuccess()).isTrue();
        }

        @Test
        @DisplayName("getActiveRefreshCount — 刷新完成后归零")
        void getActiveRefreshCount() {
            MaterializedViewDef def = sampleView("mv1");
            Map<String, MaterializedViewDef> registry = Map.of("mv1", def);
            ViewRefresher refresher = new ViewRefresher(registry::get, new MaterializedViewConfig(), sql -> true);

            assertThat(refresher.getActiveRefreshCount()).isZero();
            refresher.refresh(RefreshEvent.manual("mv1", "test"));
            assertThat(refresher.getActiveRefreshCount()).isZero();
        }
    }
}