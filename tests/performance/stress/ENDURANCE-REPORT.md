# G-P-02 8h 稳定性测试报告

## 1. 测试概述

### 1.1 测试目标

验证系统在长时间（8 小时）中等负载下的稳定性，检测以下风险：

- 内存泄漏（JVM 堆内存是否随时间单调上升）
- 连接池耗尽（错误率是否随时间上升）
- GC 暂停恶化（P99 长尾是否随时间扩大）
- 数据库连接泄漏
- 响应时间衰减（后段 vs 前段）

### 1.2 测试环境

| 项目 | 值 |
|------|------|
| 项目路径 | `F:\nexus\DataEngineBDP` |
| 后端服务 | `java -jar platform/encaps-layer/target/encaps-layer-0.1.0-SNAPSHOT.jar --server.port=18086` |
| 服务地址 | `http://localhost:18086` |
| 环境变量 | `K8S_MOCK_ENABLED=true` |
| 登录账号 | `admin / admin`（tenant-001） |
| 测试工具 | k6 v0.49.0（grafana/k6） |
| JDK | OpenJDK 17.0.20 (Corretto-17.0.20.8.1) |
| 操作系统 | Windows (win32) |

### 1.3 测试时长

| 场景 | 时长 | 并发 | 状态 |
|------|------|------|------|
| 缩短版验证 | 10 分钟 | 50 VUs | ✅ 已执行，全部通过 |
| 生产环境完整测试 | 8 小时 | 500 VUs | ⏳ 需生产环境执行 |

## 2. 判定标准（无衰减）

| 指标 | 阈值 | 说明 |
|------|------|------|
| 响应时间 P99 | < 200ms | 持续满足，不随时间上升 |
| 错误率 | < 0.1% | `endurance_error_rate` 与 `http_req_failed` |
| 健康检查成功率 | > 99% | `/actuator/health` 每 30 秒探测 |
| JVM 堆内存 | 后段均值 ≤ 前段 × 1.2 | 不应单调上升 |
| 业务成功率 | 100% | `biz_success_rate` |

## 3. 测试脚本

### 3.1 脚本路径

`tests/performance/stress/endurance-test.js`

### 3.2 脚本能力

- 恒定并发（`constant-vus` executor）混合负载，轮询 5 个 API（login / projects / governance/assets / standards / search/history）
- 定期健康检查：每 30 秒探测 `/actuator/health`，记录 `health_check_ok` 与 `health_check_latency`
- JVM 内存监控：每 60 秒采集 `jvm.memory.used`（heap）、`jvm.memory.committed`（heap）、`jvm.threads.live`
- 时间窗口指标：`window_latency` / `window_error_rate` 用于前后段对比
- teardown 输出耐久性分析提示与最终健康状态

### 3.3 k6 options 阈值

```javascript
thresholds: {
  endurance_error_rate: ['rate<0.001'],   // 错误率 < 0.1%
  endurance_latency: ['p(99)<200'],       // P99 < 200ms
  http_req_failed: ['rate<0.001'],        // HTTP 失败率 < 0.1%
  health_check_ok: ['rate>0.99'],         // 健康检查成功率 > 99%
}
```

## 4. 缩短版验证结果（10 分钟，已执行）

### 4.1 执行命令

```bash
k6 run \
  --env VUS=50 \
  --env DURATION=10m \
  --env USERNAME=admin \
  --env PASSWORD=admin \
  --env BASE_URL=http://localhost:18086 \
  --summary-export=tests/performance/stress/results/endurance-10m-summary.json \
  tests/performance/stress/endurance-test.js
```

### 4.2 总体结果

| 指标 | 结果 | 阈值 | 判定 |
|------|------|------|------|
| 运行时长 | 10m00s | 10m | ✅ |
| 完成迭代数 | 140,704 | - | - |
| 迭代速率 | 234.45/s | - | - |
| setup login | ✓ status 200 / ✓ has token | - | ✅ |
| `endurance_error_rate` | 0.00% | < 0.1% | ✅ 通过 |
| `endurance_latency` P99 | 16.03ms | < 200ms | ✅ 通过 |
| `http_req_failed` | 0.00% | < 0.1% | ✅ 通过 |
| `health_check_ok` | 100.00%（951 次） | > 99% | ✅ 通过 |
| `biz_success_rate` | 100.00% | 100% | ✅ 通过 |
| 最终健康检查 | UP | - | ✅ |

### 4.3 各 API 响应时间

| API | avg | P50 | P90 | P95 | P99 | P99.9 | max |
|-----|-----|-----|-----|-----|-----|-------|-----|
| auth/login | 5.57ms | 4.52ms | 8.54ms | 15.49ms | 19.11ms | 32.2ms | 59.86ms |
| projects | 2.97ms | 2.43ms | 4.08ms | 5.04ms | 14.47ms | 167.07ms | 182.87ms |
| governance/assets | 3.23ms | 2.9ms | 4.64ms | 5.58ms | 14.42ms | 21.01ms | 43.59ms |
| standards | 2.83ms | 2.59ms | 4.05ms | 4.85ms | 9.98ms | 18.09ms | 36.27ms |
| search/history | 2.15ms | 1.97ms | 3.13ms | 3.77ms | 5.76ms | 18.22ms | 29.94ms |

### 4.4 JVM 内存与线程监控

| 指标 | min | max | 趋势判定 |
|------|-----|-----|----------|
| `jvm_heap_used_bytes` | 150 MB | 641 MB | ✅ 在区间内波动，无单调上升 |
| `jvm_heap_committed_bytes` | 893 MB | 893 MB | ✅ 稳定 |
| `jvm_threads_live` | 30 | 71 | ✅ 稳定 |

### 4.5 健康检查延迟

| 指标 | avg | P50 | P90 | P99 | max |
|------|-----|-----|-----|-----|-----|
| `health_check_latency` | 100.15ms | 99ms | 172ms | 237ms | 238ms |

### 4.6 吞吐量

| 指标 | 值 |
|------|------|
| `http_reqs` | 143,009（238.29/s） |
| `data_received` | 394 MB（656 kB/s） |
| `data_sent` | 55 MB（91 kB/s） |

### 4.7 结论

缩短版 10 分钟稳定性测试**全部阈值通过**，脚本可用性已验证：

- 响应时间 P99 = 16.03ms，远低于 200ms 阈值，无衰减迹象
- 错误率 0.00%，业务成功率 100%
- JVM 堆内存 150-641 MB 波动，无内存泄漏
- 健康检查 100% 通过，服务持续 UP

## 5. 生产环境 8h 完整测试执行说明

### 5.1 前置条件

1. 后端服务已启动且健康：`http://localhost:18086/actuator/health` 返回 `UP`
2. k6 已安装（v0.49.0+）
3. 环境变量 `K8S_MOCK_ENABLED=true` 已设置

### 5.2 Windows 环境注意事项

Windows 系统环境变量 `USERNAME` 会覆盖 `common.js` 中 `__ENV.USERNAME || 'admin'` 的默认值，导致登录失败（用户名错误）。**执行时必须显式传入 `--env USERNAME=admin`**。

### 5.3 执行命令

```bash
# 设置环境变量
export K8S_MOCK_ENABLED=true   # Linux/Mac
set K8S_MOCK_ENABLED=true      # Windows CMD
$env:K8S_MOCK_ENABLED="true"   # Windows PowerShell

# 完整 8 小时稳定性测试
k6 run \
  --env VUS=500 \
  --env DURATION=8h \
  --env USERNAME=admin \
  --env PASSWORD=admin \
  --env BASE_URL=http://localhost:18086 \
  --out json=tests/performance/stress/results/endurance-8h-results.json \
  --summary-export=tests/performance/stress/results/endurance-8h-summary.json \
  tests/performance/stress/endurance-test.js
```

### 5.4 结果分析步骤

1. 检查 k6 summary 中所有 thresholds 是否通过（exit code = 0）
2. 对比 `window_latency` 测试前 10% 与后 10% 均值，若后段 > 前段 × 1.5，疑似内存泄漏
3. 观察 `endurance_error_rate` 是否随时间上升，若上升疑似连接池耗尽
4. 观察 P99 与 P99.9 差距是否随时间扩大，疑似 GC 暂停恶化
5. 观察 `jvm_heap_used_bytes` 时序数据，若单调上升且不回落，疑似内存泄漏
6. 用 `--out json=results.json` 输出时序数据，配合 Grafana 画延迟-时间曲线

## 6. 交付物清单

| 文件 | 说明 |
|------|------|
| `tests/performance/stress/endurance-test.js` | 稳定性测试脚本（已完善：8h 默认、健康检查、JVM 监控、P99<200ms / 错误率<0.1% 阈值） |
| `tests/performance/stress/results/endurance-10m-summary.json` | 10 分钟测试 k6 summary 导出 |
| `tests/performance/stress/ENDURANCE-REPORT.md` | 本报告 |

## 7. 需要确认

- 8h 完整测试需在生产环境或长时间空闲的测试环境执行，本机 10 分钟验证已通过
- 若生产环境 `USERNAME` 环境变量与登录账号不同，务必显式传入 `--env USERNAME=admin`
- 建议生产环境执行时使用 `--out json` 输出时序数据，便于事后绘制延迟-时间曲线与内存-时间曲线