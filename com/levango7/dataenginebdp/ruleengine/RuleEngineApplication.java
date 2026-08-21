package com.shuqing.bigdata.ruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - 自研规则引擎主入口。
 *
 * <p>统一执行数据质量检查（DQ）、数据脱敏（MASK）与告警（ALERT）规则。</p>
 */
@SpringBootApplication
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}