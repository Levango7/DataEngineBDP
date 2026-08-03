﻿# L4.3 数据开发 IDE（Theia 二开）· 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L4 数据开发与分析层
> 套餐：标准版起含（§11.5.1），基础版不含
> 对标：Eclipse Theia / VS Code Online / JupyterLab / DataWorks Studio
> 关联：L2.7 统一 SQL 网关（SQL 执行经网关）；L4.2 调度编排（IDE 提交作业）；L4.1 数据集成（IDE 内配置同步任务）；L4.4 BI 可视化（IDE 内预览建图表）；L2.2 Spark（Python 作业提交）；L0.11 封装层（启停/配额/隔离）；L3.5 资产目录（SQL 编辑器表名/字段补全）

## 1. 定位与价值

数据开发 IDE 是数据工程师的**统一开发入口**：以浏览器即开即用的在线 IDE 承载 SQL 脚本与 Python Notebook 的编写、试运行、版本管理与一键提交调度，免去本地环境搭建与上传下载。

选型决策——**基于 Eclipse Theia 二开，不从零自研**：
- Theia 是 VS Code 同源的开源 IDE 框架，已抽象编辑器/扩展/语言协议（LSP）/终端，从零自研等同重造 IDE，是公认的"巨坑"。
- 二开只做平台特化扩展（SQL 网关执行、Notebook 内核桥、调度提交、数据集成配置、BI 预览建图表、租户隔离），把 80% 通用能力交给上游，专注 20% 平台差异。

客户价值：
- "浏览器即用"——免装客户端，信创终端/瘦客户机可直接开发。
- "SQL+Python 一体"——同一窗口写 SQL 查 Iceberg、写 Python 跑 Spark，无需切换工具。
- "试运行→提交调度一条龙"——本地试跑通过即可一键入调度，避免脚本与作业脱节。
- "集成→开发→调度→可视化闭环"——IDE 内可直接配置数据集成任务、预览查询结果建图表，无需切换控制台。

平台价值：
- Theia on K8s 按需启停，闲置自动回收，开发态资源不常驻。
- SQL 执行统一收敛到 L2.7 网关，鉴权/审计/限流天然继承，IDE 不直连引擎。

## 2. 总体架构

```text
┌──────── 浏览器（用户开发态） ────────┐
│  Theia Frontend（Monaco 编辑器 + Notebook UI + Git 视图）  │
└──────────────────────┬──────────────────────────┘
                        ▼  WebSocket / REST
┌──────── Theia on K8s（每用户/每项目一个 Pod，按需启停） ────────┐
│  Theia Backend · 扩展宿主                                          │
│  ┌──────── 平台二开扩展 ────────┐                                  │
│  │ sql-ext  notebook-ext  git-ext  schedule-ext  fs-ext           │
│  │ integration-ext  bi-ext                                        │
│  └──────────────┬──────────────┘                                   │
│                 ▼                                                   │
│  LSP(SQL/Py) · 文件系统(挂载项目 PVC) · 终端(受控)                  │
└──────────────────────┬──────────────────────────┘
                        ▼  经 L0.11 封装层
        ┌──────────────┼──────────────┬──────────────┐
        ▼              ▼              ▼              ▼
   L2.7 SQL 网关    L2.2 Spark      L4.2 调度       对象存储/PVC
   (执行/校验)      (Python 作业)   (提交作业)      (脚本/Notebook)
        │
        ▼
   L4.1 数据集成    L4.4 BI 可视化   L3.5 资产目录
   (配置同步任务)   (预览建图表)     (表名/字段补全)
```

## 3. 核心扩展

| 扩展 | 职责 | 关键点 |
| --- | --- | --- |
| sql-ext | SQL 编辑器 + 语法校验 + 执行 | Monaco SQL 语法高亮；LSP 做语法/表名补全（表名候选项调 `GET /api/catalog/v1/assets?q=`（L3.5 资产目录）拉取）；执行经 L2.7 网关，结果分页回显 |
| notebook-ext | Python Notebook（Jupyter 兼容） | 内核桥接 K8s 上的 PySpark Kernel；单元格试跑；变量透视 |
| git-ext | 版本管理 | 项目脚本/Notebook Git 化藏提交、分支、Diff；后端 GitOps 仓 |
| schedule-ext | 一键提交调度 | 脚本→作业模板→提交 L4.2；可选调度参数（cron/依赖） |
| fs-ext | 文件管理 | 项目 PVC 挂载为工作区；目录树/上传/下载受租户隔离约束 |
| integration-ext | 数据集成任务配置与提交 | IDE 内可视化配置 Source/Sink/Transform（调 `POST /api/integration/v1/jobs`（L4.1）），无需切控制台；支持试运行与提交 L4.2 调度 |
| bi-ext | 查询结果转图表/仪表盘 | SQL 试运行结果一键建 ECharts 图表或推送至 Superset 数据集（调 `POST /api/bi/v1/charts`（L4.4）），IDE 内预览图表效果 |

扩展加载：平台二开扩展打包进 Theia 镜像随 Pod 启动加载；扩展升级走镜像版本滚动更新，不改用户脚本与 PVC。

## 4. 接口契约（经 L0.11 封装层 REST API）

```
POST   /api/ide/v1/sessions            { project } → { podName, wsUrl }   // 打开 IDE（按需起 Pod）
DELETE /api/ide/v1/sessions/{pod}                                        // 关闭/回收 IDE
GET    /api/ide/v1/files?project=&path= → [{path,type,size}]               // 文件列表
PUT    /api/ide/v1/files/{path}         { content }                        // 保存脚本/Notebook
POST   /api/ide/v1/sql/execute          { sql, catalog, db } → { jobId }   // 执行 SQL（转 L2.7）
POST   /api/ide/v1/notebook/run         { nb, cellId } → { result }        // 跑 Notebook 单元格
POST   /api/ide/v1/schedule/submit      { script, schedule } → { jobId }   // 一键提交 L4.2
POST   /api/ide/v1/integration/submit   { source, sink, transform, mode } → { jobId }  // 提交 L4.1 数据集成作业
POST   /api/ide/v1/chart/from-result    { resultRef, vizType, params } → { chartId }   // 查询结果转图表（L4.4）
GET    /api/ide/v1/catalog/suggest      { q, type } → [{id,name}]          // 表名/字段补全（转 L3.5）
```
> 所有接口经封装层鉴权与配额；IDE Pod 本身不直接对外暴露，经封装层反代 WebSocket。

## 5. 关键流程

```text
用户打开 IDE ──▶ 封装层按需起 Theia Pod（命中则复用）
                        │
                        ▼
        编辑 SQL / Python Notebook（Monaco + LSP 实时校验，表名补全调 L3.5）
                        │
                        ▼
              本地试运行（SQL→L2.7 网关 / Py→Spark Kernel）
                        │  失败 ◀── 回查结果/日志，改脚本重来
                        ▼  通过
        Git 提交脚本到项目仓 ──▶ 一键提交调度（schedule-ext → L4.2）
                        │
                        ▼
              调度作业生成，回 IDE 查看运行状态/结果
                        │
                        ▼  （可选）
        配置数据集成任务（integration-ext → L4.1）/ 查询结果建图表（bi-ext → L4.4）
```

## 6. 与统一 SQL 网关集成

- IDE 的"执行 SQL"按钮**不直连引擎**，统一经 L2.7 网关：鉴权（继承租户/项目上下文）、审计（谁在 IDE 跑了什么 SQL）、限流（防 IDE 大查询打爆交互引擎）。
- 语法校验走 IDE 内 LSP（轻量、离线）；执行与结果回显走网关（重量、在线）。
- 结果集大时分页拉取并提示导出，避免浏览器内存爆炸。

## 7. 与 L4.1 数据集成 / L4.4 BI 可视化集成

- **integration-ext**：IDE 内提供"数据集成"侧边栏，可视化配置 Source/Sink/Transform，调 `POST /api/ide/v1/integration/submit` 转发至 L4.1 `POST /api/integration/v1/jobs`；配置完成后可一键提交 L4.2 调度，实现"集成→开发→调度"闭环。
- **bi-ext**：SQL 试运行结果可一键"建图表"，调 `POST /api/ide/v1/chart/from-result` 转发至 L4.4 `POST /api/bi/v1/charts`，在 IDE 内预览 ECharts 图表效果；满意后推送至 Superset 数据集或仪表盘。
- 两扩展均经封装层鉴权，继承租户/项目上下文，不直连 L4.1/L4.4 原生端口。

## 8. 资源管理

- **按需启停**：用户打开 IDE 时封装层起 Theia Pod（含 CPU/Mem request/limit）；闲置超阈值自动休眠至 0 副本，再开冷启动秒级。
- **资源回收**：会话结束/项目删除回收 Pod 与临时 PVC；脚本与 Notebook 落项目持久 PVC 与 Git 仓，不丢。
- **多租户隔离**：每项目独立 Pod + ServiceAccount + NetworkPolicy；PVC 按 `workspace/project` 隔离；终端命令白名单过滤危险操作。

资源规格（默认，可由封装层按租户等级覆盖）：

| 规格 | CPU req/limit | Mem req/limit | 适用 |
| --- | --- | --- | --- |
| 轻量（仅 SQL） | 0.5 / 1 核 | 1 / 2 Gi | 轻查询开发 |
| 标准（SQL+Notebook） | 1 / 2 核 | 2 / 4 Gi | 默认开发态 |
| 重度（跑 PySpark Kernel） | 2 / 4 核 | 4 / 8 Gi | Notebook 重计算 |

> 资源配额说明：上表 quota 为单组件（单 IDE Pod）上限，平台总额度见 §11.5.2，由封装层 ResourceQuota 在租户级聚合约束（多用户并发时按租户配额汇总，非所有 Pod 同时满载）。

## 9. 多环境适配

| 环境 | IDE 镜像 | 内核/执行 | 说明 |
| --- | --- | --- | --- |
| 信创 | 国产基础镜像（鲲鹏/龙蜥） | SQL 网关 + 国产 Spark | 优先国产 Node/Python 运行时 |
| 本地数据中心 | 标准 Theia 镜像 | 网关 + 自建 Spark | 裸金属/虚机 K8s |
| 公有云 VM | 标准 Theia 镜像 | 网关 + 客户 Spark | **只用客户 VM K8s，不绑云托管 IDE 服务** |
| 私有云 | 标准/客户定制镜像 | 网关 + 私有 Spark | 客户私有云 K8s |

> 四环境 IDE 扩展与流程完全一致，差异只在基础镜像与执行后端，保持可迁移、不锁云。

## 10. 部署配置

部署参数落地于 `design/deploy/values/theia-values.yaml`，由封装层 Helm Operator 渲染。关键配置块：

```yaml
# theia-values.yaml
theia:
  image: sq-theia:0.1.0
  replicas: 0          # 按需启停，默认 0 副本
  autoIdle: true
  idleTimeout: "30m"
  persistence:
    projectPVC: { size: "10Gi", storageClass: "default" }
  extensions:
    platform:          # 平台二开扩展镜像
      - sql-ext:0.1.0
      - notebook-ext:0.1.0
      - git-ext:0.1.0
      - schedule-ext:0.1.0
      - fs-ext:0.1.0
      - integration-ext:0.1.0
      - bi-ext:0.1.0
  resources:
    light:    { requests: { cpu: "0.5", memory: "1Gi" }, limits: { cpu: "1", memory: "2Gi" } }
    standard: { requests: { cpu: "1",   memory: "2Gi" }, limits: { cpu: "2", memory: "4Gi" } }
    heavy:    { requests: { cpu: "2",   memory: "4Gi" }, limits: { cpu: "4", memory: "8Gi" } }
  tierProfiles:
    base:      { enabled: false }   # 基础版不含 L4.3
    standard:  { enabled: true,  resources: "standard" }
    flagship:  { enabled: true,  resources: "heavy" }
```

## 11. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 从零自研 IDE（已规避） | 周期失控、能力落后 | 选型已拍板 Theia 二开，不重造轮子 |
| Theia Pod 资源占用 | 多用户并发成本高 | 按需启停 + 闲置休眠 + request/limit 严控 |
| 浏览器大结果集卡顿 | 体验差 | 网关分页 + 导出对象存储，IDE 只回显前 N 行 |
| 终端越权/危险命令 | 安全风险 | 命令白名单 + ServiceAccount 最小权限 + NetworkPolicy |
| Notebook 内核抢占 | 多人抢 Spark Kernel | 每会话独立内核 + 超时回收 + 配额上限 |
| 信创浏览器/终端兼容 | UI 异常 | 优先国产浏览器适配，降级 Monaco 高级特性 |

## 12. 与 UI 的对应

控制台 v0.3「数据开发」页（在线 IDE 入口、SQL/Notebook 编辑、试运行、提交调度、查看作业结果）均基于本设计。本文件是其 IDE 侧契约依据。