package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据引擎大数据平台 - 封装层（Encaps Layer）启动主类。
 *
 * <p>封装层负责向上屏蔽底层 K8s/大数据组件差异，向下统一调度资源供应原语。</p>
 *
 * <p>{@code @EnableScheduling} 启用 Spring 定时任务，供 JWT 密钥自动轮换
 * （{@code JwtKeyRotationService}）等调度任务使用。轮换默认关闭，
 * 由 {@code security.jwt.rotation.enabled} 控制。</p>
 *
 * <p><b>持久单元边界（2026-10-13 显式化）</b>：{@code Tenant} / {@code ProjectEntity}
 * 及对应仓储已抽到 {@code encaps-shared-model}（独立包根
 * {@code com.levango7.dataenginebdp.encapsshared}，刻意不是 {@code ...encaps}
 * 的子包），故此处显式声明扫描范围。列出自身包根 {@code ...encaps} 与共享包，
 * 语义与原默认值等价，但让"本服务持久化什么"一眼可见。</p>
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {
        // 本服务自有实体：...encaps.model（20 个）
        "com.levango7.dataenginebdp.encaps",
        // 跨服务共享实体：Tenant / ProjectEntity
        "com.levango7.dataenginebdp.encapsshared.model"
})
@EnableJpaRepositories(basePackages = {
        // 本服务自有仓储：...encaps.repository
        "com.levango7.dataenginebdp.encaps",
        // 跨服务共享仓储：TenantRepository / ProjectRepository
        "com.levango7.dataenginebdp.encapsshared.repository"
})
public class EncapsLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsLayerApplication.class, args);
    }
}