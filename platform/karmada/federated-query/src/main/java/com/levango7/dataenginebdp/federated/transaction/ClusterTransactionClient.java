package com.levango7.dataenginebdp.federated.transaction;

/**
 * 集群事务操作接口（抽象集群间通信）。
 *
 * <p>2PC 协议通过此接口向参与集群发送 prepare/commit/rollback 请求，
 * 测试时使用 Mock 实现，生产环境使用 mTLS WebClient 实现。
 *
 * <p>不引入实际网络依赖，便于单元测试。
 */
public interface ClusterTransactionClient {

    /**
     * 向指定集群发送 prepare 请求（阶段 1）。
     *
     * <p>集群在本地持久化事务日志，承诺可提交或回滚。
     *
     * @param clusterName 集群名
     * @param endpoint    集群端点 URL
     * @param txId        全局事务 ID
     * @return true 集群已 prepared，false 集群拒绝
     */
    boolean prepare(String clusterName, String endpoint, String txId);

    /**
     * 向指定集群发送 commit 请求（阶段 2）。
     *
     * @param clusterName 集群名
     * @param endpoint    集群端点 URL
     * @param txId        全局事务 ID
     * @return true 集群已 committed
     */
    boolean commit(String clusterName, String endpoint, String txId);

    /**
     * 向指定集群发送 rollback 请求。
     *
     * @param clusterName 集群名
     * @param endpoint    集群端点 URL
     * @param txId        全局事务 ID
     * @return true 集群已 rolled back
     */
    boolean rollback(String clusterName, String endpoint, String txId);
}