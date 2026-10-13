package com.levango7.dataenginebdp.ruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - 自研规则引擎主入口。
 *
 * <p>统一执行数据质量检查（DQ）、数据脱敏（MASK）与告警（ALERT）规则。</p>
 *
 * <p>2026-10-12 包名收口：编排引擎原在 {@code ...dataenginebdp.rule.engine.orchestrator}
 * （{@code rule.engine} ≠ {@code ruleengine}），与启动类不同根，必须靠显式
 * {@code scanBasePackages} 补扫，少列一个根就让整组编排端点在运行期 404（单元测试构造
 * POJO 测不出来）。现已并入 {@code ...dataenginebdp.ruleengine.orchestrator}，
 * 落在默认扫描根内，故移除该补丁——不再有"漏列扫描根"这一后患。</p>
 */
@SpringBootApplication
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}