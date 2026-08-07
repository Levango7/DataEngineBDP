# FinOps 成本采集与模型服务

## 1. 概述

本模块实现数擎大数据平台（ShuqingBigDataPlatform）的 FinOps 成本采集与模型服务，覆盖 CPU/内存/存储/GPU/网络五维度资源用量采集，建立成本模型支持三种计费方式（按量/包年/阶梯），支持多租户隔离与 GPU 多卡型号差异化定价。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| 五维度采集 | CPU/内存/存储/GPU/网络资源用量采集，粒度 ≤ 1min |
| 三种计费 | 按量（实时用量×单价）、包年（预留实例分摊）、阶梯（累计用量阶梯计价） |
| 租户隔离 | 按 tenant + namespace 标签隔离，tenant 间成本数据不可见 |
| GPU 差异化 | 支持 A100/V100/昇腾910/T4 等多卡型号差异化定价 |
| 动态定价 | 通过 API 或配置文件动态配置单价 |

### 1.2 技术栈

- Java 17 / Spring Boot 3.2.5
- Prometheus + kube-state-metrics + DCGM Exporter + 网络 Exporter
- JWT 认证 + Spring Security
- H2（开发）/ PostgreSQL（生产）
- Docker 多阶段构建

## 2. 目录结构

```
platform/finops/
├── cost-model/                          # Java/Spring Boot 成本模型服务
│   ├── pom.xml                          # Maven 配置
│   ├── Dockerfile                       # Docker 镜像构建（多阶段）
│   └── src/main/
│       ├── java/com/shuqing/bigdata/finops/
│       │   ├── CostModelApplication.java        # 主类
│       │   ├── model/                           # 成本模型
│       │   │   ├── BillingMethod.java           # 计费方式枚举（ON_DEMAND/RESERVED/TIERED）
│       │   │   ├── ResourceDimension.java       # 资源维度枚举（CPU/MEMORY/STORAGE/GPU/NETWORK）
│       │   │   ├── ResourceUsage.java           # 资源用量
│       │   │   ├── CostResult.java              # 成本计算结果
│       │   │   ├── PricingConfig.java           # 定价配置
│       │   │   ├── TieredPricingTier.java       # 阶梯定价档位
│       │   │   ├── CostCalculationRequest.java  # 成本计算请求
│       │   │   └── CostCalculationResponse.java # 成本计算响应
│       │   ├── controller/                      # REST API
│       │   │   ├── HealthController.java        # 健康检查
│       │   │   ├── CostController.java          # 成本 API（calculate/report）
│       │   │   ├── PricingController.java       # 定价配置 API
│       │   │   └── GlobalExceptionHandler.java  # 全局异常处理器（校验→400, 非法参数→400）
│       │   ├── service/                         # 成本计算服务
│       │   │   ├── BillingStrategy.java         # 计费策略接口
│       │   │   ├── OnDemandBillingStrategy.java # 按量计费
│       │   │   ├── ReservedBillingStrategy.java # 包年计费（预留分摊）
│       │   │   ├── TieredBillingStrategy.java   # 阶梯计费
│       │   │   ├── CostCalculationService.java  # 成本计算服务（策略选择+租户隔离）
│       │   │   └── PricingConfigService.java    # 定价配置服务（动态配置）
│       │   ├── collector/                       # 资源用量采集器
│       │   │   ├── PrometheusQueryClient.java   # Prometheus 查询客户端
│       │   │   └── ResourceUsageCollector.java  # 五维度采集器
│       │   └── security/                        # 安全配置
│       │       ├── TenantContext.java           # 租户上下文（ThreadLocal）
│       │       ├── JwtAuthFilter.java           # JWT 认证过滤器
│       │       └── SecurityConfig.java          # Spring Security 配置
│       └── resources/
│           └── application.yml                  # 服务配置
├── exporters/                           # 自定义 Exporter 配置
│   ├── dcgm-exporter-values.yaml        # GPU Exporter Helm values（DCGM，含昇腾 NPU）
│   ├── network-exporter.yaml            # 网络流量 Exporter ServiceMonitor
│   └── network-exporter-deployment.yaml # 网络流量 Exporter Deployment
├── prometheus-rules/                    # Prometheus 采集规则
│   └── cost-collection-rules.yaml       # 五维度采集规则（1min 粒度, tenant+namespace 标签）
└── README.md                            # 本文档
```

## 3. REST API

### 3.1 成本计算

#### POST /api/v1/cost/calculate

计算指定资源用量的成本。

**请求体：**

```json
{
  "usages": [
    {
      "tenant": "tenant-a",
      "namespace": "ns-1",
      "dimension": "CPU",
      "amount": 10.0,
      "start": "2026-08-08T00:00:00Z",
      "end": "2026-08-08T01:00:00Z"
    }
  ],
  "billingMethod": "ON_DEMAND",
  "pricingConfigName": "default"
}
```

**响应体：**

```json
{
  "results": [
    {
      "tenant": "tenant-a",
      "namespace": "ns-1",
      "billingMethod": "ON_DEMAND",
      "totalCost": 5.0000,
      "dimensionCosts": {"CPU": 5.0000},
      "dimensionUsages": {"CPU": 10.0},
      "note": "按量计费：实时用量 × 单价"
    }
  ],
  "grandTotal": 5.0000,
  "pricingConfigName": "default"
}
```

### 3.2 成本报告

#### GET /api/v1/cost/report

从 Prometheus 采集用量并计算成本。

**查询参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| namespace | string | 是 | Kubernetes namespace |
| billingMethod | enum | 否 | 计费方式（默认 ON_DEMAND） |
| pricingConfigName | string | 否 | 定价配置名（默认 default） |
| start | datetime | 是 | 窗口起始时间（ISO-8601） |
| end | datetime | 是 | 窗口结束时间（ISO-8601） |

### 3.3 定价配置

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/v1/pricing | GET | 列出所有定价配置名 |
| /api/v1/pricing/{name} | GET | 获取指定定价配置 |
| /api/v1/pricing | POST | 新建定价配置（动态配置单价） |
| /api/v1/pricing/{name} | PUT | 更新定价配置 |

## 4. 三种计费方式

### 4.1 按量计费（ON_DEMAND）

计算公式：`成本 = 实时用量 × 单价`

GPU 维度按型号差异化定价（从 `gpuPrices` 取型号单价）。

### 4.2 包年计费（RESERVED）

预留实例按月价分摊到小时，超出部分按按量单价计费（混合计费）：

- 预留覆盖用量 = min(实际用量, 预留数量)
- 预留成本 = 预留覆盖用量 × (月价 / 月小时数)
- 超出用量 = max(0, 实际用量 - 预留数量)
- 超出成本 = 超出用量 × 按量单价
- 总成本 = 预留成本 + 超出成本

### 4.3 阶梯计费（TIERED）

支持两种阶梯模式：

- **累计阶梯**（cumulative=true）：各档独立计价后求和。例如累计用量 150 落在档1[0,100)@1.0 与档2[100,∞)@2.0，则成本 = 100×1.0 + 50×2.0 = 200
- **统一阶梯**（cumulative=false）：按累计用量命中档位统一单价。例如累计用量 150 命中档2[100,∞)@2.0，则成本 = 150×2.0 = 300

## 5. 五维度采集

### 5.1 采集指标

| 维度 | 单位 | PromQL | Exporter |
|------|------|--------|----------|
| CPU | 核时 | `rate(container_cpu_usage_seconds_total[1m])` | cAdvisor |
| 内存 | GB·时 | `container_memory_working_set_bytes / 1Gi` | cAdvisor |
| 存储 | GB·时 | `kubelet_volume_stats_capacity_bytes / 1Gi` | kubelet |
| GPU | 卡时 | `DCGM_FI_DEV_GPU_UTIL / 100` | DCGM Exporter |
| 网络 | GB | `rate(container_network_receive_bytes_total[1m]) + rate(container_network_transmit_bytes_total[1m])` | cAdvisor + node-exporter |

### 5.2 采集粒度

采集粒度配置为 60s（≤ 1min 满足 FinOps 要求），在 `cost-collection-rules.yaml` 与 `application.yml` 中配置。

### 5.3 标签隔离

所有指标按 `tenant` 与 `namespace` 标签聚合，实现租户间数据隔离：

```promql
sum by (tenant, namespace) (rate(container_cpu_usage_seconds_total[1m]))
```

## 6. GPU 多卡型号差异化定价

默认 GPU 单价配置：

| 型号 | 单价（元/卡时） |
|------|-----------------|
| A100 | 12.0 |
| V100 | 6.0 |
| 昇腾910 | 8.0 |
| T4 | 3.0 |
| default | 8.0 |

昇腾 NPU 通过 `hccn_tool` 采集，映射为 DCGM 兼容指标（`dcgm-exporter-values.yaml` 中 `ascend` 配置）。

## 7. 部署与运行

### 7.1 本地 Docker 运行

```bash
# 在 tests/integration/ 目录下
docker-compose up -d --build cost-model prometheus

# 等待服务就绪
docker-compose up -d --wait cost-model

# 验证健康检查
curl http://localhost:18084/api/v1/health
```

### 7.2 Maven 本地构建

```bash
cd platform/finops/cost-model
mvn clean package -DskipTests
java -jar target/cost-model-0.1.0-SNAPSHOT.jar
```

### 7.3 配置环境变量

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| SERVER_PORT | 8084 | 服务端口 |
| JWT_SECRET | dev-secret-... | JWT 签名密钥 |
| PROMETHEUS_URL | http://localhost:19090 | Prometheus 地址 |
| DB_URL | jdbc:h2:file:./data/cost-model-db | 数据库 URL |
| FINOPS_DEFAULT_BILLING | ON_DEMAND | 默认计费方式 |
| COLLECTOR_INTERVAL | 60 | 采集粒度（秒） |

## 8. 集成测试

### 8.1 测试文件

`tests/integration/docker/test_finops.py`

### 8.2 测试覆盖

| 测试类别 | 测试函数 | 说明 |
|----------|----------|------|
| 健康检查 | test_health_check | 验证 /api/v1/health 返回 UP |
| 认证机制 | test_unauthorized_without_token | 无 token 返回 401 |
| 五维度采集 | test_five_dimensions_on_demand | 五维度用量与成本均存在 |
| 五维度采集 | test_five_dimensions_amounts_correct | 用量数值精确无丢失 |
| 按量计费 | test_on_demand_billing | 用量×单价精确计算 |
| 包年计费 | test_reserved_billing | 预留分摊计算 |
| 包年计费 | test_reserved_billing_with_excess | 超出部分按按量计费 |
| 阶梯计费 | test_tiered_billing_cumulative | 累计阶梯各档求和 |
| 阶梯计费 | test_tiered_billing_high_usage | 跨三档计价 |
| 阶梯计费 | test_tiered_billing_no_config_fallback | 无配置回退按量 |
| 租户隔离 | test_tenant_isolation | 租户 A 看不到租户 B 数据 |
| 租户隔离 | test_tenant_b_isolation | 租户隔离对称性 |
| 租户隔离 | test_tenant_isolation_namespace_grouping | 同租户多 namespace 分组 |
| GPU 差异化 | test_gpu_differentiated_pricing | A100/V100/昇腾910 不同单价 |
| GPU 差异化 | test_gpu_model_t4_pricing | T4 型号定价 |
| 动态定价 | test_list_pricing_configs | 列出配置 |
| 动态定价 | test_get_pricing_config | 获取配置 |
| 动态定价 | test_create_and_use_custom_pricing | 创建并使用自定义配置 |
| 动态定价 | test_update_pricing_config | 更新配置后使用新单价 |
| 成本报告 | test_cost_report_endpoint | /api/v1/cost/report 端点 |
| 输入校验 | test_empty_usages_rejected | 空用量返回 400 |
| 输入校验 | test_missing_billing_method_rejected | 缺计费方式返回 400 |
| 输入校验 | test_nonexistent_pricing_config_rejected | 不存在配置返回错误 |

### 8.3 运行测试

```bash
cd tests/integration
pytest docker/test_finops.py -v
```

## 9. 验收标准对照

| 验收标准 | 实现位置 | 状态 |
|----------|----------|------|
| 成本模型服务(Java/Spring Boot)骨架完成 | `cost-model/` | ✅ |
| 三种计费方式实现 | `service/*BillingStrategy.java` | ✅ |
| 五维度采集器配置完成 | `collector/ResourceUsageCollector.java` + `prometheus-rules/` | ✅ |
| 采集粒度≤1min配置 | `application.yml` + `cost-collection-rules.yaml` (60s) | ✅ |
| tenant+namespace标签隔离 | `security/` + `cost-collection-rules.yaml` | ✅ |
| GPU多卡型号差异化定价 | `PricingConfig.gpuPrices` + `dcgm-exporter-values.yaml` | ✅ |
| pytest测试套件编写完成 | `tests/integration/docker/test_finops.py` | ✅ |
| Dockerfile可构建 | `cost-model/Dockerfile`（多阶段构建） | ✅ |