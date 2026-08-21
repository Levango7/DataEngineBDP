package com.shuqing.bigdata.sqlgateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlExecuteRequest 模型测试。
 */
class SqlExecuteRequestTest {

    @Test
    @DisplayName("getter/setter — 所有字段正确存取")
    void allFields_shouldBeAccessible() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");
        request.setTenantId("t1");
        request.setLimit(100);

        assertThat(request.getSql()).isEqualTo("SELECT 1");
        assertThat(request.getEngine()).isEqualTo("trino");
        assertThat(request.getTenantId()).isEqualTo("t1");
        assertThat(request.getLimit()).isEqualTo(100);
    }

    @Test
    @DisplayName("默认值 — 字段初始为null")
    void defaultValues_shouldBeNull() {
        SqlExecuteRequest request = new SqlExecuteRequest();

        assertThat(request.getSql()).isNull();
        assertThat(request.getEngine()).isNull();
        assertThat(request.getTenantId()).isNull();
        assertThat(request.getLimit()).isNull();
    }

    @Test
    @DisplayName("toString — 不为空")
    void toString_shouldNotBeNull() {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        assertThat(request.toString()).isNotNull();
    }
}