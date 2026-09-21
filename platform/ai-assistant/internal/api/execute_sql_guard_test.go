package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
)

// /execute 端点必须在转发给下游 sql-gateway 之前拦截破坏性 SQL。
//
// 背景：只读 SQL 校验此前在 api 包（validateReadOnlySQL）与 service 包
// （ValidateReadOnlySQL）各有一份逐字相同的实现，api 包的副本注释声称
// 是为「避免 api→service 循环依赖」而复制的，但 handler.go 本来就 import 了
// internal/service，该循环依赖并不存在，复制前提失效。
// 保留两份实现意味着安全规则必须改两次，漏改一份就会让 /execute 的
// 写操作防线出现缺口。删除副本统一走 service.ValidateReadOnlySQL 之后，
// 本测试锁住「/execute 仍然拦截破坏性 SQL」这一性质。
func TestExecute_DestructiveSQL_Rejected(t *testing.T) {
	cases := []struct {
		name string
		sql  string
	}{
		{"DROP", "DROP TABLE orders"},
		{"DELETE", "DELETE FROM orders WHERE 1=1"},
		{"UPDATE", "UPDATE orders SET amount = 0"},
		{"INSERT", "INSERT INTO orders VALUES (1)"},
		{"ALTER", "ALTER TABLE orders ADD COLUMN x INT"},
		{"TRUNCATE", "TRUNCATE TABLE orders"},
		{"多语句拼接", "SELECT 1; DROP TABLE orders"},
		{"非 SELECT 开头", "EXPLAIN SELECT 1"},
		{"空语句", ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			router := buildTestRouter(t, nil)
			token := makeTestToken(t, "tenant-a")
			payload, err := json.Marshal(map[string]string{
				"sql":      tc.sql,
				"dialect":  "ANSI",
				"tenantId": "tenant-a",
			})
			assert.NoError(t, err)

			w := doJSON(router, http.MethodPost,
				"/api/v1/ai-assistant/execute", token, string(payload))

			// 校验发生在转发之前，因此必须是 400，而不是由下游返回的 502。
			assert.Equal(t, http.StatusBadRequest, w.Code,
				"破坏性 SQL 必须在转发前被拦截: %s", tc.sql)
		})
	}
}

// 反向用例：合法只读 SELECT 不应被只读校验拒绝（400）。
// 没有这条，上面那组断言无法区分「正确拦截」与「一律拦截」。
// 只读查询可能因为下游不可用而返回 5xx，但绝不应是 400。
func TestExecute_ReadOnlySQL_NotRejectedByGuard(t *testing.T) {
	router := buildTestRouter(t, nil)
	token := makeTestToken(t, "tenant-a")
	payload, err := json.Marshal(map[string]string{
		"sql":      "SELECT id, amount FROM orders WHERE tenant_id = 'tenant-a'",
		"dialect":  "ANSI",
		"tenantId": "tenant-a",
	})
	assert.NoError(t, err)

	w := doJSON(router, http.MethodPost,
		"/api/v1/ai-assistant/execute", token, string(payload))

	assert.NotEqual(t, http.StatusBadRequest, w.Code,
		"合法只读 SELECT 不应被只读校验拒绝")
}
