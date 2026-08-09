# 数据引擎大数据平台 全链路性能压测套件

> T046 交付物 · 覆盖 13 项非功能指标 + 10 项 SLA 验证

## 目录结构

```
tests/integration/perf/
├── __init__.py                      # 包标识
├── conftest.py                      # 性能测试专用 conftest（阈值/引擎/fixtures）
├── requirements.txt                 # 性能测试依赖（httpx/locust/psutil/numpy）
├── README.md                        # 本文件
├── test_performance_benchmark.py    # 性能基准压测（15 个用例，13 项指标）
├── test_sla_verification.py         # SLA 验证测试（10 个用例）
├── perf_report.py                   # 性能压测报告生成器（HTML + JSON）
├── tuning_params.yaml               # 调优参数集（JVM/连接池/缓存/线程池/K8s）
└── run_perf.sh                      # 一键压测脚本
```

## 13 项非功能指标

| 序号 | 类别 | 指标 | 目标值 | 用例 |
|------|------|------|--------|------|
| 1 | 并发性能 | 100 并发响应时间 | ≤ 500ms | `test_api_concurrent_100` |
| 2 | 并发性能 | 500 并发响应时间 | ≤ 1s | `test_api_concurrent_500` |
| 3 | 并发性能 | 1000 并发响应时间 | ≤ 2s | `test_api_concurrent_1000` |
| 4 | 延迟性能 | API P99 延迟 | ≤ 200ms | `test_api_p99_latency` |
| 5 | 延迟性能 | SQL 查询延迟 | ≤ 5s | `test_sql_query_latency` |
| 6 | 吞吐量 | API 吞吐量 | ≥ 1000 QPS | `test_api_throughput` |
| 7 | 吞吐量 | 数据摄入吞吐量 | ≥ 100MB/s | `test_data_ingest_throughput` |
| 8 | 资源利用率 | CPU 利用率 | ≤ 80% | `test_cpu_utilization` |
| 9 | 资源利用率 | 内存利用率 | ≤ 85% | `test_memory_utilization` |
| 10 | 稳定性 | 30 分钟稳定性 | 无异常 | `test_long_run_stability` |
| 11 | 稳定性 | 错误率 | ≤ 0.1% | `test_error_rate` |
| 12 | 扩展性 | 水平扩展 | 可验证 | `test_horizontal_scale` |
| 13 | 数据一致性 | 多租户一致性 | 100% | `test_data_consistency` |
| 14 | 冷启动 | 冷启动时间 | ≤ 30s | `test_cold_start_time` |
| 15 | 故障恢复 | 故障恢复时间 | ≤ 60s | `test_failover_recovery_time` |

## 10 项 SLA 验证

| 序号 | SLA 指标 | 目标值 | 用例 |
|------|----------|--------|------|
| 1 | API 可用性 | ≥ 99.9% | `test_sla_api_availability` |
| 2 | SQL 查询 P95 | ≤ 3s | `test_sla_sql_query_p95` |
| 3 | SQL 查询 P99 | ≤ 5s | `test_sla_sql_query_p99` |
| 4 | AI 推理延迟 | ≤ 2s | `test_sla_ai_inference_latency` |
| 5 | 微调吞吐量 | ≥ 100 samples/s | `test_sla_finetuning_throughput` |
| 6 | 跨集群查询延迟 | ≤ 10s | `test_sla_federated_query_latency` |
| 7 | 流处理延迟 | ≤ 1s | `test_sla_stream_processing_delay` |
| 8 | 100 租户并发 | 无异常 | `test_sla_concurrent_tenants` |
| 9 | 治理管道吞吐量 | ≥ 50 ops/s | `test_sla_data_governance_throughput` |
| 10 | 看板渲染时间 | ≤ 3s | `test_sla_dashboard_render_time` |

## 快速开始

### 1. 安装依赖

```bash
pip install -r tests/integration/perf/requirements.txt
```

### 2. 启动目标服务

```bash
# 方式一：Docker Compose 启动全部服务
cd tests/integration
docker-compose up -d

# 方式二：仅启动核心 4 组件
docker-compose up -d it-encaps-layer it-sql-gateway it-catalog it-rule-engine
```

### 3. 运行压测

```bash
# 一键运行（推荐）
bash tests/integration/perf/run_perf.sh

# 仅运行基准压测
python -m pytest tests/integration/perf/test_performance_benchmark.py -v

# 仅运行 SLA 验证
python -m pytest tests/integration/perf/test_sla_verification.py -v

# 运行全部性能测试并生成报告
python -m pytest tests/integration/perf/ -v --html=perf-report.html
```

### 4. 生成压测报告

```bash
# 生成 HTML + JSON 双格式报告
python tests/integration/perf/perf_report.py --output report.html

# 仅生成 JSON 报告
python tests/integration/perf/perf_report.py --format json --output report.json
```

## 设计要点

### 自研异步压测引擎

不依赖 locust 即可运行，基于 `asyncio + httpx`：

- **连接池复用**：`httpx.AsyncClient` 复用 TCP 连接，减少握手开销；
- **并发可控**：支持 100/500/1000 并发，连接池上限可配；
- **完整指标**：返回延迟分布（P50/P95/P99/Max）、QPS、错误率、状态码分布；
- **跨平台**：纯 Python 实现，无需安装系统级依赖。

### 自动跳过机制

所有压测用例在目标服务不可用时自动 `pytest.skip()`，避免误报：

- 服务可用性通过健康检查端点探测（5 秒超时）；
- `perf_services_ready` fixture 一次性探测所有服务，session 级缓存；
- 测试内部通过 `encaps_available` 等 fixture 判断是否跳过。

### 阈值集中管理

13 项非功能指标阈值与 10 项 SLA 阈值集中维护在 `conftest.py`：

- `PERF_THRESHOLDS`：非功能指标目标值；
- `SLA_THRESHOLDS`：SLA 验证目标值；
- `PERF_CONFIG`：压测运行参数（并发数/持续时间等）。

可通过环境变量覆盖：

```bash
export PERF_REQUESTS_PER_USER=50      # 每用户请求数
export PERF_STABILITY_DURATION=300    # 稳定性测试时长（秒）
export PERF_SAMPLE_INTERVAL=1.0       # 资源采样间隔
```

### 调优参数集

`tuning_params.yaml` 提供各组件推荐调优参数：

- **JVM 参数**：堆大小、GC 策略（G1/ZGC）；
- **连接池参数**：HikariCP/Tomcat 连接池大小与超时；
- **缓存参数**：Caffeine/Redis 容量与 TTL；
- **线程池参数**：核心/最大/队列容量；
- **K8s 资源配额**：requests/limits（CPU/内存）。

## CI 集成

在 CI 中运行性能压测的推荐配置：

```yaml
# .github/workflows/perf.yml
- name: 运行性能压测
  run: |
    pip install -r tests/integration/perf/requirements.txt
    docker-compose -f tests/integration/docker-compose.yml up -d
    python -m pytest tests/integration/perf/ -v --html=perf-report.html
    python tests/integration/perf/perf_report.py --output perf-report.html
  env:
    PERF_REQUESTS_PER_USER: "10"    # CI 环境降低压测强度
    PERF_STABILITY_DURATION: "10"   # CI 中缩短稳定性测试
```

## 验证步骤

```bash
# 1. Python 语法检查
python -m py_compile tests/integration/perf/*.py

# 2. pytest 测试收集
python -m pytest tests/integration/perf/ --noconftest --collect-only -q

# 3. 确认用例数量 ≥ 25（15 压测 + 10 SLA）
python -m pytest tests/integration/perf/ --collect-only -q | wc -l
```

## 依赖关系

- **前置**：T045 端到端集成测试已完成（`tests/integration/e2e/`）；
- **复用**：`tests/integration/docker/conftest.py` 的服务管理与 JWT 工具；
- **不修改**：父级 `tests/integration/docker/conftest.py` 与 `tests/integration/conftest.py`。