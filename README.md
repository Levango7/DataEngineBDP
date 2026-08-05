# 数擎大数据平台 · ShuqingBigDataPlatform（项目统一根）

> 单一项目根：设计文档 + 自研 K8s 发行版（SKE）运行代码**全部收纳于此**，**单目录、不跨盘、不散乱**。
> 拼音：数擎 = shù qíng → **Shuqing**（SKE = Shuqing Kubernetes Engine），非 Shuqian。

## 目录结构

```
ShuqingBigDataPlatform/
├── design/                      # 设计 + 部署文档（纯文档 / 设计稿）
│   ├── 多平台多租户大数据平台_产品原型设计_v0.4.md   # 总体方案（五层 + 49 模块，内容为 v0.5）
│   ├── 数擎大数据平台_控制台原型_v0.3.html           # 最新 UI 原型（小众线性 SVG 图标）
│   ├── 数擎大数据平台_控制台原型_v0.1.html           # 历史版本
│   ├── 详细设计/                                       # 43 份模块详细设计（文档级，工程实现进行中）
│   └── deploy/                  # 部署设计态骨架（早期设计稿，含运营后台代码 services/operations）
├── ske/                         # 数擎云核 SKE 自研 K8s 发行版 v0.1（见 ske/README.md）
├── platform/                    # 平台引导 bootstrap.sh + in-cluster MinIO
├── examples/                    # 端到端 PoC（SIM 模式演示脚本，非真实跑通）
└── docs/                        # SKE 设计文档索引（即 docs/README.md）
```

## 运行态 vs 设计态（诚实化声明）

- `ske/ platform/ examples/` 是**集群引导与演示脚本（SIM 模式）**，端到端真实跑通尚在开发中。
  - `ske/`：SKE 发行版引导脚本（kind/kubeadm 包装 + 调优），当前两条拉起路径均存在已知缺陷，正在修复。
  - `platform/`：bootstrap.sh 建立 `ws-demo` namespace + in-cluster MinIO，可独立运行。
  - `examples/`：run-demo.sh 检测到 dqctl 缺失即进入 SIM 模式（echo 演示），不是真实端到端 PoC。
- `design/deploy/` 是**设计态骨架**（早期部署设计稿，与运行态并行演进，不作为实际部署入口）。
- `design/详细设计/` 43 份详设**已完成文档级设计**，工程实现进行中（详见下方能力真实性矩阵）。

## 能力真实性矩阵

> 评估日期：2026-08-05 ｜ 评估方式：全仓库逐行代码审计 + 设计文档交叉验证
> 状态分级：文档级（仅有设计文档）｜ 原型级（有 HTML/前端原型）｜ 脚本级（有 Shell 脚本但未验证）｜ 服务级（有可运行的服务代码）

| 模块 / 能力 | 真实状态 | 证据 |
| --- | --- | --- |
| L0.6 自研 SKE 发行版 | 脚本级 | `ske/ske.sh` 282 行 bash 包装 kind/kubeadm；七大支柱多数仅有调优脚本，scheduler-policy 引用不存在的插件 |
| L0.7 Cilium 网络 | 脚本级 | `ske/manifests/cilium-values.yaml` 存在但未 helm install 验证；socketLB.mode 配置非法值 |
| L0.8 容器存储 | 文档级 | 仅有 values 配置，无 CSI 部署实体 |
| L0.9 可观测基座 | 文档级 | values 引用 Prometheus/Grafana/Loki/Tempo 但无 Chart 实体 |
| L0.11 K8s 封装层 | 文档级 | `design/详细设计/封装层详细设计_v0.1.md`；`platform/bootstrap.sh` 仅硬编码 ws-demo 一段 kubectl apply |
| L2.1 统一存储（Iceberg） | 文档级 | 仅有详细设计文档，无 Iceberg Catalog REST 服务代码 |
| L2.2 Spark 批计算 | 文档级 | 仅有 values 配置，无 Spark Operator 部署实体 |
| L2.3 Flink 流计算 | 文档级 | 仅有 values 配置，无 Flink Operator 部署实体 |
| L2.4 Trino 交互查询 | 文档级 | 仅有 values 配置与一份静态 SQL 文件 |
| L2.5 Doris OLAP | 文档级 | 仅有 values 配置，无 Doris Operator 部署实体 |
| L2.7 统一 SQL 网关 | 文档级 | `统一SQL网关详细设计_v0.1.md`；零代码实现 |
| L3.1-L3.7 治理中台 | 文档级 | `治理中台详细设计_v0.1.md` 等多份文档；自研 Catalog/规则引擎/血缘解析零代码 |
| L4.1-L4.4 数据开发工具链 | 文档级 | 4 份详细设计文档；SeaTunnel/DolphinScheduler/Theia/Superset 均无部署实体 |
| L4.5.3-L4.5.6 智能数据层 | 文档级 | `智能数据层详细设计_v0.1.md`；Milvus/NebulaGraph/RAG/LLMOps 零代码 |
| L5.1 统一控制台 | 原型级 | `frontend/` Vue3+TS strict 骨架真实，但功能完成度 0%（零 API 调用、零 v-model） |
| L5.2 运营后台 | 服务级 | `design/deploy/services/operations/main.py` 152 行 FastAPI 可运行；但租户态存内存、看板返回硬编码假数据 |
| L5.3-L5.6 行业模板/门户/API/资产流通 | 文档级 | 仅有详细设计文档 |
| X1-X4 横切（身份/安全/运维/网关） | 文档级 | 4 份详细设计文档；Keycloak/APISIX 仅有 values 配置 |
| 端到端 PoC | 脚本级 | `examples/run-demo.sh` SIM 模式 echo 演示；PoC SQL 占位符无替换逻辑，表命名互不衔接 |
| Helm Chart | 文档级 | 全仓库无 Chart.yaml；46 个 Chart 仅在部署清单文档中描述 |
| CI/CD | 文档级 | `design/deploy/ci/build-images.yaml` 在错误位置（无 .github/workflows/），依赖的 Dockerfile/unit-test.sh 全部缺失 |
| 测试 | 无 | 全仓库零单测/集成/E2E，唯一"验证"是 SIM 模式 echo 演出 |
| 运维（监控/告警/备份） | 文档级 | 零 PrometheusRule、零 Grafana dashboard JSON、零备份脚本 |

**实现覆盖度估算：约 3–5%（相对文档声称的 49 模块 + 15 自研组件范围）。**

## 快速开始（在你笔记本执行；沙箱内 Docker/WSL2 不可达）

详见 `ske/WSL2-QUICKSTART.md`。WSL2 真 kubeadm 路：
```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform
sudo bash ske/wsl2/setup-host.sh
sudo bash ske/ske.sh tune-host
sudo bash ske/ske.sh up --target wsl2 --profile local
bash platform/bootstrap.sh --profile local
bash examples/run-demo.sh
```

> ⚠️ **诚实提示**：上述流程当前**尚未端到端跑通**。已知问题：SKE kind/kubeadm 路径有配置缺陷、`examples/run-demo.sh` 进入 SIM 模式、PoC SQL 占位符未渲染。修复路线见评估报告"改进路线图"。

## 关键约束

- K8s 一律自建，禁用云托管（ACK/EKS/TKE/CCE）；SKE 是深度定制封装的自研发行版，非 kubeadm/k3s/kind 原样。
- 验证环境：笔记本 x86_64 + Docker Desktop + kubectl；无信创机器（信创 Profile 仅交付用）。
- 命名规范：见 `CONVENTIONS.md`（套餐 base/standard/flagship、工作空间 ws-<name>、模块计数 49、版本号 v0.5/SKE v0.1）。
