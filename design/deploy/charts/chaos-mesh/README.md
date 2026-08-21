# Chaos Mesh 故障演练 Helm Chart（v2.1 生产化加固）

> 引入 Chaos Mesh 进行稳态验证与故障注入测试，建立 SLO 量化基线。

## 一、Chart 说明

本 Chart 安装 Chaos Mesh 控制器与 Dashboard，并预置 6 类故障注入实验配置和 SLO 稳态验证告警规则。

| 能力 | 说明 |
| --- | --- |
| 网络延迟 | 注入 200ms 网络延迟，验证超时重试与降级熔断 |
| 网络丢包 | 注入 10% 丢包率，验证重试与熔断 |
| Pod 杀 | 随机杀死 Pod，验证自愈与多副本高可用 |
| 磁盘 IO 延迟 | 注入 100ms IO 延迟，验证 IO 超时处理 |
| 内存压力 | 注入 512MB 内存压力，验证 OOM 处理 |
| CPU 压力 | 注入 80% CPU 负载，验证 CPU 限流与弹性伸缩 |

## 二、安装

```bash
helm install chaos-mesh design/deploy/charts/chaos-mesh \
  --namespace chaos-mesh --create-namespace
```

## 三、故障注入实验

### 3.1 查看实验状态

```bash
kubectl get networkchaos,podchaos,stresschaos,iochaos -A
```

### 3.2 手动触发实验

```bash
# 触发网络延迟实验
kubectl apply -f design/deploy/charts/chaos-mesh/templates/experiment-network.yaml
```

### 3.3 Dashboard

```bash
kubectl port-forward -n chaos-mesh svc/chaos-mesh-dashboard 2333:2333
# 访问 http://localhost:2333
```

## 四、SLO 稳态验证

详见 [SLO_BASELINE.md](./SLO_BASELINE.md)。

## 五、依赖

- Kubernetes >= 1.23
- Prometheus + Alertmanager（SLO 指标采集与告警）
- Chaos Mesh >= 2.7.0