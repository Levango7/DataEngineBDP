package com.levango7.dataenginebdp.streambatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 — T035 流批一体调度与统一入口 启动类。
 *
 * <p>本模块实现：
 * <ul>
 *   <li>DolphinScheduler 流批统一编排插件（扩展 DAG 节点类型，支持 Spark 批 + Flink 流同一 DAG）</li>
 *   <li>Iceberg snapshot 隔离配置与验证（Spark 批读固定 snapshot，Flink 流读最新 snapshot）</li>
 *   <li>BI 自动选择视图路由器（根据查询模式自动选择批快照或流最新视图，与 Doris 物化视图集成）</li>
 * </ul>
 *
 * <p>基于 Phase 1 Iceberg V2 upsert（T015）与 Flink CDC（T014），
 * 实现 Iceberg 表同时被 Spark 批读与 Flink 流读数据一致（snapshot 隔离）。
 */
@SpringBootApplication
public class StreamBatchSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamBatchSchedulerApplication.class, args);
    }
}