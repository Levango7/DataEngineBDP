package com.levango7.dataenginebdp.infra.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - L0.5 跨环境统一供给编排器 启动主类。
 *
 * <p>L0.5 编排层位于四环境 Provider（L0.1 信创 / L0.2 裸金属 / L0.3 公有云 / L0.4 私有云）
 * 之上，对上暴露统一的 {@code /api/v1/clusters} REST API，通过请求体的 {@code environment}
 * 字段路由到对应 Provider，实现跨环境统一供给抽象。</p>
 *
 * <p>核心组件：</p>
 * <ul>
 *   <li>{@link com.levango7.dataenginebdp.infra.orchestrator.registry.ProviderRegistry} - Provider 注册表</li>
 *   <li>{@link com.levango7.dataenginebdp.infra.orchestrator.service.SupplyOrchestrator} - 供应流程编排核心</li>
 *   <li>{@link com.levango7.dataenginebdp.infra.orchestrator.registry.EnvironmentProfile} - 环境配置 Profile</li>
 * </ul>
 */
@SpringBootApplication
public class OrchestratorApplication {

    /**
     * 启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}