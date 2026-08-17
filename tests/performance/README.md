# 性能测试套件

> DataEngineBDP 平台 P1 性能测试，验证 API P99 < 200ms，并发 1000 连接。

## 目录结构

```
tests/performance/
├── k6-scripts/              # k6 压测脚本（ES 模块）
│   ├── common.js            # 共享配置与工具函数
│   ├── login-stress.js      # 登录接口阶梯压测
│   ├── api-baseline.js      # 5 核心 API 基线测试
│   └── mixed-workload.js    # 1000 并发混合工作负载
├── jmeter/                  # JMeter 测试计划
│   └── api-baseline.jmx     # 1000 线程测试计划
├── reports/                 # 压测报告
│   └── performance-report.md
├── results/                 # 原始结果数据
│   ├── stress-results.json
│   ├── stress-results.csv
│   └── intermediate_vu*.json
├── run-stress-httpclient.ps1  # PowerShell HttpClient 压测脚本（推荐）
├── run-stress.ps1             # PowerShell Invoke-WebRequest 版（已弃用）
└── README.md
```

## 前置条件

1. 后端服务运行在 `http://localhost:18086`
2. 登录账号：admin / admin
3. 健康检查：`GET /actuator/health` 返回 200

## 运行方式

### 方式一：PowerShell（无需额外安装）

```powershell
# 完整阶梯压测 100→500→1000
.\run-stress-httpclient.ps1

# 自定义参数
.\run-stress-httpclient.ps1 -ConcurrencySteps @(100,500,1000) -DurationSec 20 -RampUpSec 5
```

### 方式二：k6（需安装 k6）

```bash
# 安装 k6
choco install k6          # Windows
brew install k6           # macOS

# 运行登录压测
k6 run --env VUS=100  DURATION=30s k6-scripts/login-stress.js
k6 run --env VUS=1000 DURATION=60s k6-scripts/login-stress.js

# 运行 API 基线
k6 run --env VUS=100  DURATION=30s k6-scripts/api-baseline.js

# 运行混合工作负载
k6 run --env VUS=1000 DURATION=60s k6-scripts/mixed-workload.js
```

### 方式三：JMeter（需安装 JMeter 5.6+）

```bash
# GUI 模式打开
jmeter -t jmeter/api-baseline.jmx

# 非 GUI 模式运行
jmeter -n -t jmeter/api-baseline.jmx -l results.jtl -e -o report-html
```

## 性能目标

| 指标 | 目标 |
|------|------|
| P99 延迟 | < 200ms |
| 并发连接 | 1000 |
| 错误率 | < 1% |
| 业务成功率 | > 99% |

## 最新结果摘要

详见 `reports/performance-report.md`。

- 100 并发：5/5 API 达标，TPS 3,807 ~ 7,472
- 500 并发：4/5 API 达标（登录 P99=950ms 未达标）
- 1000 并发：3/5 API 达标（登录 P99=992ms，standards P99=206ms 未达标）
- 错误率：全部 0%
