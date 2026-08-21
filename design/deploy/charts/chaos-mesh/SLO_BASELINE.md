# SLO 量化基线（v2.1 故障演练）

> 基于 Chaos Mesh 故障注入实验建立的服务等级目标（SLO）量化基线，用于稳态验证与故障演练评估。

## 一、SLO 基线定义

### 1.1 核心服务 SLO 基线

| 服务 | 可用性 | P99 延迟 | 错误率 | 吞吐量 | RTO | RPO |
| --- | --- | --- | --- | --- | --- | --- |
| sq-encaps-layer | ≥ 99.9% | ≤ 500ms | ≤ 1% | ≥ 200 req/s | 30s | 0 |
| sq-sql-gateway | ≥ 99.5% | ≤ 1000ms | ≤ 5% | ≥ 100 req/s | 60s | 0 |
| sq-rule-engine | ≥ 99.0% | ≤ 800ms | ≤ 5% | ≥ 50 req/s | 120s | 0 |
| sq-catalog | ≥ 99.9% | ≤ 300ms | ≤ 1% | ≥ 150 req/s | 30s | 0 |
| sq-asset-exchange | ≥ 99.5% | ≤ 800ms | ≤ 2% | ≥ 80 req/s | 60s | 0 |

### 1.2 故障注入期间允许的 SLO 降级

| 故障类型 | 可用性降级 | P99 延迟放大 | 错误率上限 | 验证目标 |
| --- | --- | --- | --- | --- |
| 网络延迟 200ms | ≥ 99.0% | ≤ 2x 基线 | ≤ 5% | 超时重试、降级熔断 |
| 网络丢包 10% | ≥ 95.0% | ≤ 3x 基线 | ≤ 10% | 重试、熔断、降级 |
| Pod 杀 | ≥ 99.0%（30s 内自愈） | ≤ 1.5x 基线 | ≤ 5% | 多副本高可用、自愈 |
| 磁盘 IO 延迟 100ms | ≥ 99.5% | ≤ 2x 基线 | ≤ 3% | IO 超时、降级处理 |
| 内存压力 512MB | ≥ 99.0% | ≤ 2x 基线 | ≤ 5% | OOM 处理、内存限制 |
| CPU 压力 80% | ≥ 99.0% | ≤ 3x 基线 | ≤ 5% | CPU 限流、弹性伸缩 |

## 二、稳态验证流程

### 2.1 故障演练流程

```text
1. 记录基线指标（故障注入前 5 分钟）
2. 启动故障注入实验
3. 持续监控 SLO 指标（Prometheus + Alertmanager）
4. 对比故障期间指标与 SLO 基线
5. 故障注入结束
6. 验证服务恢复到基线水平
7. 生成故障演练报告
```

### 2.2 稳态验证告警规则

| 告警名称 | 触发条件 | 严重级别 | 说明 |
| --- | --- | --- | --- |
| SLOAvailabilityViolation | 可用性 < 99% | critical | 可用性违反 SLO 基线 |
| SLOLatencyViolation | P99 > 1000ms | warning | 延迟超过 SLO 基线 |
| SLOErrorRateViolation | 错误率 > 5% | critical | 错误率超过 SLO 基线 |
| SLOThroughputDrop | 吞吐量 < 100 req/s | warning | 吞吐量下降超过阈值 |

## 三、故障注入实验清单

### 3.1 网络类故障

| 实验名称 | 类型 | 参数 | 目标 | 调度 |
| --- | --- | --- | --- | --- |
| network-delay-experiment | NetworkChaos/delay | latency=200ms, jitter=50ms | sq-encaps-layer | @every 1h |
| network-loss-experiment | NetworkChaos/loss | loss=10% | sq-sql-gateway | 手动触发 |

### 3.2 Pod 类故障

| 实验名称 | 类型 | 参数 | 目标 | 调度 |
| --- | --- | --- | --- | --- |
| pod-kill-experiment | PodChaos/pod-kill | mode=all, gracePeriod=0 | sq-rule-engine | @every 30m |

### 3.3 资源压力类故障

| 实验名称 | 类型 | 参数 | 目标 | 调度 |
| --- | --- | --- | --- | --- |
| memory-stress-experiment | StressChaos/memory | size=512MB, workers=4 | sq-sql-gateway | 手动触发 |
| cpu-stress-experiment | StressChaos/cpu | load=80, workers=4 | sq-rule-engine | 手动触发 |

### 3.4 磁盘 IO 类故障

| 实验名称 | 类型 | 参数 | 目标 | 调度 |
| --- | --- | --- | --- | --- |
| disk-io-delay-experiment | IOChaos/latency | delay=100ms, path=/data | sq-encaps-layer | 手动触发 |

## 四、故障演练报告模板

```markdown
## 故障演练报告

- **演练时间**：YYYY-MM-DD HH:MM
- **演练实验**：network-delay-experiment
- **目标服务**：sq-encaps-layer
- **故障参数**：latency=200ms, jitter=50ms
- **持续时长**：5 分钟

### 基线指标（故障前）
- 可用性：99.95%
- P99 延迟：320ms
- 错误率：0.5%
- 吞吐量：250 req/s

### 故障期间指标
- 可用性：99.12%（符合 ≥ 99.0% 基线）
- P99 延迟：680ms（符合 ≤ 2x 基线）
- 错误率：3.2%（符合 ≤ 5% 基线）
- 吞吐量：180 req/s

### 结论
- [x] 可用性符合 SLO 基线
- [x] 延迟在允许放大范围内
- [x] 错误率符合 SLO 基线
- [x] 服务在故障结束后 30s 内恢复到基线水平
```

## 五、与 Chaos Mesh Dashboard 集成

Chaos Mesh Dashboard 提供 Web UI 管理故障注入实验：

```bash
# 端口转发访问 Dashboard
kubectl port-forward -n chaos-mesh svc/chaos-mesh-dashboard 2333:2333

# 访问 http://localhost:2333
```

## 六、自动化故障演练

通过 Cron 调度定期执行故障演练，建立稳态验证基线：

```bash
# 查看已调度的故障实验
kubectl get networkchaos,podchaos,stresschaos,iochaos -A

# 手动触发一次故障实验
kubectl apply -f - <<EOF
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: ad-hoc-network-delay
  namespace: default
spec:
  action: delay
  mode: all
  selector:
    namespaces: ["default"]
    labelSelectors: {"app": "sq-encaps-layer"}
  delay:
    latency: "500ms"
  duration: "1m"
EOF
```