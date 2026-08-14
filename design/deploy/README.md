# 部署骨架（deploy/）

> 派生自《部署清单详细设计 v0.1》§12。把"方案 + 控制台 UI + 后端契约"变成可部署物。
> **铁律**：Kubernetes 一律在客户自有机器上自建；云只作"裸 VM + 块存储 + 网络"，
> 禁用任何云厂商托管 K8s（ACK/EKS/TKE/CCE）及云托管 DB/MQ/对象存储。

## 目录

```
deploy/
├── values-base.yaml        # 环境无关基础 values（组件清单 + 默认值）
├── profiles/               # 四环境 Profile（覆盖 8 抽象维度）
│   ├── xinchuang.yaml      #   信创（国密启用、国产对象存储、openeuler 变体）
│   ├── onprem.yaml         #   本地数据中心（Ceph、Vault KMS）
│   ├── publiccloud.yaml    #   公有云 VM（客户提供 S3 密钥，不绑云托管）
│   └── privatecloud.yaml   #   私有云 VM（厂商对象存储 / KMS）
├── ci/
│   └── build-images.yaml   # 多 arch 镜像 CI（buildx + 扫描 + 签名）
└── scripts/
    └── preflight.sh        # 部署前能力探测，输出能力矩阵
```

## 渲染与安装

```bash
# 1) 选环境（xinchuang / onprem / publiccloud / privatecloud）
ENV=onprem

# 2) 渲染：逐 Chart 渲染（无顶层聚合 Chart；80 个骨架 Chart 需先补全镜像/探针，见各 Chart README）
mkdir -p rendered
for chart in encaps-layer sql-gateway catalog; do
  helm template "$chart" "charts/$chart" \
    -f values-base.yaml \
    -f profiles/$ENV.yaml \
    > "rendered/$ENV-$chart.yaml"
done
# 说明：design/deploy/charts 下 81 个 Chart 为独立部署单元，无顶层 sq-bigdata 聚合 Chart。
# 生产建议使用 ArgoCD ApplicationSet（argocd/applicationsets/platform-engines.yaml）批量编排。

# 3) 部署（封装层先于平台组件，见 values-base.yaml encapsulation.enabled）
kubectl apply -f rendered/$ENV-encaps-layer.yaml
```

## 多 Arch 镜像

`ci/build-images.yaml` 用 `docker buildx` 产出 `amd64 + arm64` manifest；
信创环境（`VARIANT=openeuler`）额外产出 openEuler 基础镜像变体。
国产组件（Doris / SeaTunnel / DolphinScheduler / IoTDB / KubeSphere / openGauss）优先选用，
降低 ARM 构建适配风险；计算引擎走 Java 生态天然跨架构。

## preflight 能力矩阵

部署前运行 `scripts/preflight.sh`，逐项检查 arch / 国产 OS / 对象存储后端 / 国密 / LB，
输出能力矩阵（呼应部署清单 §10），缺失项降级或告警，避免四环境行为漂移。

## 部署阶段划分（P0→P1→P2）

| 优先级 | 组件 | Chart |
| --- | --- | --- |
| P0 | 封装层 / 统一存储 / Spark·Flink·Doris·Trino / 统一 SQL / 控制台 | 首个可演示 MVP |
| P1 | 治理全套（元数据/标准/质量/血缘/资产/安全）/ 集成调度 | 渐进补全 |
| P2 | 智能数据层（向量/知识/LLMOps/网关） | 最后补齐 |

> 下一步：端到端验证 PoC（Doris + Iceberg 湖仓联动，呼应 v0.4 演进路线）。
