# 端到端演示脚本包（湖仓集联动）

> 对应文档：`多平台多租户大数据平台_端到端PoC详细设计_v0.1.md`
> 目标：把 PoC 文档中的步骤抽成可直接执行的文件，在已部署的平台上跑通 `MySQL → Iceberg(湖) → Spark(仓) → Doris(集) → 统一 SQL 网关` 全流程。
> 客户视角：全程只使用封装层 CLI `dqctl`，不接触任何 K8s 资源。

## 目录结构

```text
examples/
├── README.md                      # 本文件
├── run-demo.sh                    # 一键串联全流程（含模拟运行日志）
├── 00-bootstrap-workspace.sh      # 客户视角：建工作空间 + 数据项目
├── 01-cdc-iceberg.sql             # Flink CDC 实时入湖（湖层 ods）
├── 02-spark-dwd.sql               # Spark 湖→仓主题建模（dwd/dws）
├── 03-doris-catalog.sql           # Doris External Catalog + 物化视图（集层）
└── 04-unified-query.sql           # 统一 SQL 网关 联邦查询
```

## 前置条件

1. 已按 `deploy/README.md` 完成 `deploy/` 骨架部署，封装层 Operator 就绪。
2. `preflight.sh` 通过（`k8s.self_built=true`，存储驱动就绪）。
3. 封装层 CLI `dqctl` 已配置（对应控制台 v0.3「工作空间」页与封装层 v0.1 REST API）。
4. 已选 Profile（本包以 `xinchuang` 为例；其余环境仅存储驱动不同，SQL 与作业不变）。

## 运行方式

```bash
# 方式 A：一键全流程（推荐演示用）
bash run-demo.sh

# 方式 B：分步执行，便于讲解每一步
bash 00-bootstrap-workspace.sh
dqctl job submit --workspace demo-fin --project trade --type flink --file 01-cdc-iceberg.sql
dqctl job submit --workspace demo-fin --project trade --type spark --file 02-spark-dwd.sql
dqctl sql exec  --workspace demo-fin --file 03-doris-catalog.sql
dqctl sql query --workspace demo-fin --file 04-unified-query.sql
```

## 验收（对应 PoC V1~V7）

- [ ] `00` 执行后，封装层返回 workspaceId，底层生成 Namespace+Quota+deny-all（客户不感知）
- [ ] `01` 提交后，MySQL 变更 3~5s 内在 Iceberg `ods_user_order` 可见
- [ ] `02` 提交后，`dwd_user_order` / `dws_user_order_1d` 生成，与 ods 共享 warehouse 无冗余
- [ ] `03` 执行后，Doris `mv_dws_user_order_1d` 物化视图可毫秒级查询
- [ ] `04` 返回跨 Iceberg+Doris 的联邦结果（≤320ms）
- [ ] 全程无 Pod / kubeconfig / YAML 暴露给客户
- [ ] 四环境（信创/本地/公有云/私有云）各跑一遍，结果字节级一致

## 测试数据管理（非硬编码、可一键清理）

演示数据**不写在查询 SQL 里**，而是落在 MySQL 测试库 `fin.user_order`，由种子脚本注入、清理脚本删除：

```bash
# 演示前注入测试数据 (5 行, 仅演示用)
mysql -h<host> -u<user> -p fin < 05-seed-test-data.sql

# 演示后一键清理 (删 workspace + 清 MySQL 测试表)
bash cleanup.sh
```

- `05-seed-test-data.sql`：建 `fin` 库 + `user_order` 测试表 + 注入测试行（明确标注测试数据）。
- `cleanup.sh`：客户视角删工作空间（封装层级联回收 K8s/Iceberg/Doris），并显式清 MySQL 测试表。
- `01-cdc-iceberg.sql` 的 CDC 源即此测试表；查询 SQL 不硬编码任何数值，全部来自库。

## 四环境切换

```bash
export DQCTL_PROFILE=xinchuang     # 或 onprem / publiccloud / privatecloud
bash run-demo.sh
```

仅存储驱动与镜像变体随 Profile 变化，作业与 SQL 完全一致（见 PoC §10）。

## 笔记本验证（无信创机器）

若**没有信创机器**，可在笔记本（x86_64 / amd64）用 `local` Profile 跑通 PoC：

```bash
export DQCTL_PROFILE=local
bash ../scripts/local-up.sh      # 起 K3s/kind + MinIO + 应用 local Profile + 跑 PoC
```

- `local` Profile：amd64、单节点 K3s/kind、本地 MinIO 存储、标准加密（非国密）、最小资源。
- `xinchuang` 等四环境 Profile 仅用于客户交付，笔记本无信创硬件无法运行。
- 验证标准与四环境一致（V1~V7），仅底层存储驱动 / 镜像变体 / 加密算法不同。
