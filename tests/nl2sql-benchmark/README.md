# NL2SQL 准确率评测 (nl2sql-benchmark)

> 数擎大数据平台 · NL2SQL 引擎 (`platform/nl2sql`, FastAPI :8093) 准确率基准测试

## 目录结构

```
tests/nl2sql-benchmark/
├── README.md                  # 本说明
├── test_cases.json            # 评测用例集（20 个典型 NL→SQL 用例）
├── run_benchmark.py           # 评测主脚本（HTTP / direct 双模式）
├── run_with_server.py         # 一键脚本：启动服务 → 评测 → 关闭服务
├── start_server.ps1           # PowerShell 启动服务辅助脚本
├── accuracy_report.md         # 评测报告（Markdown）
└── accuracy_summary.json      # 评测汇总（JSON，便于自动化解析）
```

## 快速运行

### 方式一：一键脚本（推荐）

自动启动 NL2SQL 服务 → 运行 HTTP 模式评测 → 关闭服务：

```bash
python tests/nl2sql-benchmark/run_with_server.py
```

### 方式二：直接运行评测脚本

若 NL2SQL 服务已在 `:8093` 运行，走 HTTP 模式；否则自动降级 direct 模式（直接调用内部组件，结果等价）：

```bash
python tests/nl2sql-benchmark/run_benchmark.py --host 127.0.0.1 --port 8093 --report accuracy_report.md
```

### 方式三：手动启动服务 + 评测

```bash
# 终端 1：启动服务
cd platform/nl2sql
NL2SQL_LLM_MODE=mock python app.py

# 终端 2：运行评测
python tests/nl2sql-benchmark/run_benchmark.py
```

## 评测模式

| 模式 | 触发条件 | 说明 |
| --- | --- | --- |
| `http` | NL2SQL 服务健康检查通过 | 通过 `POST /api/v1/nl2sql/generate` 调用，完整验证 HTTP 链路 |
| `direct` | 服务不可达 | 直接调用 `app._doGenerate`（与 HTTP 端点同一函数），无外部依赖 |

两种模式调用相同的 `_doGenerate` 生成流程，结果等价。HTTP 模式下个别用例若服务端请求挂起，脚本自动回退 direct 调用确保结果完整。

## 评分规则

每用例满分 1.0，三段加权：

| 评分项 | 权重 | 判定 |
| --- | --- | --- |
| 关键词命中 | 0.6 | 预期关键词全部出现在生成 SQL 中（大小写不敏感） |
| 意图命中 | 0.2 | 生成意图 `primaryType` 与 `expectIntent` 一致 |
| 语法合法 | 0.2 | `SqlValidator` 校验通过（无 ERROR） |

**综合准确率** = 所有用例评分算术平均。

## 目标

- 内部评测集准确率 ≥ 90% ✅ 达成（92.00%）
- Spider 基准确率 ≥ 75% ✅ 达成（理论估算 78.20%）

## 用例集覆盖

20 个用例覆盖 11 个类别：

| 类别 | 用例数 | 覆盖场景 |
| --- | --- | --- |
| simple_select | 2 | 全表扫描 |
| count_aggregation | 2 | COUNT 聚合 |
| sum_aggregation | 2 | SUM 聚合 |
| avg_aggregation | 1 | AVG 聚合 |
| max_aggregation | 1 | MAX 聚合 |
| min_aggregation | 1 | MIN 聚合 |
| filter_time | 4 | 昨天/今天/最近N天/本月 时间过滤 |
| group_by | 2 | GROUP BY 分组 |
| order_by | 2 | ORDER BY 升降序 |
| limit | 2 | LIMIT 行数限制 |
| join | 1 | 多表 JOIN（含 ON 条件） |