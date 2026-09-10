// Package service 的租户隔离辅助函数。
//
// 本文件实现向量集合的租户命名空间隔离策略：
//   - 每个租户的集合在存储层映射为 "<tenantId>__<collectionName>" 的内部名
//   - 不同租户的集合天然隔离，互不可见
//   - ListCollections 仅返回当前租户前缀的集合，并剥离前缀还原原始名
//
// 租户身份通过 context 传递（request-scoped），由 handler 层从 JWT 注入。
// service 层从 context 提取 tenantId，缺失时 fail-fast 返回 ErrMissingTenant。
// 这确保即使 handler 遗漏注入，也不会静默退化为无隔离的裸访问。
package service

import (
	"context"
	"errors"
	"strings"
)

// ErrMissingTenant 表示 context 中缺少租户身份，拒绝执行任何数据操作。
// 这是一个安全哨兵错误：没有租户身份的请求不得触碰向量数据。
var ErrMissingTenant = errors.New("missing tenant identity in context: tenant isolation requires a non-empty tenantId")

// tenantCtxKey 是 context 中租户身份的键类型，使用未导出结构体避免冲突。
type tenantCtxKey struct{}

// WithTenantID 将租户身份注入 context，返回新的 context。
// 供 handler 层在调用 service 方法前构造带租户的请求上下文。
func WithTenantID(ctx context.Context, tenantID string) context.Context {
	return context.WithValue(ctx, tenantCtxKey{}, tenantID)
}

// TenantIDFromContext 从 context 提取租户身份。
// 返回 (tenantID, true) 当且仅当存在非空 tenantId；否则返回 ("", false)。
func TenantIDFromContext(ctx context.Context) (string, bool) {
	v, ok := ctx.Value(tenantCtxKey{}).(string)
	if !ok || strings.TrimSpace(v) == "" {
		return "", false
	}
	return v, true
}

// tenantNamespaceSeparator 是租户前缀与集合名之间的分隔符。
// 使用双下划线降低与合法集合名碰撞的概率。
const tenantNamespaceSeparator = "__"

// namespacedCollectionName 将用户可见的集合名映射为带租户前缀的内部存储名。
// 例如：tenantID="t1", name="my_vectors" → "t1__my_vectors"。
// 这确保不同租户的同名集合在存储层互不冲突。
func namespacedCollectionName(tenantID, name string) string {
	return tenantID + tenantNamespaceSeparator + name
}

// tenantPrefix 返回租户的集合前缀，用于 ListCollections 过滤。
// 例如：tenantID="t1" → "t1__"。
func tenantPrefix(tenantID string) string {
	return tenantID + tenantNamespaceSeparator
}

// stripTenantPrefix 从内部存储名中剥离租户前缀，还原用户可见的集合名。
// 若内部名不以该租户前缀开头，返回空字符串与 false（表示不属于该租户）。
func stripTenantPrefix(tenantID, internalName string) (string, bool) {
	prefix := tenantPrefix(tenantID)
	if !strings.HasPrefix(internalName, prefix) {
		return "", false
	}
	return strings.TrimPrefix(internalName, prefix), true
}
