package com.levango7.dataenginebdp.federated.degrade;

import com.levango7.dataenginebdp.federated.config.FederatedQueryProperties;
import com.levango7.dataenginebdp.federated.model.ClusterQueryResult;
import com.levango7.dataenginebdp.federated.model.DegradationAlert;
import com.levango7.dataenginebdp.federated.model.TableLocation;
import com.levango7.dataenginebdp.federated.transport.ClusterTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 降级策略：网络中断降级单集群查询并告警。
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>对每个目标集群，先用 {@link NetworkFailureDetector} 判断是否已降级</li>
 *   <li>若已降级，跳过该集群，仅查询本地集群的本地表</li>
 *   <li>若查询中发生网络失败（超时/连接拒绝），记录失败，触发告警，
 *       并将该集群涉及的表回退到本地集群（若本地有同名表）</li>
 *   <li>降级过程不抛异常，保证查询不失败（仅返回部分结果 + DEGRADED 状态）</li>
 * </ol>
 *
 * <p>验收标准：网络中断降级单集群查询并告警，降级过程无查询失败。
 */
@Slf4j
@Component
public class DegradeStrategy {

    private final FederatedQueryProperties props;
    private final NetworkFailureDetector detector;
    private final AlertNotifier alertNotifier;
    private final ClusterTransport transport;

    public DegradeStrategy(FederatedQueryProperties props,
                           NetworkFailureDetector detector,
                           AlertNotifier alertNotifier,
                           ClusterTransport transport) {
        this.props = props;
        this.detector = detector;
        this.alertNotifier = alertNotifier;
        this.transport = transport;
    }

    /**
     * 执行降级决策：对一组计划查询的集群，返回实际可查询的集群列表与触发的告警。
     *
     * @param plannedClusters 计划查询的集群列表
     * @param tableLocations  表定位信息（用于判断本地表）
     * @return 降级决策结果
     */
    public DegradeDecision decide(List<String> plannedClusters, Map<String, TableLocation> tableLocations) {
        List<String> actualClusters = new ArrayList<>();
        List<DegradationAlert> alerts = new ArrayList<>();
        boolean degraded = false;
        String degradeReason = null;

        for (String cluster : plannedClusters) {
            if (detector.shouldDegrade(cluster)) {
                degraded = true;
                degradeReason = "Cluster " + cluster + " marked degraded due to network failures";
                DegradationAlert alert = alertNotifier.alert(
                        "WARN",
                        "DEGRADE_TRIGGERED",
                        cluster,
                        "Network failures exceeded threshold, degrade to local cluster",
                        props.getLocalCluster());
                alerts.add(alert);
                log.warn("Degrade triggered for cluster [{}] -> fallback to [{}]",
                        cluster, props.getLocalCluster());
                // 不加入 actualClusters，由调用方决定是否查本地
            } else {
                actualClusters.add(cluster);
            }
        }

        // 若所有集群都降级，至少保留本地集群
        if (actualClusters.isEmpty() && !plannedClusters.isEmpty()) {
            actualClusters.add(props.getLocalCluster());
            degraded = true;
            if (degradeReason == null) {
                degradeReason = "All target clusters degraded, fallback to local only";
            }
        }

        return new DegradeDecision(actualClusters, degraded, degradeReason, alerts);
    }

    /**
     * 处理单集群查询失败：记录失败、判断是否触发降级、返回是否可重试到本地。
     *
     * @param cluster    失败的集群
     * @param result     失败的查询结果
     * @param sql        SQL 语句
     * @param database   数据库
     * @param tableLocations 表定位
     * @return 降级后的查询结果（成功）或原失败结果（不可降级）
     */
    public ClusterQueryResult handleFailure(String cluster, ClusterQueryResult result,
                                            String sql, String database,
                                            Map<String, TableLocation> tableLocations) {
        detector.recordFailure(cluster, result.getError());

        DegradationAlert alert = alertNotifier.alert(
                "ERROR",
                "CONNECTION_FAILURE",
                cluster,
                "Query to cluster " + cluster + " failed: " + result.getError(),
                props.getLocalCluster());

        // 尝试降级到本地集群
        if (props.getDegrade().isEnabled()) {
            String localCluster = props.getLocalCluster();
            FederatedQueryProperties.ClusterEndpoint local = props.getClusters().get(localCluster);
            if (local != null && local.isEnabled()) {
                log.warn("Degrading query from [{}] to local [{}]", cluster, localCluster);
                long timeout = props.getDegrade().getQueryTimeout().toMillis();
                ClusterQueryResult localResult = transport.execute(
                        localCluster, local.getUrl(), sql, database, timeout);
                localResult.setDegraded(true);
                if (localResult.isSuccess()) {
                    detector.recordSuccess(localCluster);
                    alertNotifier.recover(cluster);
                }
                return localResult;
            }
        }

        // 无法降级，返回原失败结果
        result.setDegraded(false);
        return result;
    }

    /**
     * 降级决策结果。
     */
    public record DegradeDecision(
            List<String> actualClusters,
            boolean degraded,
            String degradeReason,
            List<DegradationAlert> alerts) {}
}