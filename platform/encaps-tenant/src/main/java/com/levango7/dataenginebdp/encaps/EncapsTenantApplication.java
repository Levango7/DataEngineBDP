package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据引擎大数据平台 - 封装层（租户管理域）启动主类。
 *
 * <p>本模块承载租户管理域职责：Project / Account / Workspace / Quota。</p>
 *
 * <p><b>持久单元边界（2026-10-13 显式化，收口台账 #63 的遗留语义问题）</b>：
 * 此前本模块与 {@code encaps-layer} 共享包根
 * {@code com.levango7.dataenginebdp.encaps}，而 {@code @EntityScan} 取默认值
 * （即本类所在包根）⇒ 扫描把 encaps-layer 的 <b>全部 20 个实体</b>都拉进
 * 本服务的持久单元，于是 {@code V3__baseline_shared_model.sql} 必须复制
 * 20 张表 —— 而那些表由 encaps-layer / encaps-gateway / encaps-data 写入
 * <b>encaps_layer</b> schema，本服务 schema 里的副本**永远是空的**
 * （即"结构性恒 0"的根因）。</p>
 *
 * <p>修法：把本服务真正需要的 {@code Tenant} / {@code ProjectEntity} 抽到
 * {@code encaps-shared-model}（独立包根 {@code ...encapsshared}，
 * <b>刻意不是 {@code ...encaps} 的子包</b>），然后在此<b>逐个列出</b>实体所属包
 * —— 关键是不列 {@code ...encaps.model}，从而把持久单元收窄到
 * quota / workspace / shared 三处。</p>
 *
 * <p>{@code @EntityScan} / {@code @EnableJpaRepositories} 显式列出而非依赖默认值，
 * 是为了让"本服务持久化什么"一眼可见：改动扫描范围即改动契约。</p>
 *
 * <p><b>⚠️ 尚未完成的一半</b>：本服务仍依赖 {@code encaps-layer} 的
 * {@code encaps.common} / {@code encaps.security}（含等保 2.0 的
 * {@code AuditLogAspect}），故组件扫描仍会带入其 Controller
 * （{@code /api/v1/tenants}、{@code /api/v1/projects} 双注册，
 * compose 靠 {@code ENCAPS_LAYER_TENANT_CONTROLLER_ENABLED=false} 挡住）。
 * 摘除该依赖需先把 common/security 一并迁到共享模块，属独立 PR。</p>
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {
        // 本服务自有实体，**逐个列出所属包而非整个 ...encaps**
        // —— 后者会把 encaps-layer 的 ...encaps.model（20 个实体）一并扫进来
        "com.levango7.dataenginebdp.encaps.quota",
        "com.levango7.dataenginebdp.encaps.workspace",
        // 跨服务共享实体：Tenant / ProjectEntity（独立包根，非 ...encaps 子包）
        "com.levango7.dataenginebdp.encapsshared.model"
})
@EnableJpaRepositories(basePackages = {
        "com.levango7.dataenginebdp.encaps.quota",
        "com.levango7.dataenginebdp.encaps.workspace",
        // 跨服务共享仓储：TenantRepository / ProjectRepository
        "com.levango7.dataenginebdp.encapsshared.repository"
})
public class EncapsTenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsTenantApplication.class, args);
    }
}
