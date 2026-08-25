package service

import (
	"fmt"
	"net/url"

	"github.com/prometheus/prometheus/model/labels"
	"github.com/prometheus/prometheus/promql/parser"
)

const tenantLabelName = "tenant_id"

// TenantFilter 负责租户隔离的 PromQL 注入与标签过滤。
//
// 核心策略：
//  1. 对瞬时/范围查询的 PromQL（query 参数），解析 PromQL AST，遍历所有
//     VectorSelector 节点，为缺失 tenant_id 匹配器的选择器追加
//     {tenant_id="xxx"} 等值匹配器后重新序列化。该方式正确覆盖嵌套聚合
//     （sum/rate...）与 subquery，且保留 offset/@ 子句。
//  2. 对 labels/series 请求，追加 match[]={__name__=~"...",tenant_id="xxx"} 过滤。
//  3. 平台方请求不做任何过滤（全平台可见）。
type TenantFilter struct{}

// NewTenantFilter 创建 TenantFilter。
func NewTenantFilter() *TenantFilter {
	return &TenantFilter{}
}

// InjectTenantQuery 将 tenant_id 标签过滤注入到 PromQL 查询表达式的每个选择器上。
//
// 策略：
//   - 解析失败（非法 PromQL）时原样返回，由下游 Prometheus 返回原始解析错误。
//   - 已显式含 tenant_id 匹配器的选择器不重复注入。
//   - offset / @ 子句在重新序列化时保持不变。
func (f *TenantFilter) InjectTenantQuery(promql string, tenantID string) string {
	if tenantID == "" {
		return promql
	}
	// 每次调用创建局部 parser 实例，避免包级单例的 statefulLexer 在并发调用时竞态。
	p := parser.NewParser(parser.Options{})
	expr, err := p.ParseExpr(promql)
	if err != nil {
		return promql
	}
	injector := &tenantMatcherInjector{tenantID: tenantID}
	if err := parser.Walk(injector, expr, nil); err != nil {
		return promql
	}
	return expr.String()
}

// tenantMatcherInjector 遍历 AST 并为缺失 tenant_id 匹配器的 VectorSelector 注入等值匹配器。
type tenantMatcherInjector struct {
	tenantID string
}

// Visit 实现 parser.Visitor 接口。
func (v *tenantMatcherInjector) Visit(node parser.Node, _ []parser.Node) (parser.Visitor, error) {
	if vs, ok := node.(*parser.VectorSelector); ok && !hasTenantMatcher(vs.LabelMatchers) {
		vs.LabelMatchers = append(vs.LabelMatchers,
			labels.MustNewMatcher(labels.MatchEqual, tenantLabelName, v.tenantID))
	}
	return v, nil
}

func hasTenantMatcher(ms []*labels.Matcher) bool {
	for _, m := range ms {
		if m.Name == tenantLabelName {
			return true
		}
	}
	return false
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
