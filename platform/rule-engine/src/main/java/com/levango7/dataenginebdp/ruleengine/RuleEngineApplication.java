package com.levango7.dataenginebdp.ruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - 自研规则引擎主入口。
 *
 * <p>统一执行数据质量检查（DQ）、数据脱敏（MASK）与告警（ALERT）规则。</p>
 */
// 编排引擎在 com.levango7.dataenginebdp.rule.engine.orchestrator（rule.engine ≠ ruleengine），
// 不在本类的默认扫描根内；少列这一个根会让整组编排端点在运行期 404（单元测试构造 POJO，测不出来）。
@SpringBootApplication(scanBasePackages = {
        "com.levango7.dataenginebdp.ruleengine",
        "com.levango7.dataenginebdp.rule.engine"
})
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}