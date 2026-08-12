package com.levango7.dataenginebdp.sqlgateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlExecuteResponse 模型测试。
 */
class SqlExecuteResponseTest {

    @Test
    @DisplayName("builder — 构建完整响应对象")
    void builder_shouldBuildCompleteResponse() {
        SqlExecuteResponse response = SqlExecuteResponse.builder()
                .queryId("q-001")
                .status("SUCCESS")
                .columns(List.of("col1", "col2"))
                .rows(List.of(List.of("v1", "v2")))
                .durationMs(100L)
                .engine("trino")
                .build();

        assertThat(response.getQueryId()).isEqualTo("q-001");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getColumns()).hasSize(2);
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getDurationMs()).isEqualTo(100L);
        assertThat(response.getEngine()).isEqualTo("trino");
    }

    @Test
    @DisplayName("setter — 修改字段值")
    void setter_shouldModifyFields() {
        SqlExecuteResponse response = SqlExecuteResponse.builder().build();
        response.setQueryId("q-002");
        response.setStatus("DEGRADED");

        assertThat(response.getQueryId()).isEqualTo("q-002");
        assertThat(response.getStatus()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("toString — 不为空")
    void toString_shouldNotBeNull() {
        SqlExecuteResponse response = SqlExecuteResponse.builder()
                .queryId("q-001")
                .status("SUCCESS")
                .build();
        assertThat(response.toString()).isNotNull();
    }
}