package com.shuqing.bigdata.governance.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数擎大数据平台 - T036 实时治理管道主入口。
 *
 * <p>基于 Phase 1 Iceberg V2（T015）与 Flink CDC（T014），实现实时治理闭环：
 * <ol>
 *   <li>Iceberg REST Catalog 事件监听 → 元数据采集（≤ 5s）</li>
 *   <li>Flink CDC 实时血缘解析 → 字段级血缘图更新（NebulaGraph）</li>
 *   <li>Flink CEP 流式质量规则评估 → 违规即告警（≤ 5s）</li>
 *   <li>治理闭环：commit → 元数据 → 血缘 → 质量 → 告警，P95 ≤ 10s</li>
 * </ol>
 *
 * <p>启用异步与调度支持：
 * <ul>
 *   <li>{@code @EnableAsync}：事件监听器异步触发元数据采集，避免阻塞 Catalog commit</li>
 *   <li>{@code @EnableScheduling}：周期性血缘图一致性校验与质量规则刷新</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class RealTimeGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealTimeGovernanceApplication.class, args);
    }
}