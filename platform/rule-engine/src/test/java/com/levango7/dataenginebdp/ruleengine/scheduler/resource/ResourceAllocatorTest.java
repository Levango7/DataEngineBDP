package com.levango7.dataenginebdp.ruleengine.scheduler.resource;

import com.levango7.dataenginebdp.ruleengine.scheduler.config.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceAllocator 单元测试。
 */
class ResourceAllocatorTest {

    private ResourceAllocator allocator;

    @BeforeEach
    void setUp() {
        SchedulerProperties props = new SchedulerProperties();
        props.getDefaultQuota().setMaxCpuCores(4.0);
        props.getDefaultQuota().setMaxMemoryMb(4096L);
        allocator = new ResourceAllocator(props);
    }

    @Test
    @DisplayName("setQuota + getQuota — 正确设置与查询")
    void setAndGetQuota() {
        ResourceQuota q = allocator.setQuota("t1", 8.0, 8192L);
        assertThat(q.getMaxCpuCores()).isEqualTo(8.0);
        assertThat(q.getMaxMemoryMb()).isEqualTo(8192L);

        assertThat(allocator.getQuota("t1")).isPresent();
        assertThat(allocator.getQuota("unknown")).isEmpty();
    }

    @Test
    @DisplayName("tryAllocate — 首次自动按默认配额初始化")
    void tryAllocate_autoInitDefaultQuota() {
        boolean ok = allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(2.0).memoryMb(1024L).build());
        assertThat(ok).isTrue();

        ResourceQuota q = allocator.getQuota("t1").orElseThrow();
        assertThat(q.getMaxCpuCores()).isEqualTo(4.0);
        assertThat(q.getUsedCpuCores()).isEqualTo(2.0);
        assertThat(q.getUsedMemoryMb()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("tryAllocate — 配额内多次分配累计")
    void tryAllocate_accumulates() {
        allocator.setQuota("t1", 4.0, 4096L);
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(1.0).memoryMb(1024L).build())).isTrue();
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(1.5).memoryMb(2048L).build())).isTrue();

        ResourceQuota q = allocator.getQuota("t1").orElseThrow();
        assertThat(q.getUsedCpuCores()).isEqualTo(2.5);
        assertThat(q.getUsedMemoryMb()).isEqualTo(3072L);
    }

    @Test
    @DisplayName("tryAllocate — 超配返回 false 且不占用")
    void tryAllocate_overQuota_returnsFalse() {
        allocator.setQuota("t1", 2.0, 2048L);
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(1.0).memoryMb(1024L).build())).isTrue();
        // CPU 超配
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(2.0).memoryMb(512L).build())).isFalse();
        // 内存超配
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(0.5).memoryMb(2048L).build())).isFalse();

        ResourceQuota q = allocator.getQuota("t1").orElseThrow();
        assertThat(q.getUsedCpuCores()).isEqualTo(1.0); // 未被超配请求增加
        assertThat(q.getUsedMemoryMb()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("release — 释放后已用量减少")
    void release_decreasesUsage() {
        allocator.setQuota("t1", 4.0, 4096L);
        ResourceRequest req = ResourceRequest.builder().cpuCores(2.0).memoryMb(1024L).build();
        allocator.tryAllocate("t1", req);

        allocator.release("t1", req);
        ResourceQuota q = allocator.getQuota("t1").orElseThrow();
        assertThat(q.getUsedCpuCores()).isEqualTo(0.0);
        assertThat(q.getUsedMemoryMb()).isEqualTo(0L);
    }

    @Test
    @DisplayName("release — 释放量超过已用时不为负")
    void release_overRelease_clampedToZero() {
        allocator.setQuota("t1", 4.0, 4096L);
        allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(1.0).memoryMb(512L).build());
        // 释放比分配多
        allocator.release("t1", ResourceRequest.builder().cpuCores(5.0).memoryMb(5000L).build());

        ResourceQuota q = allocator.getQuota("t1").orElseThrow();
        assertThat(q.getUsedCpuCores()).isEqualTo(0.0);
        assertThat(q.getUsedMemoryMb()).isEqualTo(0L);
    }

    @Test
    @DisplayName("多租户隔离 — 不同租户配额独立")
    void multiTenantIsolation() {
        allocator.setQuota("t1", 2.0, 2048L);
        allocator.setQuota("t2", 8.0, 8192L);

        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(2.0).memoryMb(2048L).build())).isTrue();
        // t1 已满，t2 仍有余量
        assertThat(allocator.tryAllocate("t1", ResourceRequest.builder().cpuCores(0.1).memoryMb(1L).build())).isFalse();
        assertThat(allocator.tryAllocate("t2", ResourceRequest.builder().cpuCores(2.0).memoryMb(2048L).build())).isTrue();
    }

    @Test
    @DisplayName("ResourceRequest.zero — 零资源请求")
    void zeroRequest() {
        ResourceRequest z = ResourceRequest.zero();
        assertThat(z.getCpuCores()).isEqualTo(0.0);
        assertThat(z.getMemoryMb()).isEqualTo(0L);
    }

    @Test
    @DisplayName("ResourceQuota.canAllocate — 边界判断")
    void canAllocate_boundary() {
        ResourceQuota q = ResourceQuota.builder().maxCpuCores(4.0).maxMemoryMb(4096L).build();
        assertThat(q.canAllocate(4.0, 4096L)).isTrue();  // 恰好等于
        assertThat(q.canAllocate(4.1, 4096L)).isFalse(); // CPU 超
        assertThat(q.canAllocate(4.0, 4097L)).isFalse(); // 内存超
    }
}