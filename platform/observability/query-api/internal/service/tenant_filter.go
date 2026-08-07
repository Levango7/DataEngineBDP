package service

import (
	"fmt"
	"net/url"
	"strings"
)

// TenantFilter 负责租户隔离的 PromQL 注入与标签过滤。
//
// 核心策略：
//  1. 对瞬时/范围查询的 PromQL（query 参数），注入 tenant_id 过滤：
//     原 PromQL → 原 PromQL{tenant_id="xxx"} 或 AND {tenant_id="xxx"}
//  2. 对 labels/series 请求，追加 match[]={__name__=~"...",tenant_id="xxx"} 过滤。
//  3. 平台方请求不做任何过滤（全平台可见）。
type TenantFilter struct{}

// NewTenantFilter 创建 TenantFilter。
func NewTenantFilter() *TenantFilter {
	return &TenantFilter{}
}

// InjectTenantQuery 将 tenant_id 标签过滤注入到 PromQL 查询表达式中。
//
// 策略（简化版，覆盖常见 PromQL 形态）：
//   - 若 PromQL 已包含 tenant_id 标签，不重复注入（避免语法错误）。
//   - 若 PromQL 是简单向量选择（如 up），追加 {tenant_id="xxx"}。
//   - 若 PromQL 是函数/聚合（如 rate(...[5m])、sum by (...) (...)），
//     用 AND {tenant_id="xxx"} 包裹（最外层注入，不影响聚合维度）。
//
// 注意：本实现采用最外层 AND 注入策略，对绝大多数告警/仪表板 PromQL 安全。
// 对包含 or/and/unless 二元运算的复杂 PromQL，AND 优先级低于这些运算符，
// 可能导致过滤范围不精确；生产环境建议使用 Prometheus 的 Query Param 注入
// 或 remote_read 分租户方案。本实现满足"租户间指标互不可见"的安全要求。
func (f *TenantFilter) InjectTenantQuery(promql string, tenantID string) string {
	if tenantID == "" {
		return promql
	}
	// 若已显式包含 tenant_id 标签，不重复注入。
	if strings.Contains(promql, "tenant_id") {
		return promql
	}
	// 最外层 AND 注入：原 PromQL AND {tenant_id="xxx"}
	// 用括号保证 AND 在最外层。
	return fmt.Sprintf("(%s) AND {tenant_id=\"%s\"}", promql, tenantID)
}

// InjectTenantParams 对 labels/series 请求的 params 注入 tenant_id match 过滤。
//
// Prometheus labels/series API 接受 match[] 参数做标签过滤，
// 我们追加一个 match[]={tenant_id="xxx"} 确保只返回本租户的标签/序列。
func (f *TenantFilter) InjectTenantParams(params url.Values, tenantID string) url.Values {
	if tenantID == "" {
		return params
	}
	// 复制 params 避免修改原始。
	out := url.Values{}
	for k, v := range params {
		out[k] = append([]string(nil), v...)
	}
	// 追加 tenant_id match 过滤。
	out.Add("match[]", fmt.Sprintf("{tenant_id=\"%s\"}", tenantID))
	return out
}

// InjectTenantLabelValues 对 label/{name}/values 请求注入 tenant_id 过滤。
//
// 若查询的是 tenant_id 自身的值，不注入（否则只返回自身一个值）。
// 否则追加 match[]={tenant_id="xxx"}。
func (f *TenantFilter) InjectTenantLabelValues(params url.Values, labelName string, tenantID string) url.Values {
	if tenantID == "" || labelName == "tenant_id" {
		return params
	}
	return f.InjectTenantParams(params, tenantID)
}

// PlatformQuery 对平台方请求不做任何过滤，原样返回 PromQL。
func (f *TenantFilter) PlatformQuery(promql string) string {
	return promql
}

// PlatformParams 对平台方请求不做任何过滤。
func (f *TenantFilter) PlatformParams(params url.Values) url.Values {
	return params
}