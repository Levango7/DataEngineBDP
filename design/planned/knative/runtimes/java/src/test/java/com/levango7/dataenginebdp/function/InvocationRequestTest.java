package com.levango7.dataenginebdp.function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InvocationRequest} 单元测试。
 *
 * <p>验证请求 DTO 的构造、null 事件兜底及 getter 行为。</p>
 */
@DisplayName("InvocationRequest 函数调用请求 DTO")
class InvocationRequestTest {

    @Nested
    @DisplayName("构造函数")
    class Constructor {

        @Test
        @DisplayName("应正确保存租户 ID、函数名和事件")
        void constructorStoresAllFields() {
            Map<String, Object> event = new HashMap<>();
            event.put("key", "value");

            InvocationRequest request = new InvocationRequest("tenant-1", "fn-1", event);

            assertThat(request.getTenantId()).isEqualTo("tenant-1");
            assertThat(request.getFunctionName()).isEqualTo("fn-1");
            assertThat(request.getEvent()).isSameAs(event);
        }

        @Test
        @DisplayName("null 事件应兜底为空 Map")
        void nullEventBecomesEmptyMap() {
            InvocationRequest request = new InvocationRequest("tenant-1", "fn-1", null);

            assertThat(request.getEvent()).isNotNull();
            assertThat(request.getEvent()).isEmpty();
        }

        @Test
        @DisplayName("null 事件兜底应为 Collections.emptyMap")
        void nullEventBecomesCollectionsEmptyMap() {
            InvocationRequest request = new InvocationRequest("tenant-1", "fn-1", null);

            assertThat(request.getEvent()).isEqualTo(Collections.emptyMap());
        }

        @Test
        @DisplayName("空 Map 事件应保持空 Map（不替换）")
        void emptyEventRemainsEmptyMap() {
            Map<String, Object> emptyEvent = new HashMap<>();
            InvocationRequest request = new InvocationRequest("tenant-1", "fn-1", emptyEvent);

            assertThat(request.getEvent()).isSameAs(emptyEvent);
            assertThat(request.getEvent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getter 方法")
    class Getters {

        @Test
        @DisplayName("getTenantId 应返回构造时传入的租户 ID")
        void getTenantIdReturnsConstructorValue() {
            InvocationRequest request = new InvocationRequest("tenant-x", "fn", null);

            assertThat(request.getTenantId()).isEqualTo("tenant-x");
        }

        @Test
        @DisplayName("getFunctionName 应返回构造时传入的函数名")
        void getFunctionNameReturnsConstructorValue() {
            InvocationRequest request = new InvocationRequest("t", "function-name", null);

            assertThat(request.getFunctionName()).isEqualTo("function-name");
        }

        @Test
        @DisplayName("getEvent 应返回构造时传入的事件 Map")
        void getEventReturnsConstructorValue() {
            Map<String, Object> event = new HashMap<>();
            event.put("a", 1);
            event.put("b", "two");
            InvocationRequest request = new InvocationRequest("t", "fn", event);

            assertThat(request.getEvent()).containsEntry("a", 1).containsEntry("b", "two");
        }
    }
}