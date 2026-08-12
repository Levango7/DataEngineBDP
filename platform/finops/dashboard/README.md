# FinOps 看板与优化建议服务

## 1. 概述

本模块实现数据引擎大数据平台（DataEngineBDP）的 FinOps 看板与优化建议服务，基于 T028 FinOps 成本采集（cost-model）的成本数据，提供 FinOps 看板（Top10/趋势/明细/闲置清单）、优化建议引擎（5 类闲置模式识别）、账单导出（CSV/Excel）、分账到子工作空间四大能力。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| Top10 成本资源 | 按总成本降序取前 N 个资源（默认 N=10） |
| 成本趋势 | 按小时/天/月粒度的成本时间序列 |
| 成本明细 | 按资源粒度的成本明细列表 |
| 闲置清单 | 5 类闲置模式识别结果（低 CPU/低内存/未挂载存储/空闲 GPU/低流量） |
| 优化建议引擎 | 识别 5 类闲置模式并生成可操作的优化建议 |
| 账单导出 | CSV/Excel 格式，含明细（按资源）与汇总（按 tenant/namespace/工作空间） |
| 分账到子工作空间 | 按 namespace 或工作空间标签分账，分账比例可配置 |

### 1.2 技术栈

- Java 17 / Spring Boot 3.2.5
- Prometheus（查询资源利用率指标）
- Apache POI 5.2.5（Excel 导出）
- OpenCSV 5.9（CSV 导出）
- JWT 认证 + Spring Security
- H2（开发）/ PostgreSQL（生产）
- Docker 多阶段构建

## 2. 目录结构

```
platform/finops/dashboard/
├── pom.xml                          # Maven 配置
├── Dockerfile                       # Docker 镜像构建（多阶段）
├── README.md                        # 本文档
└── src/main/
    ├── java/com/shuqing/bigdata/finops/dashboard/
    │   ├── FinOpsDashboardApplication.java       # 主类
    │   ├── model/                                # 数据模型
    │   │   ├── IdlePattern.java                  # 闲置模式枚举（5 类）
    │   │   ├── ResourceCostDetail.java           # 资源成本明细
    │   │   ├── TopCostResource.java              # Top10 成本资源
    │   │   ├── CostTrendPoint.java               # 成本趋势数据点
    │   │   ├── IdleResource.java                 # 闲置资源清单项
    │   │   ├── OptimizationSuggestion.java       # 优化建议
    │   │   ├── BillSummary.java                  # 账单汇总项
    │   │   ├── AllocationConfig.java             # 分账配置
    │   │   ├── AllocationItem.java               # 分账结果项
    │   │   └── DashboardResponse.java            # 看板统一响应
    │   ├── controller/                           # REST API
    │   │   ├── HealthController.java             # 健康检查
    │   │   ├── DashboardController.java          # 看板 API（top10/trend/details）
    │   │   ├── SuggestionController.java         # 优化建议 API（idle/list）
    │   │   ├── BillExportController.java         # 账单导出 API（csv/excel）
    │   │   ├── AllocationController.java         # 分账 API（configs/execute）
    │   │   └── GlobalExceptionHandler.java       # 全局异常处理器
    │   ├── service/                              # 业务服务
    │   │   ├── CostDataService.java              # 成本数据服务（Top10/趋势/明细）
    │   │   ├── OptimizationEngine.java           # 优化建议引擎（5 类闲置模式识别）
    │   │   ├── BillSummaryService.java           # 账单汇总服务
    │   │   └── AllocationService.java            # 分账服务
    │   ├── exporter/                             # 账单导出器
    │   │   ├── CsvBillExporter.java              # CSV 导出（明细+汇总）
    │   │   └── ExcelBillExporter.java            # Excel 导出（明细+汇总+full）
    │   ├── collector/                            # Prometheus 采集
    │   │   └── PrometheusQueryClient.java        # Prometheus 查询客户端
    │   └── security/                             # 安全配置
    │       ├── TenantContext.java                # 租户上下文（ThreadLocal）
    │       ├── JwtAuthFilter.java                # JWT 认证过滤器
    │       └── SecurityConfig.java               # Spring Security 配置
    └── resources/
        └── application.yml                       # 服务配置
```

## 3. REST API

### 3.1 健康检查

#### GET /api/v1/health

无需认证。

```json
{"status":"UP","service":"finops-dashboard","version":"0.1.0","timestamp":"..."}
```

### 3.2 FinOps 看板

#### GET /api/v1/dashboard/top10

Top10 成本资源。

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| namespace | string | 否 | Kubernetes namespace |
| start | datetime | 是 | 窗口起始时间（ISO-8601） |
| end | datetime | 是 | 窗口结束时间（ISO-8601） |

#### GET /api/v1/dashboard/trend

成本趋势。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| namespace | string | 否 | namespace |
| granularity | enum | 否 | 粒度（HOUR/DAY/MONTH，默认 HOUR） |
| start | datetime | 是 | 窗口起始时间 |
| end | datetime | 是 | 窗口结束时间 |

#### GET /api/v1/dashboard/details

成本明细。

### 3.3 优化建议

#### GET /api/v1/suggestions/idle

闲置资源清单（5 类闲置模式识别结果）。

#### GET /api/v1/suggestions/list

优化建议列表（按闲置模式聚合）。

### 3.4 账单导出

#### GET /api/v1/bill/export/csv

CSV 账单导出。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | enum | 否 | details/summary/full（默认 details） |
| groupBy | enum | 否 | TENANT/NAMESPACE/WORKSPACE（默认 TENANT） |
| namespace | string | 否 | namespace |
| start | datetime | 是 | 窗口起始时间 |
| end | datetime | 是 | 窗口结束时间 |

#### GET /api/v1/bill/export/excel

Excel 账单导出（参数同 CSV）。

### 3.5 分账到子工作空间

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/v1/allocation/configs | GET | 列出所有分账配置 |
| /api/v1/allocation/configs/{id} | GET | 获取指定分账配置 |
| /api/v1/allocation/configs | POST | 新建/更新分账配置 |
| /api/v1/allocation/configs/{id} | DELETE | 删除分账配置 |
| /api/v1/allocation/execute | GET | 执行分账 |

## 4. 5 类闲置模式识别

| 闲置模式 | 识别条件 | 优化动作 | 默认阈值 |
|----------|----------|----------|----------|
| 低利用率 CPU | CPU 平均利用率 < 阈值 | 缩容到 30% 或释放 | 10% |
| 低利用率内存 | 内存平均利用率 < 阈值 | 缩容内存 limit 至 50% | 20% |
| 未挂载存储 | PVC 未被任何 Pod 引用 | 删除 PVC 释放存储 | - |
| 空闲 GPU | GPU 平均利用率 < 阈值 | 释放或共享给其他任务 | 5% |
| 低流量负载 | 网络流量 < 阈值 | 合并部署或缩容副本 | 1 MB/s |

## 5. 账单导出格式

### 5.1 明细导出（按资源）

每行一个资源，字段：资源ID、资源类型、租户、namespace、工作空间、CPU成本、内存成本、存储成本、GPU成本、网络成本、总成本、GPU型号、窗口起始、窗口结束。

### 5.2 汇总导出（按 tenant/namespace/工作空间）

每行一个聚合键，字段：聚合维度、聚合键、总成本、CPU成本、内存成本、存储成本、GPU成本、网络成本、资源数量、明细条数。

### 5.3 Excel full 模式

双 Sheet：成本明细 + 成本汇总。

## 6. 分账到子工作空间

### 6.1 分账配置

```json
{
  "id": "ws-team1-split",
  "parentWorkspace": "ns-team1",
  "dimension": "namespace",
  "ratios": {
    "sub-ws-analytics": 0.6,
    "sub-ws-training": 0.3,
    "sub-ws-inference": 0.1
  },
  "enabled": true,
  "remark": "team1 工作空间按业务线分账"
}
```

### 6.2 分账比例约束

- 比例值范围 [0, 1]
- 所有比例合计必须 = 1.0
- 校验失败返回 400

## 7. 部署与运行

### 7.1 本地 Docker 运行

```bash
cd tests/integration
docker-compose up -d --build finops-dashboard prometheus

# 验证健康检查
curl http://localhost:18085/api/v1/health
```

### 7.2 Maven 本地构建

```bash
cd platform/finops/dashboard
mvn clean package -DskipTests
java -jar target/finops-dashboard-0.1.0-SNAPSHOT.jar
```

### 7.3 配置环境变量

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| SERVER_PORT | 8085 | 服务端口 |
| JWT_SECRET | dev-secret-... | JWT 签名密钥 |
| PROMETHEUS_URL | http://localhost:19090 | Prometheus 地址 |
| COST_MODEL_URL | http://localhost:18084 | cost-model 服务地址 |
| FINOPS_TOP_N | 10 | Top N 资源数 |
| IDLE_CPU_THRESHOLD | 10.0 | 低 CPU 利用率阈值（%） |
| IDLE_MEMORY_THRESHOLD | 20.0 | 低内存利用率阈值（%） |
| IDLE_GPU_THRESHOLD | 5.0 | 空闲 GPU 利用率阈值（%） |
| IDLE_NETWORK_THRESHOLD | 1.0 | 低网络流量阈值（MB/s） |
| IDLE_SUSTAINED_HOURS | 24 | 闲置持续时长（小时） |
| ALLOCATION_DIMENSION | namespace | 默认分账维度 |
| ALLOCATION_RATIOS | default=1.0 | 默认分账比例 |

## 8. 集成测试

### 8.1 测试文件

`tests/integration/docker/test_finops_dashboard.py`

### 8.2 测试覆盖

| 测试类别 | 测试函数数 | 说明 |
|----------|------------|------|
| 健康检查 | 1 | 验证 /api/v1/health 返回 UP |
| 认证机制 | 1 | 无 token 返回 401 |
| Top10 看板 | 2 | 数据正确 + 降序排列 |
| 趋势看板 | 2 | 数据正确 + 粒度正确 |
| 明细看板 | 1 | 数据正确 |
| 闲置清单 | 2 | 5 类模式识别 + 闲置资源数量 |
| 优化建议 | 2 | 5 类建议生成 + 节约成本 |
| CSV 导出 | 2 | 明细 + 汇总 |
| Excel 导出 | 2 | 明细 + 汇总 |
| 分账配置 | 2 | 创建 + 比例校验 |
| 分账执行 | 1 | 分账结果正确 |

### 8.3 运行测试

```bash
cd tests/integration
pytest docker/test_finops_dashboard.py -v
```

## 9. 验收标准对照

| 验收标准 | 实现位置 | 状态 |
|----------|----------|------|
| Top10 成本资源 API | `DashboardController.top10` + `CostDataService.getTopCostResources` | ✅ |
| 成本趋势 API | `DashboardController.trend` + `CostDataService.getCostTrend` | ✅ |
| 成本明细 API | `DashboardController.details` + `CostDataService.getCostDetails` | ✅ |
| 闲置清单 API | `SuggestionController.idle` + `OptimizationEngine.identifyIdleResources` | ✅ |
| 5 类闲置模式识别 | `OptimizationEngine` (LOW_CPU/MEMORY/UNMOUNTED_STORAGE/IDLE_GPU/LOW_NETWORK) | ✅ |
| 优化建议生成 | `OptimizationEngine.generateSuggestions` | ✅ |
| CSV 明细导出 | `CsvBillExporter.exportDetails` | ✅ |
| CSV 汇总导出 | `CsvBillExporter.exportSummary` | ✅ |
| Excel 明细导出 | `ExcelBillExporter.exportDetails` | ✅ |
| Excel 汇总导出 | `ExcelBillExporter.exportSummary` | ✅ |
| Excel full 导出（明细+汇总） | `ExcelBillExporter.exportFull` | ✅ |
| 分账到子工作空间 | `AllocationService.allocate` | ✅ |
| 分账比例可配置 | `AllocationController.saveConfig` + `AllocationService.saveConfig` | ✅ |
| 分账比例合计=1.0 校验 | `AllocationService.validateRatios` | ✅ |
| pytest 测试套件 | `tests/integration/docker/test_finops_dashboard.py` | ✅ |
| Dockerfile 可构建 | `Dockerfile`（多阶段构建） | ✅ |