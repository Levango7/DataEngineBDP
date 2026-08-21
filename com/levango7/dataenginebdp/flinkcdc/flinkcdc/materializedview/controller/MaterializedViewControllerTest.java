package com.shuqing.bigdata.flinkcdc.materializedview.controller;

import com.shuqing.bigdata.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.shuqing.bigdata.flinkcdc.materializedview.model.AggregationType;
import com.shuqing.bigdata.flinkcdc.materializedview.model.MaterializedViewDef;
import com.shuqing.bigdata.flinkcdc.materializedview.model.RefreshPolicy;
import com.shuqing.bigdata.flinkcdc.materializedview.service.MaterializedViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaterializedViewController} 单元测试。
 *
 * <p>直接调用 Controller 方法，不启动 Spring MVC 上下文，
 * 验证业务逻辑与 HTTP 状态码映射。</p>
 *
 * @author shuqing-bigdata
 */
class MaterializedViewControllerTest {

    private MaterializedViewController controller;
    private MaterializedViewService service;

    @BeforeEach
    void setUp() {
        service = new MaterializedViewService(new MaterializedViewConfig(), sql -> true);
        service.init();
        service.start();
        controller = new MaterializedViewController(service);
    }

    private MaterializedViewDef sampleView(String name) {
        return MaterializedViewDef.builder()
                .name(name).database("report").targetTable("mv_" + name)
                .addSourceTable("shop.orders").addDimension("region")
                .addMetric("total", AggregationType.SUM)
                .refreshPolicy(RefreshPolicy.manual())
                .build();
    }

    @Nested
    @DisplayName("POST / — 注册物化视图")
    class RegisterViewTest {

        @Test
        @DisplayName("注册成功 — 返回 201")
        void registerView_success() {
            ResponseEntity<Map<String, Object>> resp = controller.registerView(sampleView("mv1"));
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
        }

        @Test
        @DisplayName("名称重复 — 返回 409")
        void registerView_duplicate() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.registerView(sampleView("mv1"));
            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("非法定义 — 返回 400")
        void registerView_invalid() {
            MaterializedViewDef invalid = new MaterializedViewDef();
            ResponseEntity<Map<String, Object>> resp = controller.registerView(invalid);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("GET / — 列出所有视图")
    class ListViewsTest {

        @Test
        @DisplayName("空列表 — 返回 200")
        void listViews_empty() {
            ResponseEntity<List<MaterializedViewDef>> resp = controller.listViews();
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).isEmpty();
        }

        @Test
        @DisplayName("有视图 — 返回列表")
        void listViews_nonEmpty() {
            controller.registerView(sampleView("mv1"));
            controller.registerView(sampleView("mv2"));
            ResponseEntity<List<MaterializedViewDef>> resp = controller.listViews();
            assertThat(resp.getBody()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("GET /{name} — 查询单个视图")
    class GetViewTest {

        @Test
        @DisplayName("存在 — 返回 200")
        void getView_found() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<?> resp = controller.getView("mv1");
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).isInstanceOf(MaterializedViewDef.class);
        }

        @Test
        @DisplayName("不存在 — 返回 404")
        void getView_notFound() {
            ResponseEntity<?> resp = controller.getView("unknown");
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("PUT /{name} — 更新视图")
    class UpdateViewTest {

        @Test
        @DisplayName("存在 — 返回 200")
        void updateView_found() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.updateView("mv1", sampleView("mv1"));
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
        }

        @Test
        @DisplayName("不存在 — 返回 404")
        void updateView_notFound() {
            ResponseEntity<Map<String, Object>> resp = controller.updateView("unknown", sampleView("unknown"));
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("DELETE /{name} — 删除视图")
    class RemoveViewTest {

        @Test
        @DisplayName("存在 — 返回 200")
        void removeView_found() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.removeView("mv1");
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("不存在 — 返回 404")
        void removeView_notFound() {
            ResponseEntity<Map<String, Object>> resp = controller.removeView("unknown");
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("POST /{name}/refresh — 手动刷新")
    class RefreshViewTest {

        @Test
        @DisplayName("存在 — 返回 200")
        void refreshView_found() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.refreshView("mv1", "admin");
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
            assertThat(resp.getBody()).containsKey("eventId");
        }

        @Test
        @DisplayName("不存在 — 返回 404")
        void refreshView_notFound() {
            ResponseEntity<Map<String, Object>> resp = controller.refreshView("unknown", "admin");
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("GET /{name}/status — 视图状态")
    class GetViewStatusTest {

        @Test
        @DisplayName("未刷新 — 返回从未刷新")
        void getViewStatus_neverRefreshed() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.getViewStatus("mv1");
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("lastRefreshSuccess")).isEqualTo(false);
        }

        @Test
        @DisplayName("已刷新 — 返回成功状态")
        void getViewStatus_refreshed() {
            controller.registerView(sampleView("mv1"));
            controller.refreshView("mv1", "admin");
            ResponseEntity<Map<String, Object>> resp = controller.getViewStatus("mv1");
            assertThat(resp.getBody().get("lastRefreshSuccess")).isEqualTo(true);
            assertThat(resp.getBody()).containsKey("lastRefreshDurationMs");
        }
    }

    @Nested
    @DisplayName("GET /status — 全局状态")
    class GetGlobalStatusTest {

        @Test
        @DisplayName("返回全局状态")
        void getGlobalStatus() {
            controller.registerView(sampleView("mv1"));
            ResponseEntity<Map<String, Object>> resp = controller.getGlobalStatus();
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("started")).isEqualTo(true);
            assertThat(resp.getBody().get("viewCount")).isEqualTo(1);
        }
    }
}