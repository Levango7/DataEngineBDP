# 数据引擎大数据平台 · 端到端 PoC 验证脚本

> 真实可执行的端到端 PoC 脚本，替换 `design/详细设计/多平台多租户大数据平台_端到端PoC详细设计_v0.1.md` 中的 SIM 模拟脚本。
>
> 版本：v0.1 ｜ 日期：2026-08-05 ｜ 状态：生效

## 1. 概述

本目录下的脚本对数据引擎大数据平台进行**真实端到端验证**，覆盖从 SKE 集群拉起、Helm 部署大数据组件、平台 API 验证到端到端数据流的全链路。所有验证均通过 `curl` 调用真实 API 端点，使用 `jq`（或降级到 `python3`）解析 JSON 响应，**不使用任何模拟或桩代码**。

### 与设计文档的关系

- 替换：`design/详细设计/多平台多租户大数据平台_端到端PoC详细设计_v0.1.md`（原 SIM 模拟脚本）
- 对应：`ske/ske.sh`（SKE 集群拉起）
- 对应：`design/deploy/charts/`（13 个 Helm Chart）
- 对应：`platform/encaps-layer/`（封装层 API, port 8080）
- 对应：`platform/sql-gateway/`（SQL 网关, port 8081）
- 对应：`platform/catalog/`（Catalog, port 8082）
- 对应：`platform/rule-engine/`（规则引擎, port 8083）
- 对应：`platform/dqctl/`（dqctl CLI, 端到端流程可选调用）

## 2. 目录结构

```
scripts/poc/
├── run-poc.sh                # 主编排脚本, 按顺序执行 7 个阶段
├── verify-encaps.sh          # 封装层 API 验证 (port 8080)
├── verify-sql-gateway.sh     # SQL 网关 API 验证 (port 8081)
├── verify-catalog.sh         # Catalog API 验证 (port 8082)
├── verify-rule-engine.sh     # 规则引擎 API 验证 (port 8083)
├── README.md                 # 本文档
├── logs/                     # 运行日志 (poc-YYYYMMDD-HHMMSS.log + 各阶段日志)
└── reports/                  # Markdown 汇总报告 (poc-report-YYYYMMDD-HHMMSS.md)
```

## 3. 前置条件

### 必需

| 依赖 | 版本 | 用途 |
|------|------|------|
| bash | ≥ 4.0 | 关联数组、`PIPESTATUS` 等特性 |
| curl | 任意 | HTTP 请求 |
| timeout | GNU coreutils | 阶段超时控制 |
| tee / mktemp / date | coreutils | 日志与临时文件 |

### 可选（按阶段）

| 依赖 | 阶段 | 用途 |
|------|------|------|
| jq | 3-7 | JSON 解析（缺失时降级到 `python3 -m json.tool`） |
| python3 | 3-7 | JSON 解析降级方案 |
| docker | 1, 2 | SKE 节点镜像构建、kind 容器 |
| kubectl | 1, 2 | 集群交互 |
| helm | 2 | 大数据组件部署 |

### 平台组件运行

阶段 3-7 要求以下服务已启动并监听对应端口：

| 组件 | 端口 | 启动方式 |
|------|------|----------|
| encaps-layer | 8080 | `java -jar platform/encaps-layer/target/encaps-layer-0.1.0-SNAPSHOT.jar` |
| sql-gateway | 8081 | `java -jar platform/sql-gateway/target/sql-gateway-0.1.0.jar` |
| catalog | 8082 | `cd platform/catalog && go run ./...` |
| rule-engine | 8083 | `java -jar platform/rule-engine/target/rule-engine-0.1.0.jar` |

## 4. 用法

### 全量端到端 PoC

```bash
./run-poc.sh
```

执行全部 7 个阶段：SKE 拉起 → Helm 部署 → 封装层 → SQL 网关 → Catalog → 规则引擎 → 端到端数据流。

### 集群已存在，仅验证平台 API

```bash
./run-poc.sh --skip-cluster --skip-helm
```

### 仅运行端到端数据流验证

```bash
./run-poc.sh --skip-cluster --skip-helm --skip-platform
```

### 指定 SKE profile 与超时

```bash
./run-poc.sh --profile xinchuang --mode prod --target wsl2 --timeout 600
```

### 单独运行某组件验证

```bash
# 仅验证封装层
./verify-encaps.sh --host 127.0.0.1 --port 8080 --timeout 30

# 仅验证 SQL 网关
./verify-sql-gateway.sh --host 127.0.0.1 --port 8081 --tenant poc-tenant

# 仅验证 Catalog
./verify-catalog.sh --host 127.0.0.1 --port 8082

# 仅验证规则引擎
./verify-rule-engine.sh --host 127.0.0.1 --port 8083
```

### 完整选项

```
./run-poc.sh [选项]

选项:
  --skip-cluster       跳过阶段1 (SKE 集群拉起)
  --skip-helm          跳过阶段2 (Helm 部署)
  --skip-platform      跳过阶段3-6 (平台组件 API 验证)
  --skip-e2e           跳过阶段7 (端到端数据流验证)
  --profile <name>     SKE profile: local|xinchuang|onprem|publiccloud|privatecloud
  --mode <name>        SKE mode: dev|prod
  --target <name>      SKE target: kind|wsl2
  --timeout <sec>      单阶段超时秒数 (默认: 300)
  --host <ip>          平台组件主机 (默认: 127.0.0.1)
  --help, -h           显示帮助
```

## 5. 阶段说明

### 阶段 1: SKE 集群拉起

- 调用：`ske/ske.sh up --profile <p> --mode <m> --target <t>`
- 验证：集群节点 Ready、控制面可达
- 跳过：`--skip-cluster`（假设集群已存在）

### 阶段 2: Helm 部署大数据组件

- 部署顺序（考虑依赖关系）：
  1. keycloak（认证）
  2. kafka（消息）
  3. spark / flink（计算）
  4. trino / doris（查询）
  5. iotdb（时序）
  6. dolphinscheduler / seatunnel（调度与集成）
  7. superset（BI）
  8. apisix（网关）
  9. governance（治理）
  10. theia（IDE）
- 命名空间：`shuqing-poc`
- 跳过：`--skip-helm`

### 阶段 3: 封装层验证（`verify-encaps.sh`）

| 步骤 | 端点 | 期望状态码 |
|------|------|-----------|
| 健康检查 | `GET /api/v1/health` | 200 |
| 创建租户 | `POST /api/v1/tenants` | 201 |
| 列出租户 | `GET /api/v1/tenants` | 200 |
| 获取租户 | `GET /api/v1/tenants/{id}` | 200 |
| 更新租户 | `PUT /api/v1/tenants/{id}` | 200 |
| 删除租户 | `DELETE /api/v1/tenants/{id}` | 204 |

租户 `quotaProfile` 严格使用 `base` / `standard` / `flagship`（遵循 `CONVENTIONS.md §1`）。

### 阶段 4: SQL 网关验证（`verify-sql-gateway.sh`）

| 步骤 | 端点 | 期望 |
|------|------|------|
| 健康检查 | `GET /api/v1/health` | 200 |
| 列出引擎 | `GET /api/v1/sql/engines` | 含 `trino`、`doris` |
| 列出路由 | `GET /api/v1/sql/routes` | 200 |
| 执行 SQL | `POST /api/v1/sql/execute` | 200，返回 `queryId` |
| 添加路由 | `POST /api/v1/sql/routes` | 200/201 |

### 阶段 5: Catalog 验证（`verify-catalog.sh`）

| 步骤 | 端点 | 期望状态码 |
|------|------|-----------|
| 健康检查 | `GET /api/v1/health` | 200 |
| 创建数据库 | `POST /api/v1/catalog/databases` | 201 |
| 创建表 | `POST /api/v1/catalog/tables` | 201 |
| 列出表 | `GET /api/v1/catalog/tables` | 200 |
| 获取表 | `GET /api/v1/catalog/tables/{id}` | 200 |
| 更新表 | `PUT /api/v1/catalog/tables/{id}` | 200 |
| 删除表 | `DELETE /api/v1/catalog/tables/{id}` | 204 |

### 阶段 6: 规则引擎验证（`verify-rule-engine.sh`）

| 步骤 | 端点 | 期望 |
|------|------|------|
| 健康检查 | `GET /api/v1/health` | 200 |
| 列出规则类型 | `GET /api/v1/rules/types` | 含 `DQ`、`MASK`、`ALERT` |
| 创建规则 | `POST /api/v1/rules` | 201 |
| 列出规则 | `GET /api/v1/rules` | 200 |
| 执行规则 | `POST /api/v1/rules/execute` | 200，返回 `status` |
| 删除规则 | `DELETE /api/v1/rules/{id}` | 204 |

### 阶段 7: 端到端数据流验证

完整链路：

```
封装层创建租户
    ↓
Catalog 创建数据库 + 表
    ↓
SQL 网关执行 SELECT 1 (engine=trino, tenant=新租户)
    ↓
规则引擎创建 DQ 规则 + 执行
    ↓
清理 (删除租户)
```

## 6. 输出与日志

### 终端输出

使用 ANSI 颜色（自动检测 TTY）：

- `[PASS]` 绿色加粗
- `[FAIL]` 红色加粗
- `[WARN]` 黄色
- `[PoC]` / `[ENCAPS]` / `[SQLGW]` / `[CATALOG]` / `[RULENG]` 青色阶段标识

### 日志文件

| 文件 | 说明 |
|------|------|
| `logs/poc-YYYYMMDD-HHMMSS.log` | 主编排日志，含所有阶段输出 |
| `logs/stage-<name>-YYYYMMDD-HHMMSS.log` | 单阶段日志 |
| `logs/verify-encaps-YYYYMMDD-HHMMSS.log` | 封装层验证日志 |
| `logs/verify-sql-gateway-YYYYMMDD-HHMMSS.log` | SQL 网关验证日志 |
| `logs/verify-catalog-YYYYMMDD-HHMMSS.log` | Catalog 验证日志 |
| `logs/verify-rule-engine-YYYYMMDD-HHMMSS.log` | 规则引擎验证日志 |
| `reports/poc-report-YYYYMMDD-HHMMSS.md` | Markdown 汇总报告 |

### 退出码

| 退出码 | 含义 |
|--------|------|
| 0 | 全部阶段 PASS |
| 1 | 存在 FAIL 阶段 |
| 2 | 前置依赖缺失 |

## 7. 故障排查

### 健康检查失败（阶段 3-6 第一步）

**现象**：`[FAIL] 健康检查 (rc=1)`，HTTP_CODE 为 `000` 或 `502`/`503`。

**排查**：

1. 确认服务进程已启动：`ss -tlnp | grep -E '8080|8081|8082|8083'`
2. 确认端口未被防火墙拦截：`curl -v http://127.0.0.1:8080/api/v1/health`
3. 查看服务自身日志（Spring Boot 应用的 stdout / Go 服务的日志）

### 创建租户返回 200 而非 201

**现象**：`[FAIL] 创建租户`，但 HTTP_CODE=200。

**原因**：部分实现以 200 替代 201 表示创建成功。

**处理**：脚本已内置兼容逻辑，会自动将 200 视为成功并打印 `(兼容: 实际返回 200)`。

### 删除返回 200 而非 204

**现象**：`[FAIL] 删除租户`，HTTP_CODE=200。

**处理**：脚本已内置兼容，自动转 PASS。

### jq 不可用

**现象**：`[WARN] jq 不可用, 降级到 python3`。

**处理**：脚本自动降级到 `python3`，仅支持简单字段提取（`.field` 形式）。建议安装 jq 以支持完整 JSON 查询：

```bash
# Ubuntu/Debian
sudo apt-get install -y jq
# CentOS/RHEL
sudo yum install -y jq
# macOS
brew install jq
```

### 阶段超时

**现象**：`[FAIL] 阶段 [xxx] TIMEOUT (超过 300s)`。

**处理**：通过 `--timeout` 增大超时，或排查对应阶段为何卡住（如 Helm `--wait` 等待 Pod Ready 时间过长）。

### Helm 部署失败

**现象**：阶段 2 某个 Chart 部署失败。

**排查**：

```bash
# 查看失败 Release
helm -n shuqing-poc list --all
# 查看 Pod 状态
kubectl -n shuqing-poc get pods
kubectl -n shuqing-poc describe pod <pod-name>
```

### SKE 集群拉起失败

**现象**：阶段 1 失败。

**排查**：

```bash
# 直接运行 ske.sh 查看详细输出
bash ske/ske.sh up --profile local --mode dev --target kind
# 查看 kind 集群
kind get clusters
kubectl get nodes
```

## 8. 设计约束

- **真实调用**：所有验证通过 `curl` 调用真实 HTTP API，不使用 mock / stub / sim。
- **严格模式**：所有脚本使用 `set -euo pipefail`。
- **独立可运行**：每个 `verify-*.sh` 可独立执行，也可通过 `run-poc.sh` 编排。
- **超时控制**：`run-poc.sh` 使用 `timeout --preserve-status` 包裹每阶段；`verify-*.sh` 通过 `curl --max-time` 控制。
- **日志双写**：所有输出同时写入终端与日志文件（`tee -a`）。
- **命名遵循 `CONVENTIONS.md`**：套餐用 `base`/`standard`/`flagship`，工作空间用 `ws-` 前缀。
- **幂等友好**：每次运行使用 `$$`（PID）作为租户/库/表名后缀，避免冲突。

## 9. 后续演进

- [ ] 接入 `dqctl` CLI 端到端验证（`init → apply → query → status`）
- [ ] 接入真实 Trino/Doris 后端后，SQL 执行验证从 `SIMULATED` 升级为真实结果集断言
- [ ] 接入 Keycloak 鉴权后，curl 请求携带 Bearer Token
- [ ] 接入 APISIX 网关后，统一通过网关入口（而非直连各服务端口）
- [ ] 增加 Prometheus 指标采集验证（`/actuator/metrics`）
- [ ] 增加跨租户隔离性验证（创建租户 A/B，验证 A 不可见 B 的资源）