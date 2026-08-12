package com.levango7.dataenginebdp.federated.transport;

import com.levango7.dataenginebdp.federated.model.ClusterQueryResult;

import java.util.Map;

/**
 * 跨集群传输接口。
 *
 * <p>抽象出"将查询发送到某集群并取回结果"的能力，便于替换实现
 * （mTLS WebClient / gRPC / 直接 JDBC 等）。
 */
public interface ClusterTransport {

    /**
     * 向指定集群发送查询并取回结果。
     *
     * @param clusterName 集群名
     * @param clusterUrl  集群端点 URL
     * @param sql         SQL 语句
     * @param database    默认数据库
     * @param timeoutMs   超时（毫秒）
     * @return 集群查询子结果
     */
    ClusterQueryResult execute(String clusterName, String clusterUrl, String sql, String database, long timeoutMs);

    /**
     * 异步执行（返回 Mono）。
     */
    reactor.core.publisher.Mono<ClusterQueryResult> executeReactive(
            String clusterName, String clusterUrl, String sql, String database, long timeoutMs);

    /**
     * 探测集群是否可达（健康检查）。
     */
    boolean isReachable(String clusterName, String clusterUrl);

    /**
     * 传输协议名称（用于日志/监控）。
     */
    String protocol();
}