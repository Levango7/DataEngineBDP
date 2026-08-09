package com.levango7.dataenginebdp.federated;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数擎大数据平台 T034 - 跨集群查询路由与归并服务入口。
 *
 * <p>基于 T026 Karmada 控制面（Batch 1a）与 Phase 1 T012 Calcite 联邦优化器，
 * 提供以下能力：
 * <ul>
 *   <li>跨集群查询路由：接收 SQL，通过表元数据定位表所在集群，路由查询到对应集群</li>
 *   <li>全局 Catalog 表元数据定位：复用 Phase 1 platform/catalog REST API</li>
 *   <li>mTLS 跨集群传输：复用 Phase 1 Istio mTLS，WebClient SSL Context</li>
 *   <li>降级策略：网络中断检测（超时/连接失败），降级到单集群查询（仅查本地表），告警通知</li>
 *   <li>查询归并：跨集群查询结果归并（基于 Phase 1 T013 跨源 Join 归并器）</li>
 * </ul>
 *
 * <p>REST API 端点：
 * <ul>
 *   <li>POST /api/v1/federated/query        - 提交跨集群查询</li>
 *   <li>POST /api/v1/federated/query/sync   - 同步跨集群查询</li>
 *   <li>GET  /api/v1/federated/health       - 健康检查</li>
 *   <li>GET  /api/v1/federated/clusters     - 列出已知集群</li>
 *   <li>GET  /api/v1/federated/degradations - 列出降级事件</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FederatedQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(FederatedQueryApplication.class, args);
    }
}