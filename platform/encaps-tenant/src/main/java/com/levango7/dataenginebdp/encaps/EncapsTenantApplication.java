package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
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
 * <p><b>组件扫描卫生（2026-10-15，修 E2E/IT 启动崩溃）</b>：</p>
 * <p>{@code encaps-layer} 与本模块共享包根 {@code ...encaps}，历史上（裸
 * {@code @SpringBootApplication} 默认扫描）layer 的全部组件都会进入本服务的
 * ApplicationContext。2026-10-13 收窄持久单元后这变成致命组合：</p>
 * <ul>
 *   <li>layer 的启动主类 {@code EncapsLayerApplication} 带
 *       {@code @EntityScan + @EnableJpaRepositories}，随组件扫描进入本上下文
 *       ⇒ <b>两个注册器重复注册同一仓储 bean</b> ⇒
 *       {@code BeanDefinitionOverrideException} ⇒ 容器 exit(1)；</li>
 *   <li>即便只排除该主类，layer 的 Controller/Service 仍会被扫入，而其仓储
 *       对应的实体<b>不在</b>本服务 {@code @EntityScan} 内 ⇒ 仓储 bean 创建报
 *       {@code Not a managed type} ⇒ 同样启动失败（本地 IT 实证）；</li>
 *   <li>也<b>不能</b>按包排除 {@code ...encaps.controller} / {@code ...encaps.service}——
 *       迁移前本模块自有的 4 个 Controller 与 {@code TenantService} 就住在这些包里，
 *       且 {@code ProjectController} / {@code TenantController} 与 layer 侧<b>同 FQN</b>，
 *       classloader 先加载者胜，包级排除/类级过滤器都无法区分两个 jar 里的同名类。</li>
 * </ul>
 *
 * <p><b>正解（本类注解 + 一次包迁移）</b>：</p>
 * <ol>
 *   <li>把本模块自有的 Controller / Service 迁入<b>专属包</b>
 *       {@code ...encaps.tenant.controller} / {@code ...encaps.tenant.service}
 *       （路由与行为零变化，只换包）——顺带消除 Project/TenantController 的
 *       FQN 同名隐患（layer 侧同名类留在 {@code ...encaps.controller}，不再撞名）；</li>
 *   <li>{@code @ComponentScan} 改为<b>白名单</b>：只列本服务自有包
 *       （quota / workspace / tenant.*）+ 仍需的 layer 包
 *       （{@code common} {@code config} {@code crypto} {@code security}）。
 *       layer 的 controller/service/model/repository 与 EncapsLayerApplication
 *       <b>不在列表即被排除</b>——白名单对 layer 将来新增的组件天然免疫
 *       （它们落在被排除的包里），不存在"漏加一个 excludeFilter 就崩"的维护陷阱；</li>
 *   <li>{@code @EnableJpaRepositories} 只列本服务真正持久化的三处
 *       （quota / workspace / encapsshared），不替 layer 的仓储注册 bean。</li>
 * </ol>
 *
 * <p>layer 侧保留下包的零 Repository 依赖已穷举验证（{@code common/config/crypto/security}
 * 全部源码无 "Repository" 字样），故其组件进入本上下文是安全的；
 * {@code AuditLogAspect}（等保 2.0，GB/T 22239-2019 8.1.4.3）随 {@code security} 包
 * 保持在位，由 {@code EncapsTenantApplicationIT.auditAspectAndAnnotationsSurvive()}
 * 常态守护。</p>
 *
 * <p>本地复刻测试：{@code EncapsTenantApplicationIT}（全 context 启动冒烟，
 * 已接入 surefire 常态运行——本仓无 failsafe，此前 {@code *IT} 任何生命周期都不跑）。
 * CI 实证：ZAP/E2E/Integration Test 三个 job 的 {@code it-encaps-tenant exited (1)}
 * 即此根因。</p>
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
        // 只注册本服务真正持久化的仓储；layer 的仓储其实体不在 @EntityScan 内，
        // 注册即 "Not a managed type" 崩溃（2026-10-15 本地 IT 实证）
        "com.levango7.dataenginebdp.encaps.quota",
        "com.levango7.dataenginebdp.encaps.workspace",
        // 跨服务共享仓储：TenantRepository / ProjectRepository
        "com.levango7.dataenginebdp.encapsshared.repository"
})
// 组件扫描白名单：列进来的才扫，没列的（layer 的 controller/service/model/repository
// 及其启动主类）天然排除。不用 excludeFilters 的原因见类注释：共享包根下无法按包/按类
// 区分两个 jar 的同名类，白名单是唯一稳的形式。
@ComponentScan(basePackages = {
        // —— 本服务自有（2026-10-15 起 Controller/Service 居于专属 tenant.* 包）——
        "com.levango7.dataenginebdp.encaps.quota",
        "com.levango7.dataenginebdp.encaps.workspace",
        "com.levango7.dataenginebdp.encaps.tenant.controller",
        "com.levango7.dataenginebdp.encaps.tenant.service",
        // —— 仍需的 encaps-layer 组件（零 Repository 依赖，已穷举验证）——
        // common：GlobalExceptionHandler / MissingTenantContextException
        "com.levango7.dataenginebdp.encaps.common",
        "com.levango7.dataenginebdp.encaps.config",
        // crypto + 其全部子包（gm/intl/jwt/...）：security.facade 反依赖 config/crypto
        "com.levango7.dataenginebdp.encaps.crypto",
        // security + security.facade.*：等保 2.0 AuditLogAspect（GB/T 22239-2019 8.1.4.3）
        "com.levango7.dataenginebdp.encaps.security"
})
public class EncapsTenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsTenantApplication.class, args);
    }
}
