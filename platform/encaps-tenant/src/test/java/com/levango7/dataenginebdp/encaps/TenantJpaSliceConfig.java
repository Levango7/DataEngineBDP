package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 切片测试（如 {@code ProjectControllerTest}）的最小上下文配置。
 *
 * <p><b>为什么单独成类、且刻意放在本包（{@code ...encaps} 根）</b>：
 * {@code EncapsTenantApplication} 的 {@code @ComponentScan} 白名单包含
 * {@code ...encaps.tenant.controller} 与 {@code ...encaps.tenant.service}。
 * 若本配置类位于这两个包（或其它白名单包）内，测试类路径上的它会被
 * {@code EncapsTenantApplicationIT} 的全 context 扫描捞进去，其
 * {@code @EnableJpaRepositories} 与应用自身的注册器重复注册
 * {@code projectRepository} ⇒ {@code BeanDefinitionOverrideException}
 * （2026-10-15 CI「Java Build & Test」实证）。放在根包——不在白名单——
 * 即不会被任何应用的扫描覆盖。</p>
 *
 * <p>带 {@code @SpringBootConfiguration} 的原因：切片测试的启动器在显式配置类中
 * 找不到 {@code @SpringBootConfiguration} 时会按包向上搜索，本模块 classpath 上
 * 同时存在 {@code EncapsTenantApplication} 与 encaps-layer 的
 * {@code EncapsLayerApplication} ⇒ 报 "Found multiple @SpringBootConfiguration"。
 * 显式类里含一个 {@code @SpringBootConfiguration} 即消除该搜索。</p>
 */
@SpringBootConfiguration
@EntityScan(basePackages = "com.levango7.dataenginebdp.encapsshared.model")
@EnableJpaRepositories(basePackages = "com.levango7.dataenginebdp.encapsshared.repository")
public class TenantJpaSliceConfig {
}
