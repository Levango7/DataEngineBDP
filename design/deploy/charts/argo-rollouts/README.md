# Argo Rollouts 灰度发布 Helm Chart（v2.1 生产化加固）

> 基于 Argo Rollouts 实现金丝雀 + 蓝绿 + 流量镜像多策略渐进式发布，配合 Prometheus 指标自动推进/回滚。

## 一、Chart 说明

本 Chart 安装 Argo Rollouts 控制器，并提供三种发布策略模板与核心服务 Rollout CRD 示例：

| 能力 | 说明 | 适用场景 |
| --- | --- | --- |
| 金丝雀（Canary） | 基于权重逐步切流 20%→40%→60%→80%→100%，每步基于 Prometheus 指标自动判断 | 常规版本发布，需要渐进式验证 |
| 蓝绿（BlueGreen） | 同时维护蓝/绿两套环境，通过 Service selector 秒级切流与回滚 | 需要秒级回滚能力的关键服务 |
| 流量镜像（Mirror） | 将生产流量实时镜像到新版本（不返回响应），无风险验证后切流 | 大版本升级、架构变更等高风险发布 |

## 二、安装

```bash
# 安装到 argo-rollouts 命名空间
helm install argo-rollouts design/deploy/charts/argo-rollouts \
  --namespace argo-rollouts --create-namespace

# 安装 kubectl 插件
kubectl krew install rollouts
```

## 三、三种发布策略使用

### 3.1 金丝雀发布

```bash
# 查看发布状态
kubectl argo rollouts get rollout sq-encaps-layer -n default

# 手动推进到下一步
kubectl argo rollouts promote sq-encaps-layer -n default

# 手动回滚
kubectl argo rollouts abort sq-encaps-layer -n default
```

### 3.2 蓝绿发布

```bash
# 蓝绿发布默认 autoPromotion=false，需手动确认切换
kubectl argo rollouts promote sq-sql-gateway -n default

# 秒级回滚到上一个版本
kubectl argo rollouts undo sq-sql-gateway -n default
```

### 3.3 流量镜像发布

```bash
# 流量镜像阶段（10 分钟）观察新版本指标
kubectl argo rollouts get rollout sq-rule-engine -n default --watch

# 确认无异常后，移除镜像路由并逐步切流
kubectl argo rollouts promote sq-rule-engine -n default
```

## 四、自动推进/回滚指标

AnalysisTemplate `success-rate` 基于 Prometheus 指标自动判断：

| 指标 | 成功条件 | 失败条件 | 失败次数限制 |
| --- | --- | --- | --- |
| 成功率 | ≥ 95% | < 90% | 3 |
| 错误率 | ≤ 5% | > 10% | 2 |
| P99 延迟（ms） | ≤ 500 | > 1000 | 3 |

## 五、核心服务 Rollout 示例

Chart 默认为以下核心服务生成 Rollout CRD 示例：

| 服务 | 策略 | 副本数 | 说明 |
| --- | --- | --- | --- |
| sq-encaps-layer | canary | 3 | 封装层，金丝雀渐进式发布 |
| sq-sql-gateway | blueGreen | 3 | SQL 网关，蓝绿秒级回滚 |
| sq-rule-engine | mirror | 2 | 规则引擎，流量镜像无风险验证 |

## 六、与 Istio 集成

金丝雀与流量镜像策略依赖 Istio VirtualService 进行流量路由：

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: sq-encaps-layer-vsvc
spec:
  http:
    - name: primary
      route:
        - destination:
            host: sq-encaps-layer-stable
          weight: 100
```

## 七、自定义策略

通过 values.yaml 自定义发布策略：

```yaml
strategyTemplates:
  canary:
    enabled: true
    initialPause: "5m"   # 初始观察时间
    stepPause: "3m"      # 每步观察时间
  blueGreen:
    enabled: true
    autoPromotion: true  # 自动切换
  mirror:
    enabled: true
    mirrorDuration: "15m"  # 镜像观察时间
```

## 八、依赖

- Kubernetes >= 1.23
- Istio >= 1.16（金丝雀/流量镜像策略需要）
- Prometheus（AnalysisTemplate 指标来源）