# Go 测试框架统一标准

> 版本：v1.0 ｜ 日期：2026-08-20 ｜ 状态：生效
> 适用范围：`platform/catalog/`、`platform/dqctl/` 及后续新增的所有 Go 模块
> 关联审计项：L8（Go 测试框架不统一 → 统一 testify）

---

## 1. 背景与结论

### 1.1 审计发现

代码审计发现 `platform/catalog/` 与 `platform/dqctl/` 两个 Go 模块存在测试框架不统一的潜在风险，要求统一为 `github.com/stretchr/testify`。

### 1.2 现状核查结果

经全量核查，**两个模块的测试框架已统一使用 testify**，无需进行代码迁移：

| 模块 | go.mod testify 版本 | 测试文件数 | 是否全部使用 testify | 是否混用标准库断言 |
| --- | --- | --- | --- | --- |
| `platform/catalog/` | v1.11.1 | 8 | ✅ 是 | ❌ 否 |
| `platform/dqctl/` | v1.11.1 | 7 | ✅ 是 | ❌ 否 |

核查明细：

- `catalog` 测试文件（8 个）：`catalog_test.go`、`health_test.go`、`auth_test.go`、`cors_test.go`、`logging_test.go`、`metrics_test.go`、`gorm_store_test.go`、`store_test.go` —— 全部 import `github.com/stretchr/testify/assert`，部分同时使用 `require`。
- `dqctl` 测试文件（7 个）：`apply_test.go`、`init_test.go`、`query_test.go`、`status_test.go`、`version_test.go`、`client_test.go`、`config_test.go` —— 全部 import `assert` + `require`。
- 未发现任何 `t.Errorf` / `t.Fatalf` / `t.Error` / `t.Fatal` 等标准库直接断言调用（`assert.Error(t, err)` 属于 testify API，不计入混用）。

### 1.3 处置结论

由于框架已统一，**不进行代码改动**。本文档作为统一标准的固化依据，约束后续新增测试必须遵循，防止框架退化。

---

## 2. 统一标准（强制）

### 2.1 框架选型

| 项 | 规定 |
| --- | --- |
| 断言库 | `github.com/stretchr/testify/assert`（必选） |
| 强断言库 | `github.com/stretchr/testify/require`（失败立即终止，用于前置条件） |
| 版本 | 锁定 `v1.11.1`（与现有 go.mod 一致，升级需评审） |
| 禁止 | 禁止直接使用标准库 `t.Errorf` / `t.Fatalf` / `t.Fatal` 作为断言 |

### 2.2 使用规范

**assert vs require 选择原则**：

- `assert.*`：断言失败后继续执行当前测试函数（用于非关键断言，收集多个失败）。
- `require.*`：断言失败立即终止当前测试（用于前置条件，如初始化、准备数据；后续逻辑依赖该条件成立时必须用 require）。

**示例**：

```go
package store

import (
    "testing"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/require"
)

func TestStore_Create(t *testing.T) {
    s := NewStore()
    // 前置条件：初始化必须成功，否则后续无意义 → require
    require.NotNil(t, s, "store should be initialized")

    err := s.Create(&Item{ID: "x"})
    // 普通断言 → assert
    assert.NoError(t, err)
    assert.Equal(t, "x", s.Get("x").ID)
}
```

### 2.3 子测试命名

使用 `t.Run("子场景描述", func(t *testing.T) {...})` 组织子测试，子测试名用中文或英文短句，描述被测行为而非实现：

```go
func TestClient_Query(t *testing.T) {
    t.Run("正常请求返回结果", func(t *testing.T) { ... })
    t.Run("超时返回错误", func(t *testing.T) { ... })
}
```

### 2.4 表驱动测试

多场景测试优先使用表驱动 + `t.Run`：

```go
func TestParse(t *testing.T) {
    cases := []struct {
        name    string
        input   string
        want    int
        wantErr bool
    }{
        {"空串返回0", "", 0, false},
        {"非法字符报错", "abc", 0, true},
    }
    for _, c := range cases {
        t.Run(c.name, func(t *testing.T) {
            got, err := Parse(c.input)
            if c.wantErr {
                assert.Error(t, err)
                return
            }
            require.NoError(t, err)
            assert.Equal(t, c.want, got)
        })
    }
}
```

---

## 3. CI 防护

在 CI 中增加静态检查，防止未来引入标准库断言导致框架退化：

```bash
# 检查 Go 测试文件中是否出现标准库断言（应无输出）
rg -n '\bt\.(Errorf|Fatalf|Error|Fatal|Fail|FailNow)\(' --glob '*_test.go' platform/
```

若上述命令有输出，CI 应失败并提示本指南。

---

## 4. 模块清单与验证命令

| 模块 | 路径 | 运行测试 |
| --- | --- | --- |
| catalog | `platform/catalog/` | `cd platform/catalog && go test ./...` |
| dqctl | `platform/dqctl/` | `cd platform/dqctl && go test ./...` |

新增 Go 模块时，必须：
1. 在 go.mod 中引入 `github.com/stretchr/testify v1.11.1`
2. 测试文件遵循本指南第 2 节规范
3. 在本表格中登记

---

## 5. 变更记录

| 日期 | 变更 | 关联 |
| --- | --- | --- |
| 2026-08-20 | 初版，确认 catalog/dqctl 已统一 testify，固化标准 | L8 |