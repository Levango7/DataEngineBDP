package service

import (
	"strings"
	"testing"
)

// TestValidateReadOnlySQL 覆盖只读 SQL 校验的合法与非法用例。
//
// 该校验是 /execute 链路的写操作防线（拦截 DROP/DELETE/UPDATE 等破坏性 SQL），
// 属于安全关键逻辑，必须保证正反用例均有覆盖。
func TestValidateReadOnlySQL(t *testing.T) {
	cases := []struct {
		name    string
		sql     string
		wantErr string // 期望错误信息子串；为空表示应校验通过
	}{
		// ---- 合法只读查询 ----
		{name: "简单 SELECT", sql: "SELECT id, name FROM orders"},
		{name: "小写 select", sql: "select 1"},
		{name: "前导空白与换行", sql: "   \n\t SELECT 1"},
		{name: "WITH CTE", sql: "WITH t AS (SELECT 1) SELECT * FROM t"},
		{name: "聚合查询", sql: "SELECT count(*) FROM orders GROUP BY status"},
		{name: "子查询", sql: "SELECT 1 FROM t WHERE x IN (SELECT 2)"},
		{name: "块注释", sql: "SELECT /* note */ 1"},
		{name: "列名含 created 子串不误判", sql: "SELECT created_at, update_time FROM orders"},
		{name: "表名含 settings 不误判 SET", sql: "SELECT * FROM settings"},
		{name: "列名含 upload 不误判 LOAD", sql: "SELECT upload_id FROM t"},

		// ---- 空 / 纯空白 ----
		{name: "空字符串", sql: "", wantErr: "SQL 不能为空"},
		{name: "纯空白", sql: "   \t\n ", wantErr: "SQL 不能为空"},

		// ---- 多语句 ----
		{name: "多语句拼接", sql: "SELECT 1; SELECT 2", wantErr: "禁止多语句执行"},
		{name: "分号结尾", sql: "SELECT 1;", wantErr: "禁止多语句执行"},

		// ---- 非只读开头 ----
		{name: "SHOW 语句", sql: "SHOW TABLES", wantErr: "仅允许只读 SELECT 查询"},
		{name: "EXPLAIN 开头", sql: "EXPLAIN SELECT 1", wantErr: "仅允许只读 SELECT 查询"},
		{name: "DROP 开头", sql: "DROP TABLE orders", wantErr: "仅允许只读 SELECT 查询"},

		// ---- SELECT 开头但内嵌禁止关键字 ----
		{
			name:    "字符串字面量含 update 被词边界命中",
			sql:     "SELECT 1 FROM t WHERE note = 'please update me'",
			wantErr: "SQL 包含禁止的写操作关键字",
		},
		{
			name:    "内嵌 DELETE 子句",
			sql:     "SELECT 1 FROM t WHERE x IN (SELECT 1 FROM t2 WHERE note = 'delete')",
			wantErr: "SQL 包含禁止的写操作关键字",
		},
		{
			name:    "内嵌 TRUNCATE",
			sql:     "SELECT 1 FROM t WHERE note = 'truncate'",
			wantErr: "SQL 包含禁止的写操作关键字",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateReadOnlySQL(tc.sql)
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("期望校验通过，实际报错: %v", err)
				}
				return
			}
			if err == nil {
				t.Fatalf("期望报错（含 %q），实际通过", tc.wantErr)
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("错误信息期望含 %q，实际 %q", tc.wantErr, err.Error())
			}
		})
	}
}

// forbiddenKeywords 与 sql_validator.go 的 forbiddenSQLPattern 保持一致的完整关键字集。
var forbiddenKeywords = []string{
	"DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE", "TRUNCATE",
	"GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "MERGE", "REPLACE",
	"RENAME", "ATTACH", "DETACH", "PRAGMA", "LOAD", "SHUTDOWN",
	"VACUUM", "SET", "LOCK", "UNLOCK",
}

// TestValidateReadOnlySQL_AllForbiddenKeywords 每一个禁止关键字都必须被拦截（大写）。
func TestValidateReadOnlySQL_AllForbiddenKeywords(t *testing.T) {
	for _, kw := range forbiddenKeywords {
		t.Run(kw, func(t *testing.T) {
			sql := "SELECT 1 FROM t WHERE note = '" + kw + "'"
			err := ValidateReadOnlySQL(sql)
			if err == nil {
				t.Fatalf("关键字 %s 应被拦截，实际通过", kw)
			}
			if !strings.Contains(err.Error(), "禁止的写操作关键字") {
				t.Fatalf("关键字 %s 错误信息不符: %v", kw, err)
			}
		})
	}
}

// TestValidateReadOnlySQL_ForbiddenKeywordsCaseInsensitive 关键字匹配应大小写不敏感。
func TestValidateReadOnlySQL_ForbiddenKeywordsCaseInsensitive(t *testing.T) {
	for _, kw := range forbiddenKeywords {
		t.Run(kw, func(t *testing.T) {
			sql := "SELECT 1 FROM t WHERE note = '" + strings.ToLower(kw) + "'"
			if err := ValidateReadOnlySQL(sql); err == nil {
				t.Fatalf("小写关键字 %s 应被拦截，实际通过", strings.ToLower(kw))
			}
		})
	}
}

// TestValidateReadOnlySQL_ReadOnlyPrefixCaseInsensitive SELECT/WITH 前缀应大小写不敏感。
func TestValidateReadOnlySQL_ReadOnlyPrefixCaseInsensitive(t *testing.T) {
	for _, sql := range []string{"select 1", "SeLeCt 1", "with t as (select 1) select * from t", "WITH t AS (SELECT 1) SELECT 1 FROM t"} {
		if err := ValidateReadOnlySQL(sql); err != nil {
			t.Fatalf("只读查询 %q 应通过，实际报错: %v", sql, err)
		}
	}
}
