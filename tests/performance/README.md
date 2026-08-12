# 性能基准测试 (tests/performance)

> R3: 舒清大数据平台核心服务性能基准测试

## 文件清单

| 文件 | 说明 |
|------|------|
| `locustfile.py` | Locust 分布式压测脚本,覆盖三个 P0 核心服务全部主要端点 |
| `run_benchmark.py` | 自动压测+报告生成脚本(标准库实现,无外部依赖) |
| `requirements.txt` | Locust 依赖 |
| `benchmark_report.md` | 性能基准测试报告(自动生成) |

## 被测服务

| 服务 | 端口 | 核心端点 |
|------|------|---------|
| encaps-layer | 8080 | `/actuator/health`, `/api/v1/health` |
| sql-gateway | 8081 | `/api/v1/sql/execute`, `/api/v1/sql/parse`, `/api/v1/sql/validate` |
| rule-engine | 8083 | `/api/v1/rules/execute`, `/api/v1/rules`, `/api/v1/rules/types` |

## P95 延迟基准

| 场景 | P95 基准 |
|------|---------|
| RAG 检索 | ≤ 2000 ms |
| 数据入仓 | ≤ 5000 ms |
| 联邦查询 | ≤ 10000 ms |
| 物化视图 | ≤ 100 ms |

## 快速开始

### 方式一: 自动压测(推荐)

```bash
# 在 WSL2 中运行(可访问 K3s Pod CIDR)
python3 run_benchmark.py --requests 100

# 仅理论分析(服务不可达时)
python3 run_benchmark.py --mode theoretical
```

### 方式二: Locust 压测

```bash
pip install -r requirements.txt

# 通过 kubectl port-forward 建立本地隧道
kubectl port-forward -n shuqing svc/encaps-layer 18080:8080 &
kubectl port-forward -n shuqing svc/sql-gateway  18081:8081 &
kubectl port-forward -n shuqing svc/rule-engine  18083:8083 &

# 运行 Locust(headless 模式)
locust -f locustfile.py --headless -u 10 -r 2 -t 30s --only-summary

# 或启动 Web UI
locust -f locustfile.py  # 浏览器访问 http://localhost:8089
```

## 输出

`benchmark_report.md` 包含:
1. 服务可达性总览
2. 各端点 P50/P95/P99 延迟实测/理论值
3. P95 延迟基准对照(达标判定)
4. 结论与优化建议
5. 测试环境与端点清单附录