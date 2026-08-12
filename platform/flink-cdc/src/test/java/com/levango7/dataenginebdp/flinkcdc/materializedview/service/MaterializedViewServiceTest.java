package com.levango7.dataenginebdp.flinkcdc.materializedview.service;

import com.levango7.dataenginebdp.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.AggregationType;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.RefreshPolicy;
import com.levango7.dataenginebdp.flinkcdc.materializedview.trigger.RefreshEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MaterializedViewService} 单元测试。
 *
 * @author shuqing-bigdata
 */
class MaterializedViewServiceTest {

    private MaterializedViewDef sampleView(String name) {
        return MaterializedViewDef.builder()
                .name(name).database("report").targetTable("mv_" + name)
                .addSourceTable("shop.orders").addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(RefreshPolicy.manual())
                .build();
    }

    private MaterializedViewService newService() {
        return new MaterializedViewService(new MaterializedViewConfig(), sql -> true);
    }

    @Nested
    @DisplayName("视图定义 CRUD")
    class CrudTest {

        @Test
        @DisplayName("registerView — 注册成功")
        void registerView_success() {
            MaterializedViewService service = newService();
            assertThat(service.registerView(sampleView("mv1"))).isTrue();
            assertThat(service.viewCount()).isEqualTo(1);
            assertThat(service.getView("mv1")).isNotNull();
        }

        @Test
        @DisplayName("registerView — 名称重复返回 false")
        void registerView_duplicate() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            assertThat(service.registerView(sampleView("mv1"))).isFalse();
            assertThat(service.viewCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("registerView — 非法定义抛出异常")
        void registerView_invalid() {
            MaterializedViewService service = newService();
            MaterializedViewDef invalid = new MaterializedViewDef();
            invalid.setName("");  // 名称为空
            assertThatThrownBy(() -> service.registerView(invalid))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("updateView — 更新成功")
        void updateView_success() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            MaterializedViewDef updated = sampleView("mv1");
            updated.setTargetTable("mv_updated");
            assertThat(service.updateView(updated)).isTrue();
            assertThat(service.getView("mv1").getTargetTable()).isEqualTo("mv_updated");
        }

        @Test
        @DisplayName("updateView — 不存在返回 false")
        void updateView_notFound() {
            MaterializedViewService service = newService();
            assertThat(service.updateView(sampleView("mv1"))).isFalse();
        }

        @Test
        @DisplayName("removeView — 删除成功")
        void removeView_success() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            assertThat(service.removeView("mv1")).isTrue();
            assertThat(service.viewCount()).isZero();
        }

        @Test
        @DisplayName("removeView — 不存在返回 false")
        void removeView_notFound() {
            MaterializedViewService service = newService();
            assertThat(service.removeView("unknown")).isFalse();
        }

        @Test
        @DisplayName("listViews — 返回所有视图")
        void listViews() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            service.registerView(sampleView("mv2"));
            assertThat(service.listViews()).hasSize(2);
        }

        @Test
        @DisplayName("getViewRegistry — 返回不可修改映射")
        void getViewRegistry() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            assertThat(service.getViewRegistry()).containsKey("mv1");
        }
    }

    @Nested
    @DisplayName("生命周期")
    class LifecycleTest {

        @Test
        @DisplayName("start/stop — 正常切换状态")
        void startStop() {
            MaterializedViewService service = newService();
            service.init();
            assertThat(service.isStarted()).isFalse();
            service.start();
            assertThat(service.isStarted()).isTrue();
            service.stop();
            assertThat(service.isStarted()).isFalse();
        }

        @Test
        @DisplayName("重复 start — 无效果")
        void start_idempotent() {
            MaterializedViewService service = newService();
            service.init();
            service.start();
            service.start();
            assertThat(service.isStarted()).isTrue();
            service.stop();
        }

        @Test
        @DisplayName("config disabled — start 仍标记为已启动")
        void start_disabled() {
            MaterializedViewConfig config = new MaterializedViewConfig();
            config.setEnabled(false);
            MaterializedViewService service = new MaterializedViewService(config, sql -> true);
            service.init();
            service.start();
            assertThat(service.isStarted()).isTrue();
            service.stop();
        }
    }

    @Nested
    @DisplayName("手动刷新")
    class ManualRefreshTest {

        @Test
        @DisplayName("refreshManually — 视图存在触发成功")
        void refreshManually_success() {
            AtomicInteger sqlCount = new AtomicInteger(0);
            Function<String, Boolean> sqlExecutor = sql -> {
                sqlCount.incrementAndGet();
                return true;
            };
            MaterializedViewService service = new MaterializedViewService(new MaterializedViewConfig(), sqlExecutor);
            service.registerView(sampleView("mv1"));
            service.init();
            service.start();

            RefreshEvent event = service.refreshManually("mv1", "admin");
            assertThat(event).isNotNull();
            assertThat(event.getViewName()).isEqualTo("mv1");
            assertThat(sqlCount.get()).isEqualTo(1);
            service.stop();
        }

        @Test
        @DisplayName("refreshManually — 视图不存在返回 null")
        void refreshManually_notFound() {
            MaterializedViewService service = newService();
            service.init();
            RefreshEvent event = service.refreshManually("unknown", "admin");
            assertThat(event).isNull();
        }
    }

    @Nested
    @DisplayName("状态查询")
    class StatusTest {

        @Test
        @DisplayName("getLastRefreshResult — 返回最近结果")
        void getLastRefreshResult() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            service.init();
            service.start();
            service.refreshManually("mv1", "admin");
            assertThat(service.getLastRefreshResult("mv1")).isNotNull();
            assertThat(service.getLastRefreshResult("mv1").isSuccess()).isTrue();
            service.stop();
        }

        @Test
        @DisplayName("getActiveRefreshCount — 刷新完成后归零")
        void getActiveRefreshCount() {
            MaterializedViewService service = newService();
            service.registerView(sampleView("mv1"));
            service.init();
            service.start();
            service.refreshManually("mv1", "admin");
            assertThat(service.getActiveRefreshCount()).isZero();
            service.stop();
        }
    }
}