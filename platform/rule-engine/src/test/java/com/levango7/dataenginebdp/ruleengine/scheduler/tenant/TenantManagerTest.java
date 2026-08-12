package com.levango7.dataenginebdp.ruleengine.scheduler.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantManager 单元测试。
 */
class TenantManagerTest {

    private TenantManager manager;

    @BeforeEach
    void setUp() {
        manager = new TenantManager();
    }

    @Test
    @DisplayName("register + get — 正确注册与查询")
    void registerAndGet() {
        TenantInfo info = TenantInfo.builder().tenantId("t1").name("租户1").maxConcurrentTasks(5).build();
        manager.register(info);

        Optional<TenantInfo> got = manager.get("t1");
        assertThat(got).isPresent();
        assertThat(got.get().getName()).isEqualTo("租户1");
        assertThat(got.get().getMaxConcurrentTasks()).isEqualTo(5);
        assertThat(got.get().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("register — tenantId 空白抛异常")
    void register_blankTenantId_throws() {
        assertThatThrownBy(() -> manager.register(TenantInfo.builder().tenantId("").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.register(TenantInfo.builder().tenantId(null).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("register — 重复注册更新配置但保留运行态计数")
    void register_duplicateUpdatesConfigKeepsRuntime() {
        manager.register(TenantInfo.builder().tenantId("t1").maxConcurrentTasks(5).build());
        manager.incrementActive("t1");
        manager.incrementQueued("t1");

        manager.register(TenantInfo.builder().tenantId("t1").maxConcurrentTasks(10).build());

        TenantInfo got = manager.get("t1").orElseThrow();
        assertThat(got.getMaxConcurrentTasks()).isEqualTo(10);
        assertThat(got.getActiveTaskCount()).isEqualTo(1);
        assertThat(got.getQueuedTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("isAllowed — 已注册启用返回 true；未注册/禁用返回 false")
    void isAllowed() {
        manager.register(TenantInfo.builder().tenantId("t1").enabled(true).build());
        manager.register(TenantInfo.builder().tenantId("t2").enabled(false).build());

        assertThat(manager.isAllowed("t1")).isTrue();
        assertThat(manager.isAllowed("t2")).isFalse();
        assertThat(manager.isAllowed("unknown")).isFalse();
    }

    @Test
    @DisplayName("setEnabled — 切换租户启用状态")
    void setEnabled() {
        manager.register(TenantInfo.builder().tenantId("t1").enabled(true).build());

        assertThat(manager.setEnabled("t1", false)).isTrue();
        assertThat(manager.isAllowed("t1")).isFalse();

        assertThat(manager.setEnabled("t1", true)).isTrue();
        assertThat(manager.isAllowed("t1")).isTrue();

        assertThat(manager.setEnabled("unknown", true)).isFalse();
    }

    @Test
    @DisplayName("increment/decrement — 活跃与排队计数正确且下限为 0")
    void activeAndQueuedCounters() {
        manager.register(TenantInfo.builder().tenantId("t1").build());

        manager.incrementActive("t1");
        manager.incrementActive("t1");
        manager.incrementQueued("t1");
        assertThat(manager.getActiveCount("t1")).isEqualTo(2);
        assertThat(manager.get("t1").orElseThrow().getQueuedTaskCount()).isEqualTo(1);

        manager.decrementActive("t1");
        assertThat(manager.getActiveCount("t1")).isEqualTo(1);

        manager.decrementActive("t1");
        manager.decrementActive("t1"); // 下限 0
        assertThat(manager.getActiveCount("t1")).isEqualTo(0);

        manager.decrementQueued("t1");
        manager.decrementQueued("t1"); // 下限 0
        assertThat(manager.get("t1").orElseThrow().getQueuedTaskCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("unregister — 无活跃任务时移除成功")
    void unregister_noActive_succeeds() {
        manager.register(TenantInfo.builder().tenantId("t1").build());
        assertThat(manager.unregister("t1")).isTrue();
        assertThat(manager.get("t1")).isEmpty();
    }

    @Test
    @DisplayName("unregister — 有活跃任务时拒绝移除")
    void unregister_withActive_fails() {
        manager.register(TenantInfo.builder().tenantId("t1").build());
        manager.incrementActive("t1");
        assertThat(manager.unregister("t1")).isFalse();
        assertThat(manager.get("t1")).isPresent();
    }

    @Test
    @DisplayName("listAll — 返回全部租户")
    void listAll() {
        manager.register(TenantInfo.builder().tenantId("t1").build());
        manager.register(TenantInfo.builder().tenantId("t2").build());
        assertThat(manager.listAll()).hasSize(2);
    }
}