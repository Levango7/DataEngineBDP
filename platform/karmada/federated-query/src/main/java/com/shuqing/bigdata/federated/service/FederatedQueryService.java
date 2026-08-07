package com.shuqing.bigdata.federated.service;

import com.shuqing.bigdata.federated.catalog.TableLocationService;
import com.shuqing.bigdata.federated.config.FederatedQueryProperties;
import com.shuqing.bigdata.federated.degrade.AlertNotifier;
import com.shuqing.bigdata.federated.degrade.DegradeStrategy;
import com.shuqing.bigdata.federated.degrade.NetworkFailureDetector;
import com.shuqing.bigdata.federated.merge.MergeStrategy;
import com.shuqing.bigdata.federated.merge.QueryResultMerger;
import com.shuqing.bigdata.federated.model.ClusterQueryResult;
import com.shuqing.bigdata.federated.model.DegradationAlert;
import com.shuqing.bigdata.federated.model.FederatedQueryRequest;
import com.shuqing.bigdata.federated.model.FederatedQueryResponse;
import com.shuqing.bigdata.federated.model.TableLocation;
import com.shuqing.bigdata.federated.routing.FederatedQueryRouter;
import com.shuqing.bigdata.federated.routing.QueryPlan;
import com.shuqing.bigdata.federated.transport.ClusterTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跨集群查询执行服务。
 *
 * <p>编排完整流程：
 * <ol>
 *   <li>路由器生成查询计划（{@link FederatedQueryRouter}）</li>
 *   <li>降级策略决策（{@link DegradeStrategy}）</li>
 *   <li>并行向各集群发送查询（{@link ClusterTransport}，mTLS）</li>
 *   <li>处理失败的集群（降级到本地）</li>
 *   <li>归并结果（{@link QueryResultMerger}）</li>
 *   <li>返回响应</li>
 * </ol>
 *
 * <p>验收标准：
 * <ul>
 *   <li>跨集群查询覆盖 ≥ 2 集群，查询结果正确</li>
 *   <li>P95 ≤ 30s（跨集群查询延迟）</li>
 *   <li>网络中断降级单集群查询并告警，降级过程无查询失败</li>
 * </ul>
 */
@Slf4j
@Service
public class FederatedQueryService {

    private final FederatedQueryRouter router;
    private final ClusterTransport transport;
    private final DegradeStrategy degradeStrategy;
    private final NetworkFailureDetector failureDetector;
    private final AlertNotifier alertNotifier;
    private final QueryResultMerger merger;
    private final TableLocationService tableLocationService;
    private final FederatedQueryProperties props;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public FederatedQueryService(FederatedQueryRouter router,
                                 ClusterTransport transport,
                                 DegradeStrategy degradeStrategy,
                                 NetworkFailureDetector failureDetector,
                                 AlertNotifier alertNotifier,
                                 QueryResultMerger merger,
                                 TableLocationService tableLocationService,
                                 FederatedQueryProperties props) {
        this.router = router;
        this.transport = transport;
        this.degradeStrategy = degradeStrategy;
        this.failureDetector = failureDetector;
        this.alertNotifier = alertNotifier;
        this.merger = merger;
        this.tableLocationService = tableLocationService;
        this.props = props;
    }

    /**
     * 同步执行跨集群查询。
     */
    public FederatedQueryResponse executeSync(FederatedQueryRequest request) {
        long start = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        List<DegradationAlert> alerts = new ArrayList<>();

        try {
            // 1. 生成查询计划
            QueryPlan plan = router.plan(request.getSql(), request.getDatabase(), request.getMergeStrategy());

            if (plan.getClusters().isEmpty()) {
                // 无表定位信息：尝试本地集群
                String local = props.getLocalCluster();
                FederatedQueryProperties.ClusterEndpoint ep = props.getClusters().get(local);
                if (ep != null) {
                    plan = router.planSingleCluster(request.getSql(), request.getDatabase(), local, request.getMergeStrategy());
                } else {
                    return buildErrorResponse(queryId, "No clusters available for query", start);
                }
            }

            // 2. 降级决策
            DegradeStrategy.DegradeDecision decision = degradeStrategy.decide(
                    plan.getClusters(), plan.getTableLocations());
            alerts.addAll(decision.alerts());

            List<String> actualClusters = decision.actualClusters();
            boolean degraded = decision.degraded();
            String degradeReason = decision.degradeReason();

            // 3. 并行查询各集群
            long timeoutMs = request.getTimeoutSeconds() != null
                    ? request.getTimeoutSeconds() * 1000L
                    : props.getDegrade().getQueryTimeout().toMillis();

            List<ClusterQueryResult> results = parallelQuery(actualClusters, plan, timeoutMs);

            // 4. 处理失败的集群（降级到本地）
            List<ClusterQueryResult> finalResults = new ArrayList<>();
            for (ClusterQueryResult r : results) {
                if (r.isSuccess()) {
                    failureDetector.recordSuccess(r.getCluster());
                    finalResults.add(r);
                } else {
                    if (request.isAllowDegrade()) {
                        ClusterQueryResult degradedResult = degradeStrategy.handleFailure(
                                r.getCluster(), r, plan.getOriginalSql(), plan.getDatabase(), plan.getTableLocations());
                        finalResults.add(degradedResult);
                        if (degradedResult.isDegraded() && degradedResult.isSuccess()) {
                            degraded = true;
                            if (degradeReason == null) {
                                degradeReason = "Cluster " + r.getCluster() + " failed, degraded to local";
                            }
                        }
                    } else {
                        finalResults.add(r);
                    }
                }
            }

            // 5. 归并结果
            MergeStrategy strategy = MergeStrategy.fromString(plan.getMergeStrategy());
            QueryResultMerger.MergedResult merged = merger.merge(finalResults, strategy);

            // 6. 构造响应
            String status = determineStatus(finalResults, degraded);
            List<String> clusterList = finalResults.stream()
                    .map(ClusterQueryResult::getCluster)
                    .distinct()
                    .toList();

            return FederatedQueryResponse.builder()
                    .queryId(queryId)
                    .status(status)
                    .schema(merged.schema())
                    .rows(merged.rows())
                    .totalRows(merged.totalRows())
                    .clusters(clusterList)
                    .degraded(degraded)
                    .degradeReason(degradeReason)
                    .alerts(alerts)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .timestamp(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("Federated query failed: queryId={} err={}", queryId, e.getMessage(), e);
            return buildErrorResponse(queryId, e.getMessage(), start);
        }
    }

    /**
     * 异步执行跨集群查询。
     */
    public CompletableFuture<FederatedQueryResponse> executeAsync(FederatedQueryRequest request) {
        return CompletableFuture.supplyAsync(() -> executeSync(request), executor);
    }

    /**
     * 列出已知集群。
     */
    public List<Map<String, Object>> listClusters() {
        List<Map<String, Object>> list = new ArrayList<>();
        props.getClusters().forEach((name, ep) -> {
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("name", name);
            info.put("url", ep.getUrl());
            info.put("type", ep.getType());
            info.put("vendor", ep.getVendor());
            info.put("arch", ep.getArch());
            info.put("region", ep.getRegion());
            info.put("env", ep.getEnv());
            info.put("enabled", ep.isEnabled());
            info.put("local", ep.isLocal());
            info.put("reachable", ep.getUrl() != null && transport.isReachable(name, ep.getUrl()));
            info.put("degraded", failureDetector.shouldDegrade(name));
            list.add(info);
        });
        return list;
    }

    /**
     * 列出最近降级告警。
     */
    public List<DegradationAlert> listDegradeAlerts(int limit) {
        return alertNotifier.listRecent(limit);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<ClusterQueryResult> parallelQuery(List<String> clusters, QueryPlan plan, long timeoutMs) {
        List<CompletableFuture<ClusterQueryResult>> futures = new ArrayList<>();
        for (String cluster : clusters) {
            String sql = plan.getClusterSqls().getOrDefault(cluster, plan.getOriginalSql());
            FederatedQueryProperties.ClusterEndpoint ep = props.getClusters().get(cluster);
            String url = ep != null ? ep.getUrl() : null;
            futures.add(CompletableFuture.supplyAsync(
                    () -> transport.execute(cluster, url, sql, plan.getDatabase(), timeoutMs),
                    executor));
        }
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(timeoutMs + 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        log.warn("Parallel query future failed: {}", e.getMessage());
                        return ClusterQueryResult.builder()
                                .success(false)
                                .error(e.getMessage())
                                .build();
                    }
                })
                .toList();
    }

    private String determineStatus(List<ClusterQueryResult> results, boolean degraded) {
        boolean allSuccess = results.stream().allMatch(ClusterQueryResult::isSuccess);
        boolean anySuccess = results.stream().anyMatch(ClusterQueryResult::isSuccess);
        if (allSuccess && !degraded) {
            return "SUCCESS";
        }
        if (anySuccess && degraded) {
            return "DEGRADED";
        }
        if (anySuccess) {
            return "PARTIAL";
        }
        return "FAILED";
    }

    private FederatedQueryResponse buildErrorResponse(String queryId, String error, long start) {
        return FederatedQueryResponse.builder()
                .queryId(queryId)
                .status("FAILED")
                .rows(Collections.emptyList())
                .totalRows(0)
                .clusters(Collections.emptyList())
                .degraded(false)
                .elapsedMs(System.currentTimeMillis() - start)
                .timestamp(Instant.now())
                .error(error)
                .build();
    }
}