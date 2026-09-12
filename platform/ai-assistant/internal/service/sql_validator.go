package service

import (
	"errors"
	"regexp"
	"strings"
)

// 只读 SQL 必须以 SELECT 或 WITH（CTE）开头。
var readOnlySQLStart = regexp.MustCompile(`(?i)^\s*(SELECT|WITH)\b`)

// 禁止的 DDL/DML/系统关键字（词边界匹配，避免误判列名/表名中包含的子串）。
var forbiddenSQLPattern = regexp.MustCompile(
	`(?i)\b(DROP|DELETE|UPDATE|INSERT|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|CALL|MERGE|REPLACE|RENAME|ATTACH|DETACH|PRAGMA|LOAD|SHUTDOWN|VACUUM|SET|LOCK|UNLOCK)\b`)

// ValidateReadOnlySQL 校验 SQL 仅允许只读 SELECT 查询，禁止 DDL/DML 与多语句。
// 防止通过 Chat 端点的 EnableExec 链路执行破坏性 SQL（如 DROP TABLE、DELETE、UPDATE 等）。
//
// 与 api 包的 validateReadOnlySQL 逻辑一致，放在 service 包以避免 api→service 循环依赖。
func ValidateReadOnlySQL(sql string) error {
	s := strings.TrimSpace(sql)
	if s == "" {
		return errors.New("SQL 不能为空")
	}
	// 禁止多语句（分号分隔），防止语句拼接注入。
	if strings.Contains(s, ";") {
		return errors.New("禁止多语句执行")
	}
	// 必须以 SELECT 或 WITH 开头（只读查询）。
	if !readOnlySQLStart.MatchString(s) {
		return errors.New("仅允许只读 SELECT 查询")
	}
	// 禁止任何 DDL/DML/系统关键字。
	if forbiddenSQLPattern.MatchString(s) {
		return errors.New("SQL 包含禁止的写操作关键字")
	}
	return nil
}
