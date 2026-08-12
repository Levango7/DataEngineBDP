package com.levango7.dataenginebdp.flinkcdc.materializedview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MaterializedViewConfig} 单元测试。
 *
 * @author shuqing-bigdata
 */
class MaterializedViewConfigTest {

    @Nested
    @DisplayName("默认值")
    class DefaultValueTest {

        @Test
        @DisplayName("默认 enabled=true")
        void defaultEnabled() {
            assertThat(new MaterializedViewConfig().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认 Doris 配置")
        void defaultDoris() {
            MaterializedViewConfig.DorisConfig doris = new MaterializedViewConfig().getDoris();
            assertThat(doris.getFeHosts()).isEqualTo("127.0.0.1:8030");
            assertThat(doris.getUsername()).isEqualTo("root");
            assertThat(doris.getDatabase()).isEqualTo("report");
        }

        @Test
        @DisplayName("默认刷新配置")
        void defaultRefresh() {
            MaterializedViewConfig.RefreshDefaults refresh = new MaterializedViewConfig().getRefresh();
            assertThat(refresh.getMaxConcurrentRefreshes()).isEqualTo(5);
            assertThat(refresh.getDefaultBatchThreshold()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("CdcConfig.parsedTopics — Topic 解析")
    class ParsedTopicsTest {

        @Test
        @DisplayName("逗号分隔 — 正确解析")
        void parsedTopics_csv() {
            MaterializedViewConfig.CdcConfig cdc = new MaterializedViewConfig.CdcConfig();
            cdc.setListenTopics("cdc-orders,cdc-products,cdc-users");
            assertThat(cdc.parsedTopics()).containsExactly("cdc-orders", "cdc-products", "cdc-users");
        }

        @Test
        @DisplayName("含空格 — 自动 trim")
        void parsedTopics_withSpaces() {
            MaterializedViewConfig.CdcConfig cdc = new MaterializedViewConfig.CdcConfig();
            cdc.setListenTopics(" cdc-orders , cdc-products ");
            assertThat(cdc.parsedTopics()).containsExactly("cdc-orders", "cdc-products");
        }

        @Test
        @DisplayName("空字符串 — 返回空列表")
        void parsedTopics_empty() {
            MaterializedViewConfig.CdcConfig cdc = new MaterializedViewConfig.CdcConfig();
            cdc.setListenTopics("");
            assertThat(cdc.parsedTopics()).isEmpty();
        }

        @Test
        @DisplayName("null — 返回空列表")
        void parsedTopics_null() {
            MaterializedViewConfig.CdcConfig cdc = new MaterializedViewConfig.CdcConfig();
            cdc.setListenTopics(null);
            assertThat(cdc.parsedTopics()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validate — 配置校验")
    class ValidateTest {

        @Test
        @DisplayName("默认配置 — 通过校验")
        void validate_default() {
            new MaterializedViewConfig().validate();
        }

        @Test
        @DisplayName("enabled=false — 跳过校验")
        void validate_disabled() {
            MaterializedViewConfig config = new MaterializedViewConfig();
            config.setEnabled(false);
            config.getDoris().setFeHosts("");  // 即使非法也不校验
            config.validate();
        }

        @Test
        @DisplayName("enabled=true 但 FE 主机为空 — 抛出异常")
        void validate_emptyFeHosts() {
            MaterializedViewConfig config = new MaterializedViewConfig();
            config.getDoris().setFeHosts("");
            assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("maxConcurrentRefreshes <= 0 — 抛出异常")
        void validate_invalidMaxConcurrent() {
            MaterializedViewConfig config = new MaterializedViewConfig();
            config.getRefresh().setMaxConcurrentRefreshes(0);
            assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("toString — 包含关键字段")
    void toString_containsFields() {
        MaterializedViewConfig config = new MaterializedViewConfig();
        assertThat(config.toString()).contains("enabled=true");
    }
}