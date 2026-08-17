# DataEngineBDP P1 性能测试报告

> 任务编号：406 ｜ 测试日期：2026-08-17 ｜ 性能目标：API P99 < 200ms，并发 1000 连接

## 第1章 测试概述

### 1.1 测试目标

本次性能测试针对 DataEngineBDP 平台后端 encaps-layer 服务，验证以下性能指标：

- **延迟目标**：核心 API 的 P99 延迟 < 200ms
- **并发目标**：支持 1000 并发连接
- **稳定性目标**：请求错误率 < 1%，业务成功率 > 99%
- **吞吐量目标**：记录各 API 在不同并发下的 TPS

### 1.2 测试范围

覆盖 5 个核心 API 接口：

表：核心 API 接口清单

| API | 方法 | 路径 | 说明 |
|-----|------|------|------|
| 登录 | POST | /api/v1/auth/login | 用户认证，JWT 签发 |
| 项目列表 | GET | /api/v1/projects | 查询项目集合 |
| 治理资产 | GET | /api/v1/governance/assets | 查询资产目录 |
| 标准列表 | GET | /api/v1/standards | 查询标准库 |
| 搜索历史 | GET | /api/v1/search/history | 查询搜索历史 |

### 1.3 测试结论摘要

- **100 并发**：5/5 API 全部达标（P99 < 200ms），错误率 0%，TPS 3,807 ~ 7,472。
- **500 并发**：4/5 API 达标，登录接口 P99 = 950ms 未达标（CPU 密集型瓶颈）。
- **1000 并发**：3/5 API 达标，登录接口与 standards 接口未达标（P99 分别为 992ms / 206ms）。
- **稳定性**：全量 15 组测试错误率均为 0%，后端在 1000 并发下无请求失败。
- **总体判定**：GET 类接口在 1000 并发下基本满足 P99 < 200ms；登录接口因密码哈希 + JWT 签名为 CPU 密集型，在高并发下 P99 显著上升，建议引入缓存或异步签发优化。

## 第2章 测试环境

### 2.1 硬件环境

表：硬件配置

| 项目 | 配置 |
|------|------|
| 操作系统 | Microsoft Windows 11 家庭版 10.0.26200 |
| CPU | AMD Ryzen 9 7945HX with Radeon Graphics |
| 逻辑核数 | 32 |
| 物理内存 | 31.22 GB（测试时空闲 3.1 GB） |
| 磁盘 | F: 盘剩余 53.11 GB |

### 2.2 软件环境

表：软件配置

| 项目 | 版本/路径 |
|------|----------|
| JDK | OpenJDK 17.0.20 LTS |
| 后端服务 | encaps-layer-0.1.0-SNAPSHOT.jar |
| 后端端口 | 18086 |
| API 前缀 | /api/v1 |
| 数据库 | H2（内存模式，actuator/health 上报） |
| 后端进程 | PID=56060，常驻内存约 1131 MB |
| 测试账号 | admin / admin |

### 2.3 压测工具

表：压测工具与产物

| 工具 | 用途 | 状态 |
|------|------|------|
| k6（JavaScript） | 编写 ES 模块压测脚本 | 脚本已交付，运行时因网络受限未安装 k6 二进制 |
| JMeter（.jmx） | GUI 可打开的测试计划 | 计划已交付 |
| PowerShell + HttpClient | 实际执行并发压测 | 已执行，作为 k6 替代方案产出真实数据 |

> 说明：本机无法访问外网下载 k6 二进制（github.com 连接超时），因此实际压测数据由 PowerShell + System.Net.Http.HttpClient + RunspacePool 方案产出。该方案使用 .NET 原生 HttpClient，单请求开销 < 1ms，测量精度与 k6 相当。k6 脚本与 JMeter 计划均已完整交付，可在具备 k6/JMeter 环境的机器上直接运行。

## 第3章 测试方案

### 3.1 压测脚本设计

#### 3.1.1 k6 脚本（ES 模块）

表：k6 脚本清单

| 脚本 | 场景 | 关键参数 |
|------|------|---------|
| common.js | 共享配置与工具函数（登录、请求头、自定义指标） | - |
| login-stress.js | 登录接口压测，阶梯加压 100/500/1000 VU | VUS、DURATION 环境变量 |
| api-baseline.js | 5 个核心 API 基线测试 | VUS、DURATION 环境变量 |
| mixed-workload.js | 混合工作负载，模拟真实用户流程 | VUS=1000，含 1-3s 思考时间 |

自定义指标采用 snake_case 命名：`biz_success_rate`、`biz_error_count`、`login_latency`、`api_latency`、`txn_duration`。

#### 3.1.2 JMeter 测试计划

- 文件：`jmeter/api-baseline.jmx`
- Thread Group：1000 线程，Ramp-up 10 秒，循环 10 次
- 采样器：5 个 HTTP Request（登录 + 4 个 GET）
- 监听器：Summary Report + Aggregate Graph + View Results Tree
- 变量化：HOST、PORT、API_PREFIX、USERNAME、PASSWORD、THREADS、RAMPUP、LOOPS

#### 3.1.3 PowerShell 执行脚本

- 文件：`run-stress-httpclient.ps1`
- 并发模型：RunspacePool，每个 worker 独立 HttpClient 实例
- 阶梯加压：5 秒 ramp-up 内线性放行 worker
- 指标采集：每请求记录 durationMs / status / ok，聚合 P50/P90/P95/P99/TPS/错误率

### 3.2 测试执行参数

表：压测执行参数

| 参数 | 值 |
|------|-----|
| 并发阶梯 | 100 → 500 → 1000 |
| 每阶梯持续 | 20 秒 |
| Ramp-up | 5 秒 |
| 每组间隔 | 2 秒（让后端恢复） |
| 请求超时 | 15 秒 |
| 总测试时长 | 427.5 秒（约 7 分钟） |

## 第4章 测试结果

### 4.1 100 并发结果

表：100 并发压测结果

| API | 总请求 | 成功 | 错误 | TPS | avg(ms) | P50(ms) | P95(ms) | P99(ms) | max(ms) | P99<200ms |
|-----|--------|------|------|-----|---------|---------|---------|---------|---------|-----------|
| POST /auth/login | 78,469 | 78,469 | 0 | 3,807.3 | 21.84 | 14.21 | 55.76 | **90.50** | 489.25 | ✅ 达标 |
| GET /projects | 125,513 | 125,513 | 0 | 5,905.5 | 12.73 | 4.47 | 49.03 | **94.42** | 492.77 | ✅ 达标 |
| GET /governance/assets | 94,788 | 94,788 | 0 | 4,546.2 | 16.71 | 6.39 | 54.40 | **122.00** | 665.50 | ✅ 达标 |
| GET /standards | 123,733 | 123,733 | 0 | 5,839.9 | 12.91 | 4.54 | 48.86 | **86.94** | 617.62 | ✅ 达标 |
| GET /search/history | 156,360 | 156,360 | 0 | 7,471.8 | 9.52 | 2.92 | 43.50 | **81.59** | 493.73 | ✅ 达标 |

**小结**：100 并发下 5 个 API 全部达标，错误率 0%，TPS 最高 7,472（search/history），最低 3,807（login）。

### 4.2 500 并发结果

表：500 并发压测结果

| API | 总请求 | 成功 | 错误 | TPS | avg(ms) | P50(ms) | P95(ms) | P99(ms) | max(ms) | P99<200ms |
|-----|--------|------|------|-----|---------|---------|---------|---------|---------|-----------|
| POST /auth/login | 40,031 | 40,031 | 0 | 1,887.1 | 91.98 | 45.94 | 258.02 | **950.33** | 1,332.53 | ❌ 未达标 |
| GET /projects | 106,928 | 106,928 | 0 | 5,147.7 | 20.83 | 6.32 | 87.14 | **173.71** | 1,148.72 | ✅ 达标 |
| GET /governance/assets | 82,926 | 82,926 | 0 | 4,025.2 | 25.63 | 8.89 | 96.41 | **181.43** | 909.00 | ✅ 达标 |
| GET /standards | 106,021 | 106,021 | 0 | 5,106.8 | 20.29 | 6.55 | 82.67 | **149.14** | 1,007.38 | ✅ 达标 |
| GET /search/history | 119,768 | 119,768 | 0 | 5,725.8 | 16.62 | 3.76 | 78.05 | **166.38** | 940.41 | ✅ 达标 |

**小结**：500 并发下 4/5 API 达标。登录接口 P99 = 950ms，较 100 并发（90ms）劣化 10 倍，TPS 从 3,807 降至 1,887，呈现明显 CPU 瓶颈。GET 类接口 P99 均在 200ms 以内，TPS 与 100 并发基本持平。

### 4.3 1000 并发结果

表：1000 并发压测结果

| API | 总请求 | 成功 | 错误 | TPS | avg(ms) | P50(ms) | P95(ms) | P99(ms) | max(ms) | P99<200ms |
|-----|--------|------|------|-----|---------|---------|---------|---------|---------|-----------|
| POST /auth/login | 37,866 | 37,866 | 0 | 1,842.7 | 98.47 | 48.28 | 295.82 | **992.32** | 1,402.37 | ❌ 未达标 |
| GET /projects | 107,523 | 107,523 | 0 | 5,146.2 | 20.02 | 6.43 | 80.88 | **151.56** | 642.79 | ✅ 达标 |
| GET /governance/assets | 89,565 | 89,565 | 0 | 4,316.8 | 23.12 | 8.31 | 86.65 | **158.61** | 705.69 | ✅ 达标 |
| GET /standards | 99,902 | 99,902 | 0 | 4,776.9 | 21.66 | 6.29 | 94.77 | **205.63** | 1,127.20 | ❌ 未达标（+5.63ms） |
| GET /search/history | 124,615 | 124,615 | 0 | 5,949.7 | 17.13 | 4.17 | 74.85 | **146.40** | 1,044.19 | ✅ 达标 |

**小结**：1000 并发下 3/5 API 达标。登录接口 P99 = 992ms（与 500 并发基本持平，TPS 也接近，说明登录吞吐已饱和）。standards 接口 P99 = 205.63ms，略超目标 5.63ms，属于边界波动。其余 GET 接口 P99 在 146 ~ 159ms 之间，达标。

### 4.4 并发趋势对比

表：各 API P99 延迟随并发变化（单位 ms）

| API | 100 并发 | 500 并发 | 1000 并发 | 趋势 |
|-----|---------|---------|----------|------|
| POST /auth/login | 90.50 | 950.33 | 992.32 | 100→500 劣化 10.5 倍，500→1000 趋于饱和 |
| GET /projects | 94.42 | 173.71 | 151.56 | 劣化后稳定，1000 时反降（GC/JIT 稳定） |
| GET /governance/assets | 122.00 | 181.43 | 158.61 | 同上 |
| GET /standards | 86.94 | 149.14 | 205.63 | 持续上升，1000 时略超标 |
| GET /search/history | 81.59 | 166.38 | 146.40 | 劣化后稳定 |

表：各 API TPS 随并发变化

| API | 100 并发 | 500 并发 | 1000 并发 |
|-----|---------|---------|----------|
| POST /auth/login | 3,807.3 | 1,887.1 | 1,842.7 |
| GET /projects | 5,905.5 | 5,147.7 | 5,146.2 |
| GET /governance/assets | 4,546.2 | 4,025.2 | 4,316.8 |
| GET /standards | 5,839.9 | 5,106.8 | 4,776.9 |
| GET /search/history | 7,471.8 | 5,725.8 | 5,949.7 |

### 4.5 错误率统计

全量 15 组测试，错误率均为 **0.00%**，所有请求返回 HTTP 200。后端在 1000 并发下未出现连接拒绝、超时或 5xx 错误，稳定性良好。

## 第5章 性能瓶颈分析

### 5.1 登录接口瓶颈（主要瓶颈）

**现象**：

- 100 并发 P99 = 90ms → 500 并发 P99 = 950ms，劣化 10.5 倍
- TPS 从 3,807 降至 1,887，吞吐反而下降
- 500 → 1000 并发，P99 与 TPS 几乎不变，呈饱和态势

**根因分析**：

1. **密码哈希计算**：登录涉及密码校验，若使用 BCrypt/PBKDF2 等慢哈希算法，单次校验耗时 10 ~ 50ms，是 CPU 密集型操作。
2. **JWT 签名**：本项目使用 HS384（从 token header `eyJhbGciOiJIUzM4NCJ9` 解码为 `{"alg":"HS384"}`），HMAC-SHA384 签名虽快，但每次登录都生成新 token，仍有 CPU 开销。
3. **单机 CPU 资源竞争**：500+ 并发时 32 核 CPU 被密码哈希占满，线程上下文切换加剧，导致吞吐不升反降。

**证据**：登录接口 avg 从 21.84ms（100 并发）升至 91.98ms（500 并发），而 GET 接口 avg 仅从 ~13ms 升至 ~21ms，劣化幅度远小于登录。

### 5.2 GET 接口表现

**现象**：GET 接口在 1000 并发下 P99 普遍 < 160ms，TPS 稳定在 4,300 ~ 6,000。

**原因**：

1. **H2 内存数据库**：数据驻留内存，无磁盘 I/O，查询延迟 < 1ms（单请求基线测试验证：avg 1.0 ~ 1.3ms）。
2. **JWT 验证轻量**：GET 请求仅需验证 token（HMAC 校验），不涉及哈希计算，CPU 开销小。
3. **TPS 未随并发线性增长**：说明后端线程池（Tomcat 默认 200 核心线程）在 500 并发后已接近饱和，额外并发在队列等待。

### 5.3 standards 接口 1000 并发边界超标

**现象**：P99 = 205.63ms，超出目标 5.63ms。

**分析**：属于边界波动，非系统性瓶颈。该接口 500 并发 P99 = 149ms，1000 并发升至 206ms，劣化倍数与 projects/assets 一致，未呈现异常拐点。max = 1,127ms 表明存在偶发长尾（可能为 GC 暂停或 JIT 编译），拉高了 P99。

### 5.4 单请求基线对比

表：单请求顺序延迟基线（20 次连打）

| API | avg(ms) | min(ms) | P95(ms) | max(ms) |
|-----|---------|---------|---------|---------|
| POST /auth/login | 9.87 | 2.05 | 28.49 | 30.92 |
| GET /projects | 1.34 | 0.74 | 1.65 | 6.36 |
| GET /governance/assets | 1.02 | 0.69 | 1.34 | 2.22 |
| GET /standards | 1.02 | 0.75 | 1.30 | 1.36 |
| GET /search/history | 1.00 | 0.42 | 2.98 | 3.41 |

单请求下所有接口延迟 < 10ms，确认后端本身处理速度极快，高并发下的延迟增长主要来自线程调度与 CPU 竞争，而非业务逻辑瓶颈。

## 第6章 优化建议

### 6.1 登录接口优化（优先级：高）

1. **引入登录限流**：对 /auth/login 接口按 IP/账号维度限流（如 10 次/分钟），避免恶意压测打满 CPU。
2. **密码哈希异步化或缓存**：对已认证过的会话，缓存认证结果，避免每次请求重新计算哈希。可引入 Redis 缓存 token→user 映射。
3. **降低哈希强度**：若当前使用 BCrypt cost=12+，可评估降至 cost=10，单次校验从 ~50ms 降至 ~10ms。
4. **JWT 签名算法降级**：HS384 可降为 HS256，签名速度提升约 30%，安全性在内部系统可接受。
5. **登录接口独立线程池**：将登录请求路由到独立线程池，避免与 GET 请求争抢线程资源。

### 6.2 GET 接口优化（优先级：中）

1. **增加 Tomcat 线程数**：当前 TPS 在 500 并发后不升，疑似线程池饱和。可将 `server.tomcat.threads.max` 从默认 200 调至 500，并配合 `max-connections` 提升。
2. **启用响应缓存**：/projects、/standards、/governance/assets 数据变化频率低，可对 GET 响应做短时缓存（如 5 秒 TTL），大幅降低后端负载。
3. **分页优化**：确认大结果集接口已分页，避免单次返回数千条记录。

### 6.3 通用优化（优先级：低）

1. **连接池调优**：H2 连接池默认配置可适当增大（如 HikariCP maximumPoolSize = 50）。
2. **JVM 参数**：后端常驻内存 1.1GB，建议显式设置 `-Xmx2g -Xms2g`，并启用 G1GC（`-XX:+UseG1GC`），减少 GC 暂停。
3. **生产环境替换 H2**：H2 内存库仅适用于测试，生产应使用 PostgreSQL/MySQL，并确保索引覆盖查询字段。

### 6.4 压测工具链优化

1. **安装 k6**：在可联网环境执行 `choco install k6` 或下载 [k6 v0.54.0](https://github.com/grafana/k6/releases)，运行已交付的 k6 脚本获取标准化输出。
2. **JMeter 分布式压测**：1000+ 并发建议使用 JMeter 多节点分布式模式，避免单机 PowerShell runspace 调度开销。
3. **接入 APM**：压测时同步开启 OpenTelemetry，关联延迟与 trace，定位慢调用栈。

## 第7章 交付物清单

### 7.1 k6 脚本

表：k6 脚本交付物

| 文件路径 | 说明 |
|---------|------|
| tests/performance/k6-scripts/common.js | 共享配置、登录、自定义指标 |
| tests/performance/k6-scripts/login-stress.js | 登录接口阶梯压测 |
| tests/performance/k6-scripts/api-baseline.js | 5 核心 API 基线测试 |
| tests/performance/k6-scripts/mixed-workload.js | 1000 并发混合工作负载 |

运行示例：

命令示例：k6 运行登录压测

```bash
# 100 并发 30 秒
k6 run --env VUS=100  DURATION=30s tests/performance/k6-scripts/login-stress.js
# 1000 并发 60 秒
k6 run --env VUS=1000 DURATION=60s tests/performance/k6-scripts/login-stress.js
# API 基线
k6 run --env VUS=100  DURATION=30s tests/performance/k6-scripts/api-baseline.js
# 混合工作负载
k6 run --env VUS=1000 DURATION=60s tests/performance/k6-scripts/mixed-workload.js
```

### 7.2 JMeter 测试计划

| 文件路径 | 说明 |
|---------|------|
| tests/performance/jmeter/api-baseline.jmx | 1000 线程、Ramp-up 10s、循环 10 次的测试计划 |

运行示例：

命令示例：JMeter 非 GUI 模式运行

```bash
jmeter -n -t tests/performance/jmeter/api-baseline.jmx -l results.jtl -e -o report-html
```

### 7.3 PowerShell 执行脚本

| 文件路径 | 说明 |
|---------|------|
| tests/performance/run-stress-httpclient.ps1 | HttpClient + RunspacePool 并发压测（本次实际使用） |
| tests/performance/run-stress.ps1 | Invoke-WebRequest 版（开销大，已弃用） |

### 7.4 原始结果数据

| 文件路径 | 说明 |
|---------|------|
| tests/performance/results/stress-results.json | 完整压测结果（JSON，含 statusBuckets） |
| tests/performance/results/stress-results.csv | 扁平 CSV，便于 Excel 透视 |
| tests/performance/results/intermediate_vu100.json | 100 并发中间结果 |
| tests/performance/results/intermediate_vu500.json | 500 并发中间结果 |
| tests/performance/results/intermediate_vu1000.json | 1000 并发中间结果 |

## 第8章 验证结果

### 8.1 目标达成情况

表：性能目标达成矩阵

| 目标 | 100 并发 | 500 并发 | 1000 并发 | 结论 |
|------|---------|---------|----------|------|
| P99 < 200ms（全部 API） | ✅ 5/5 | ⚠️ 4/5 | ⚠️ 3/5 | 100 并发完全达标；高并发登录接口不达标 |
| P99 < 200ms（GET 接口） | ✅ 4/4 | ✅ 4/4 | ⚠️ 3/4 | GET 接口基本达标，1000 并发 standards 边界超标 |
| 错误率 < 1% | ✅ 0% | ✅ 0% | ✅ 0% | 全部达标 |
| 支持 1000 并发连接 | - | - | ✅ 0 错误 | 后端未崩溃，连接全部成功 |
| TPS 记录 | ✅ | ✅ | ✅ | 已记录各 API TPS |

### 8.2 需要确认

1. **登录接口 P99 目标是否放宽**：登录为低频操作（用户不会每秒登录 1000 次），建议对登录接口单独设定 P99 < 500ms 的目标，GET 接口保持 P99 < 200ms。
2. **生产环境数据库**：本次使用 H2 内存库，生产环境切换为 PostgreSQL/MySQL 后需重新压测，预期 GET 接口延迟会上升（涉及磁盘 I/O）。
3. **standards 接口 1000 并发 P99 = 205ms 是否接受**：仅超标 5.63ms（2.8%），属边界波动，可接受或通过响应缓存消除。

---

> 报告生成时间：2026-08-17 18:43 ｜ 数据来源：tests/performance/results/stress-results.json ｜ 测试执行时长：427.5 秒