# 信创双线云租机验证方案（鲲鹏 + 海光）

> 日期：2026-09-19 ｜ 状态：草案 v1（价格项待官网核实，见 §6）
> 目标：为 DataEngineBDP 的信创环境（`docs/环境验证状态.md` 环境 #3 xinchuang.yaml）提供
> 双线（鲲鹏 ARM / 海光 x86）可靠测试验证，支撑最终交付。
> 原则：配置档位"相对可以"（非最低配）、无出处依据不用、价格不确定即标注待核实。

---

## 1. 选型决策（含出处）

| 决策 | 结论 | 依据 |
| --- | --- | --- |
| 信创主流 CPU | **鲲鹏（ARM）与海光（x86）双主流，鲲鹏集采数量领先** | 中国联通 2025 通用服务器集采：鲲鹏 4.82 万台（55.3%）、海光 3.02 万台（34.8%）、Intel 9.9%（每经网 2025-10-05）；浦发银行 2025 信创大单鲲鹏 1.58 亿 + 海光四号 2.15 亿同时中标 |
| 鲲鹏在哪租 | **华为云**（鲲鹏 920 为华为自研，公有云仅华为云提供 kc1/kc2 系列） | 华为云 ECS 产品文档；阿里云无鲲鹏实例（其 ARM 为倚天 710） |
| 海光在哪租 | **天翼云**（国产化云主机资源池含海光 x86） | 天翼云 ECS 产品页"国产化云主机"分类 + 信创专区（ctyun.cn/products/ecs，2026-09 访问） |
| 操作系统 | **openEuler 22.03 LTS 为主（免费）+ 每线 1 台麒麟 V10（OS 兼容性证据）** | 华为云 IMS 文档确认鲲鹏实例支持 openEuler 22.03/20.03 与 Kylin V10（ARM）；阿里云市场有麒麟 V10 ARM/X86（¥1.5/h 量级） |
| 为什么 OS 不全用麒麟 | 麒麟/统信镜像在云市场按 ¥/h 收费，全用会显著抬高成本；验证目标只需证明"平台在麒麟上可运行"，1 台/线足够 | 阿里云市场麒麟 V10 定价页 |
| 海光为何可低配一档 | 海光是 x86 兼容指令集，平台现有 amd64 镜像**原生可跑**，无重新构建风险；验证重点是"国产 x86 可承载"背书而非指令集适配 | 海光公开架构资料（x86 兼容）；本项目镜像均为 amd64 |

---

## 2. 鲲鹏线方案（华为云 · 主信创验证线）

**验证目标**：证明平台可在 鲲鹏920(ARM) + openEuler/麒麟 上完成
部署 → 起服务 → 端到端查询 → 国密 SM2/3/4 → TLCP → 国产对象存储（对齐 xinchuang.yaml 清单）。

| 节点 | 规格 | 数量 | 角色与部署内容 | OS |
| --- | --- | --- | --- | --- |
| 网关/控制面 | 4C16G（kc1 或 kc2 通用计算增强型） | 1 | K8s master、APISIX、Keycloak、nacos、PostgreSQL、sql-gateway、encaps-layer | openEuler 22.03 |
| 计算引擎 A | 8C32G（同上） | 1 | Trino、Doris、Spark、Flink | **麒麟 V10 ARM**（OS 兼容证据） |
| 计算引擎 B | 8C32G（同上） | 1 | Kafka、Flink TaskManager、Spark Worker（弹性分担） | openEuler 22.03 |
| 存储/数据源 | 4C16G | 1 | MinIO（国产对象存储口径）、MySQL 数据源、E2E runner | openEuler 22.03 |

- **合计 4 台**，建议统一放在同一 VPC、同可用区。
- **关键前置（最高风险项）**：全部服务镜像需 arm64 版本。在租用窗口内，
  用其中 1 台鲲鹏机做**原生 arm64 构建**（比 x86 QEMU 交叉构建快且稳）：
  - 自研服务：Java（多阶段 Dockerfile 天然跨架构）/ Go（GOARCH=arm64）/ Python（多为纯 Py 或需重新编 wheel）
  - 第三方：Spark 3.5 / Flink 1.20 / Trino 460 / Doris 2.1 的官方镜像需逐一确认 arm64 manifest；
    缺 arm64 的改用源码构建或降级版本，**这决定鲲鹏线的成败，第一天必须先验证**。

**租赁时长**：按需付费 **100 小时/台**（建议夜间/闲置关机省实例费，云盘继续计）。
**系统盘**：超高 IO SSD 100G/台；数据盘：引擎节点另挂 200G（Iceberg/Doris 数据）。

---

## 3. 海光线方案（天翼云 · 国产 x86 验证线）

**验证目标**：证明平台现有 amd64 交付物可无损运行在 海光 + 麒麟 上
（部署可起、端到端可跑、性能不低于 x86 基线的合理区间）。

| 节点 | 规格 | 数量 | 角色与部署内容 | OS |
| --- | --- | --- | --- | --- |
| 网关/控制面 | 4C16G（通用计算增强型 C8） | 1 | K8s master、APISIX、Keycloak、PostgreSQL、sql-gateway | **麒麟 V10 x86**（OS 兼容证据） |
| 计算引擎 A | 8C32G（C8 或内存优化 M8） | 1 | Trino、Doris | openEuler 22.03 |
| 计算引擎 B | 8C32G | 1 | Spark、Flink、Kafka | openEuler 22.03 |
| 存储/数据源 | 4C16G（通用计算型 S8 即可） | 1 | MinIO、MySQL 数据源、E2E runner | CTyunOS（免费，顺验证） |

- **合计 4 台**，同 VPC。现有 amd64 镜像直接用，**无需重新构建**（这是与鲲鹏线的本质差异）。
- 天翼云 ECS 页确认的可用镜像族：自研 CTyunOS、KylinOS、OpenEuler 及第三方镜像（产品页原文）。

**租赁时长**：按需付费 **50 小时/台**（x86 无构建风险，部署联调时间减半即可覆盖验收）。
**系统盘**：100G/台；数据盘：引擎节点 200G。

---

## 4. 验证排期（墙钟估算，两线并行）

| 阶段 | 鲲鹏线 | 海光线 | 产出 |
| --- | --- | --- | --- |
| D1 环境与构建 | arm64 镜像构建验证（关键路径）+ 基础服务起 | 平台部署 + 基础服务起 | 镜像清单与构建记录 |
| D2-D4 部署联调 | 平台部署、Chart 渲染、服务起全 | 端到端链路联调 | 部署日志 |
| D5-D7 功能验收 | 跑 `sim/start-sim.ps1` 等效验收 + P0 复测（网关返回真实行） | 同左 | 验收记录 sim-results 等效物 |
| D8-D10 信创专项 | SM2/SM3/SM4、TLCP 证书链、MinIO 国产存储对接 | 国密与 TLCP 抽验 | 信创验证报告 |
| D11-D14 性能与交付 | 性能冒烟（QPS/P99 基线）、交付演示、报告定稿 | 性能对照（vs x86 基线） | 性能报告 + 交付物 |

**总周期**：约 2 周（两线并行，含 20% 缓冲）。
**实例时**：鲲鹏线 4 台 × 100h = 400 实例时；海光线 4 台 × 50h = 200 实例时。

## 5. 验收通过标准（判定用，写进交付）

1. 两条线均完成 `docs/环境验证状态.md` 中信创行全部维度（部署、服务、链路、国密、TLCP、存储）。
2. `sql-gateway` 端到端返回**真实数据行**（复用本轮 P0 修复的判定：rows == 2 且 columns 非空）。
3. 无令牌访问 401、跨租户越权 403（鉴权基线）。
4. 鲲鹏线全部服务以 arm64 镜像运行（`docker inspect` 记录架构字段）。
5. 性能冒烟：相对 x86 基线，鲲鹏/海光 QPS 衰减 ≤ 30%（阈值可调，但需记录实测值）。

## 6. 费用估算（公式 + 参考锚点，**官方价待核实**）

**计费公式**：实例费 = Σ(规格价/时 × 时长) + 云盘(GB·时) + EIP 带宽 + 市场镜像费(麒麟/统信，如有)。

**参考锚点（阿里云北京按量实测口径，仅作量级参考，非华为/天翼报价）**：
x86 4C16G ≈ 0.99 元/h、8C32G ≈ 1.99 元/h；ARM 4C16G ≈ 0.80 元/h、8C32G ≈ 1.60 元/h。

| 线 | 实例费估算 | 盘+带宽估算 | 镜像费 | 合计估算 |
| --- | --- | --- | --- | --- |
| 鲲鹏线（400 实例时） | ≈ 0.8×100×2 + 1.6×100×2 = **480 元** | ≈ 60–100 元 | 麒麟 1 台 ≈ 150 元（¥1.5/h×100h，若收费） | **≈ 700–750 元** |
| 海光线（200 实例时） | ≈ (0.99×2 + 1.99×2)×50 = **298 元** | ≈ 40–60 元 | 麒麟 1 台 ≈ 75 元（50h） | **≈ 420–450 元** |
| **两线合计** | | | | **≈ 1,100–1,200 元（上限预留 1,500 元）** |

> ⚠️ 以上为**量级估算**。华为云 kc1/kc2 与天翼云海光机型的官方按量价，
> 需在下单前以各自官网价格计算器核实后回填本表（华为云价格计算器为 JS 页面，自动化无法取数）。

## 7. 其他服务费用清单（独立计费项）

- **云硬盘**：系统盘 100G×8 + 数据盘 200G×4，按 GB·时 计（超高 IO SSD）。
- **弹性公网 IP/带宽**：对外暴露的网关节点各 1 个 EIP，固定带宽 ≤5M 档位；内部节点不出网。
- **市场镜像**：麒麟 V10 / 统信镜像若走云市场按 ¥/h 或 ¥/次（阿里云市场麒麟 V10 参考 ¥1.5/h；华为云侧待核实）。
- **不启用**（控成本）：SWR/CCE、快照、云备份、负载均衡 ELB（验证期用节点直连）。

## 8. 风险与对策

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| 第三方引擎镜像缺 arm64（鲲鹏线生死项） | 高 | D1 优先验证；缺的改源码构建或降级版本；全部记录在案 |
| 自研 Python 组件含本地扩展（如 polars/pyarrow wheel 无 arm64） | 中 | 构建时编译或锁定有 arm64 wheel 的版本 |
| 麒麟/统信镜像费高于预期 | 中 | 默认 openEuler（免费），麒麟仅每线 1 台 |
| 华为云价格未知导致预算偏差 | 中 | 先按本文公式预留 1.5× 上限，下单前核实回填 |
| 海光 x86 验证价值被质疑（指令集无差异） | 低 | 其价值是"国产 CPU 可承载"的客户背书与 BIOS/固件层兼容，如实记录 |

## 9. 执行前置（上云前必须完成）

1. **本地先跑通** `F:\Agent\cline\workspace\notes\DataEngineBDP-audit-20260917\sim\start-sim.ps1`
   （当前阻塞：Docker Desktop 未启动，需手动重启后复跑）——确认 P0 修复闭环（rows==2）。
2. 提交本轮 4 个修复文件（BackendProxyService.java、trino 两个 catalog、vitest.config.ts）。
3. 准备 arm64 构建脚本与镜像清单（见 §2 关键前置）。
4. 按本文 §6 核实华为云/天翼云官方价并回填预算表，确认后下单。

---
---

## 附录：鲲鹏线 arm64 镜像可用性清单（D1 关键前置）

| 组件 | 镜像 | arm64 支持 | 备注 / D1 对策 |
|---|---|---|---|
| Trino | trinodb/trino:460 | ✅ 有（multi-arch） | 直接用 |
| Spark | apache/spark:3.5.3 | ✅ 有（3.5+ 全架构） | 直接用 |
| Flink | apache/flink:1.20.0-scala_2.12 | ✅ 有（1.18+ 全架构） | 直接用 |
| Kafka | apache/kafka:3.8.1 | ✅ 有 | 直接用 |
| Doris FE/BE | apache/doris:2.1.7 / selectdb/doris.be:2.1.7 | ⚠️ **FE 无官方 arm64，BE 有** | **D1 必须自建 FE arm64**（源码编译） |
| MinIO | minio/minio:RELEASE.2024-05-28T17-19-04Z | ✅ 有 | 直接用 |
| PostgreSQL | postgres:16 | ✅ 有 | 直接用 |
| Redis | redis:7.2-alpine | ✅ 有 | 直接用 |
| APISIX | apache/apisix:3.9.0-debian | ✅ 有 | 直接用 |
| Superset | apache/superset:3.1.0 | ⚠️ 仅 amd64 | **D1 自建 arm64 或降级到 2.1.0** |
| DolphinScheduler | apache/dolphinscheduler:3.2.1 | ⚠️ 仅 amd64 | **D1 自建或降级** |
| SeaTunnel | apache/seatunnel:2.3.7 | ⚠️ 仅 amd64 | **D1 自建或降级** |
| IoTDB | apache/iotdb:2.0.2 | ⚠️ 仅 amd64 | **D1 自建或降级** |
| Nacos | nacos/nacos-server:v2.4.0 | ✅ 有（2.3+ arm64） | 直接用 |
| Keycloak | quay.io/keycloak/keycloak:25.0 | ✅ 有 | 直接用 |
| Prometheus | prom/prometheus:v2.54 | ✅ 有 | 直接用 |
| Grafana | grafana/grafana:11.1 | ✅ 有 | 直接用 |
| Loki / Tempo | grafana/loki:3.0 / grafana/tempo:2.4 | ✅ 有 | 直接用 |
| Elasticsearch | docker.elastic.co/elasticsearch/elasticsearch:8.15 | ✅ 有 | 直接用 |
| NebulaGraph | vesoft/nebula-graphd:3.6.0 | ⚠️ 仅 amd64 | **D1 自建或降级到 3.5** |
| Milvus | milvusdb/milvus:v2.3.3 | ✅ 有（2.3+ 支持） | 直接用 |
| Iceberg REST Catalog | tabulario/iceberg-rest-catalog | ⚠️ 仅 amd64 | **D1 自建** |
| Hive Metastore | bitsondatadev/hive-metastore | ⚠️ 仅 amd64 | **D1 自建或用 Trino 自带 HMS** |
| Argo Rollouts | argoproj/argo-rollouts | ✅ 有 | 直接用 |
| KEDA | kedacore/keda | ✅ 有 | 直接用 |
| Cilium | cilium/cilium | ✅ 有 | 直接用 |
| MinIO (旧版) | minio/minio:RELEASE.2024-05-28T17-19-04Z | ✅ 有 | 直接用 |
| Elasticsearch | docker.elastic.co/elasticsearch/elasticsearch:8.15 | ✅ 有 | 直接用 |
| Prometheus / Grafana / Loki / Tempo | prom/prometheus:v2.54 / grafana/grafana:11.1 等 | ✅ 有 | 直接用 |
| Argo Rollouts / KEDA / Cilium | argoproj/argo-rollouts / kedacore/keda / cilium/cilium | ✅ 有 | 直接用 |
| 自研镜像 | sq-* 系列 | 🔧 **需自行构建 arm64** | Java/Go 多阶段构建、Go GOARCH=arm64、Python 依赖 wheel |

**统计**：约 12 个直接可用、8 个需自建/降级。生死项（D1 必须先搞定）：Doris FE、Superset、NebulaGraph、SeaTunnel、IoTDB、Iceberg Catalog、Hive Metastore。

**D1 执行清单**：

```bash
# 1. 逐个验证镜像架构
for img in trinodb/trino:460 apache/spark:3.5.3 apache/flink:1.20.0-scala_2.12 \
           apache/kafka:3.8.1 minio/minio:RELEASE.2024-05-28T17-19-04Z \
           postgres:16 redis:7.2-alpine apache/apisix:3.9.0-debian \
           quay.io/keycloak/keycloak:25.0 prom/prometheus:v2.54 \
           grafana/grafana:11.1 vesoft/nebula-graphd:3.6.0 \
           milvusdb/milvus:v2.3.3 nacos/nacos-server:v2.4.0; do
  docker manifest inspect $img | jq '.manifests[].platform.architecture' | sort -u
done

# 2. 缺 arm64 的列入自建清单
# Doris FE: git clone https://github.com/apache/doris.git -b branch-2.1 && ./build.sh --fe --arm64
# Superset: 降级到 2.1.0（有 arm64）或社区 arm64 fork
# NebulaGraph: 降级到 3.5（有 arm64）或社区 fork
# Iceberg Catalog / Hive Metastore: 源码构建 arm64
```

---

*本方案为草案 v1，价格项以华为云/天翼云官网价格计算器为准；依据不足处已标注"待核实"。*
*本方案为草案 v1，价格项以华为云/天翼云官网价格计算器为准；依据不足处已标注"待核实"。*
