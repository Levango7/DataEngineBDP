# 部署完整性专项（2026-11-07 主理人批准立项）

> 归属：多平台多租户大数据平台 · 部署面
> 状态：已立项，第一批执行中（认领：zcode 线，留言板 2026-11-07 00:5x 条目）
> 关联：台账 #117（四轴审计 findings）、#106（values 骨架承诺）、#299（13 服务无 chart）、P1-7/P1-8（本专项两大题源）

## 一、专项要解决的三个缺口（源出 #117）

1. **P1-7 配了存储但无 Dockerfile 的六个模块** —— 经盘查实况分级（见 §三），真实缺口 3 个 + 1 个待定 + 2 个误登记。
2. **P1-8 E2E 栈全 mock，与 README 宣称的湖仓栈无一条真实贯通** —— `tests/integration/docker-compose.yml` 32 服务无 PG/Kafka/Trino/Doris/MinIO/Milvus（:686 注释自认「选内存 mock」），MOCK_MODE×4 + AUTH_MODE=none 遍布。
3. **#299 13 服务无 chart/k3s 清单** + P0-4 尾部 6 个 APISIX 未映射前缀（master-data/data-standard/operations-api 等）——与本专项 §一.1 同根：**模块存在但没有部署单元**。

## 二、边界（不与以下事项混同）

- 不替代 1.7.1 真机集群部署验证（真机仍在 GA checklist 域，本次只做「镜像/清单/链路层可部署性」）。
- 不改动前端契约（除同步标注未实现）。
- 不动生产 helm 的鉴权默认态（authMode: jwt 已覆盖，生产面无需本专项处理）。

## 三、P1-7 六模块实况分级（2026-11-07 盘查明细）

| 模块 | 实况（本机核实） | 分级 | 第一批动作 |
|---|---|---|---|
| `platform/data-standard` | 可执行 Spring Boot 服务（DataStandardApplication.java + spring-boot 打包插件 + H2/Flyway） | **真缺口** | 补 Dockerfile（Java 运行层模式，参照 `platform/catalog` 或同族 Java 服务 Dockerfile 的多段式/非 root/健康检查惯例） |
| `platform/master-data` | 同上（MasterDataApplication.java） | **真缺口** | 同上 |
| `platform/operations-api` | 完整 FastAPI 服务（`operations_api/app.py` + `api/` + `jwt_auth.py` + tests），pyproject 名 `sq-operations-api`；**但实部署副本在 `design/deploy/services/operations/`**（k8s.yaml 引用，239 行独立副本= #117 的 operations-api 双实现问题） | **真缺口 + 双实现联动** | 补 Dockerfile 指向 platform 正版；双实现去留属产品裁决（已报主理人，未裁前不动 design 副本） |
| `platform/flink-cdc` | Java 工程，有打包插件、无 Application 主类 | **待定** | 需产品裁「常驻服务 vs 作业提交器」形态后补（判据：是否常驻监听 vs `flink run` 提交） |
| `platform/storage-io` | **纯 Java 库**（`storage/api/ObjectStore` 等接口，无 main、无打包插件；docker/ 内仅 compose 复制场景文件） | **误登记（不是缺口）** | 从「缺 Dockerfile」清单除名；补一条「被哪些服务依赖、以何形态交付（jar）」的说明进 README |
| `platform/chunker` | **纯 Python 包**（无 main/app；仓内无 APISIX 前缀；仅被 chunker/embedding 内部引用） | **误登记（不是缺口）** | 同上除名；确认消费方（nl2sql? llm-gateway?）的依赖声明方式（pip 依赖 vs vendor） |

> 教训入账：#117 初版把「find 无 Dockerfile」直接等同「部署缺口」，未判存在形态。**库 vs 服务的判别先行**（有 Application/main 入口 + 打包插件 + 路由前缀三要素），否则会把「好好的库」补成假部署面——与审计中「mock 命中经核为误报」同类方法论。

## 四、P1-8 湖仓栈真实贯通（phased，需分轮）

现状：compose 栈以 mock 模式跑通链路（MOCK_MODE×4、AUTH_MODE=none、无 PG/Kafka/Trino/Doris/MinIO/Milvus）；README:70,84-85 宣称 Iceberg+Spark+Flink+Trino+Doris+Kafka+IoTDB 湖仓栈。

- **Phase 0（本专项内、低风险）**：把 E2E 断言语义补到「内容级」——修 `tests/integration/e2e/test_e2e_cross_domain.py` 的 404 即 return（:651-653）、开放 API 链不断言内容（:697-699）、计费仅 200（:757-761）、k3s chain1 只验 SQL 非空（:214-248）；现状保持 mock，但断言不再空转。
- **Phase 1（真实栈引入）**：选最小真实存储子集（Postgres + MinIO 替代 SQLite/本地盘）进 compose，逐服务切换存储配置，跑通「写→读」内容级断言。评估点：CI job 时长（账号 ≈20 作业/小时，台账 #102）——**分 job 或 nightly 化**，不塞 PR 必检。
- **Phase 2（Flink/Kafka/Trino/Doris/IoTDB）**：连接件级验证（connector 配置 + 单链路真跑），依赖 Phase 1 存储面稳定后立项细排。

## 五、执行顺序（队列，认领：zcode）

1. ✅ 六模块实况盘查（完成，本文档 §三）
2. 🔄 data-standard + master-data Dockerfile（Java 运行层惯例；mvn 构建产物 + 非 root + 健康检查）
3. ⬜ operations-api Dockerfile（platform 正版；与双实现裁决解耦——先补镜像不改部署引用）
4. ⬜ flink-cdc 形态裁定（报主理人，二选一：常驻服务 or 作业提交器）
5. ⬜ storage-io/chunker README 依赖说明 + 从缺口清单除名（防后人再当缺口补）
6. ⬜ #299/P0-4 尾部 6 前缀的 chart 补齐（master-data/data-standard/operations-api 的 Dockerfile 落地后自然可路由）
7. ⬜ P1-8 Phase 0（E2E 断言内容化）
8. ✅ `PUT /api/v1/clusters/{env}/{id}/network` 永久方案落地（2026-11-07，详见 §五.8）

### 8. PUT /network 永久方案（manifest 自动化审计批 fix/audit-20261106-batch1）

**背景**：前端 `updateNetworkConfig`（infra.ts:604-610）早已调用 `PUT {CLUSTER_BASE}/{env}/{clusterId}/network`，而 orchestrator 只有 GET（ClusterController.getNetworkConfig）、baremetal provider 无任何 PUT ⇒ **405 Not Allowed**（功能不存在，不是错误处理缺失）。

**裁决策略（主理人 11-07：永久性解决，不是临时）——网络 Profile = 持久化意图 + 下次供应/重建生效**：

- **事实依据**：PodCIDR/ServiceCIDR/CNI/MTU 是集群**引导期输入**（`kubeadm init --pod-network-cidr/--service-cidr` + CNI manifest，见 `k8s_bootstrap.go` `InitControlPlane`/`InstallCNI`），不是 K8s 运行期可变属性。因此本方案**不搞"在线热改集群网络"**（K8s 也无此 API），而是把 Profile 写入 Provider 侧持久化字段，由下一次供应/重建路径消费。这与"造一个假 200 回显"有本质区别：改动真实落库、真实生效、语义如实告知前端。

**改动（三层）**：

| 层 | 文件 | 做法 |
|---|---|---|
| provider (Go) | `platform/infra-provider-baremetal/src/internal/model/cluster.go` | `BareMetalCluster` 加 `MTU int` GORM 列（`AutoMigrate` 自动补列）；新增 `NetworkProfile`（cni/podCidr/serviceCidr/ipFamily/mtu，与前端 `NetworkConfig` 契约同名字段）；`K8sConfig` 加可选 `mtu` |
| provider (Go) | `.../internal/service/baremetal_service.go` | `UpdateNetworkConfig`：不存在→404；`validateNetworkProfile`（CNI 限 flannel/calico/cilium——与 `InstallCNI` 真实能力对齐；CIDR 非空 + `net.ParseCIDR` 校验；MTU 1280–9000）⇒ GORM `Updates(map)` 参数绑定落库（无 SQL 拼接）⇒ **回读后返回**；`InitControlPlane` 增补集群级 pod/service CIDR 入参（空则回落引导器默认），使持久化意图真正进入 kubeadm init 参数 |
| provider (Go) | `.../internal/handler/cluster_handler.go` + `main.go` 路由注册 | `rg.PUT("/clusters/baremetal/:id/network", ...)`；400 用 `NetworkProfileValidationError`（errors.As 区分校验错 vs 内部错），与既有 ScaleCluster 400 风格一致 |
| orchestrator (Java) | `SupplyOrchestrator.updateNetworkConfig` | 既有 façade 范式（同 `scaleCluster`）：`descriptor.getClusterUrl(id) + "/network"` PUT 原样转发；拆 `APIResponse.data` 信封回传 Profile |
| orchestrator (Java) | `ClusterController.updateNetworkConfig` | R13 三段式守卫（requireTenant + getClusterInfo + requireTenantOwnedCluster）；Provider 404/501 ⇒ **透传同码 + 「该环境 provider 暂不支持网络配置持久化」**（不造假成功）；Provider 4xx ⇒ 400 + provider 原因；5xx 原样抛出 |
| frontend | `InfraNet.vue`（经 i18n 词条，调用链不变） | `infraNet.messages.configUpdated` zh/en 改为「已保存，Pod/Service CIDR 与 CNI 为引导期输入，下次供应/重建时生效」；按钮 save 同义改写。语义从"已更新"（暗示运行期生效）改为"已保存（下次供应/重建时生效）" |

**验证**：`go build ./... && go test ./...`（baremetal 全绿，新增 PUT 200 落库/404/400×4/401 + service 层 4 例）；`mvn -o test` orchestrator ClusterController 46 例全绿（新增 6 例：200 转发 / 404 透传 / 501 / 400 / 租户拒绝 / 非法 env）；`vue-tsc` 存量 TS5101 外无新增。

**未覆盖（如实记账）**：cloud/private/xinchang 三 Provider 无此端点 ⇒  façade 返回 404/501 诚实错误，不伪造；MTU 已持久化但当前 CNI 安装路径（flannel/calico 标准 manifest 的 `kubectl apply`）未按 MTU 参数化注入，如需按 MTU 生效需在 provider 侧 manifest 模板化后追加（下轮候选）。

## 六、验收判据

- 每个新增 Dockerfile：`docker build --check` 0 告警 + 本地 `docker build` 成功 + 容器以非 root 起 + HEALTHCHECK 绿
- APISIX：`python scripts/gen-apisix-routes.py --check` rc=0 且新服务前缀进入路由
- E2E Phase 0：修完的断言在 mock 模式下**真实失败可复现**（变异测试：破坏产品代码→断言红）
