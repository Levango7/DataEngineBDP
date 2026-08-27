package service

import (
	"github.com/prometheus/common/model"
	"net/url"
	"testing"

	"github.com/prometheus/prometheus/model/labels"
	"github.com/prometheus/prometheus/promql/parser"
)

// TestNewTenantFilter 验证 TenantFilter 构造函数。
func TestNewTenantFilter(t *testing.T) {
	f := NewTenantFilter()
	if f == nil {
		t.Fatal("NewTenantFilter returned nil")
	}
}

// TestInjectTenantQuery_EmptyTenantID 空租户 ID 应原样返回 PromQL。
func TestInjectTenantQuery_EmptyTenantID(t *testing.T) {
	f := NewTenantFilter()
	const promql = "up"
	if got := f.InjectTenantQuery(promql, ""); got != promql {
		t.Fatalf("empty tenantID should be no-op, got %q", got)
	}
}

// TestInjectTenantQuery_AlreadyContainsTenantID 选择器已含 tenant_id 匹配器时不应重复注入。
func TestInjectTenantQuery_AlreadyContainsTenantID(t *testing.T) {
	f := NewTenantFilter()
	const promql = `up{tenant_id="abc"}`
	if got := f.InjectTenantQuery(promql, "abc"); got != promql {
		t.Fatalf("already contains tenant_id should be no-op, got %q", got)
	}
}

// TestInjectTenantQuery_SimpleVector 简单向量应在选择器上注入 tenant_id 匹配器。
func TestInjectTenantQuery_SimpleVector(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("up", "tenant-1")
	const want = `up{tenant_id="tenant-1"}`
	if got != want {
		t.Fatalf("unexpected injected PromQL: got %q want %q", got, want)
	}
}

// TestInjectTenantQuery_FunctionCall 函数调用内的选择器应注入 tenant_id 匹配器。
func TestInjectTenantQuery_FunctionCall(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("rate(http_requests_total[5m])", "acme")
	const want = `rate(http_requests_total{tenant_id="acme"}[5m])`
	if got != want {
		t.Fatalf("unexpected injected PromQL: got %q want %q", got, want)
	}
}

// TestInjectTenantQuery_Aggregation 聚合查询（sum(rate(...)) by (job)）
// 注入后 AST 中 x 的选择器必须包含 tenant_id 等值匹配器。
func TestInjectTenantQuery_Aggregation(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("sum(rate(x[5m])) by (job)", "tenant-x")

	p := parser.NewParser(parser.Options{})
	expr, err := p.ParseExpr(got)
	if err != nil {
		t.Fatalf("injected PromQL failed to parse: %v (%q)", err, got)
	}
	selectors := parser.ExtractSelectors(expr)
	var metricX []*labels.Matcher
	for _, sel := range selectors {
		for _, m := range sel {
			if m.Name == model.MetricNameLabel && m.Value == "x" {
				metricX = sel
			}
		}
	}
	if metricX == nil {
		t.Fatalf("selector for metric x not found in %d selectors of %q", len(selectors), got)
	}
	found := false
	for _, m := range metricX {
		if m.Name == "tenant_id" && m.Type == labels.MatchEqual && m.Value == "tenant-x" {
			found = true
		}
	}
	if !found {
		t.Fatalf("metric x selector missing tenant_id EQ matcher: %v", metricX)
	}
}

// TestInjectTenantQuery_NestedSubquery 嵌套聚合 + subquery 内层选择器同样被注入。
func TestInjectTenantQuery_NestedSubquery(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("max_over_time(sum(rate(x[5m])) by (job)[10m:1m])", "t-1")
	const want = `max_over_time(sum by (job) (rate(x{tenant_id="t-1"}[5m]))[10m:1m])`
	if got != want {
		t.Fatalf("unexpected injected PromQL: got %q want %q", got, want)
	}
}

// TestInjectTenantQuery_InvalidPromQL 非法 PromQL 原样返回，保持下游原始错误路径。
func TestInjectTenantQuery_InvalidPromQL(t *testing.T) {
	f := NewTenantFilter()
	for _, bad := range []string{"sum(rate(", `{foo=`, `up AND`} {
		if got := f.InjectTenantQuery(bad, "t-1"); got != bad {
			t.Fatalf("invalid PromQL must be returned unchanged: got %q want %q", got, bad)
		}
	}
}

// TestInjectTenantQuery_OffsetAndAtPreserved offset / @ 子句不被破坏且选择器仍被注入。
func TestInjectTenantQuery_OffsetAndAtPreserved(t *testing.T) {
	f := NewTenantFilter()

	offsetGot := f.InjectTenantQuery("rate(x[5m] offset 1h)", "t-1")
	const offsetWant = `rate(x{tenant_id="t-1"}[5m] offset 1h)`
	if offsetGot != offsetWant {
		t.Fatalf("offset clause broken: got %q want %q", offsetGot, offsetWant)
	}

	atGot := f.InjectTenantQuery(`x @ end()`, "t-1")
	const atWant = `x{tenant_id="t-1"} @ end()`
	if atGot != atWant {
		t.Fatalf("@ clause broken: got %q want %q", atGot, atWant)
	}
}

// TestInjectTenantQuery_MixedSelectors 多选择器查询只对缺失 tenant_id 的选择器注入。
func TestInjectTenantQuery_MixedSelectors(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery(`up{tenant_id="abc"} + rate(errs_total[5m])`, "abc")
	const want = `up{tenant_id="abc"} + rate(errs_total{tenant_id="abc"}[5m])`
	if got != want {
		t.Fatalf("unexpected injected PromQL: got %q want %q", got, want)
	}
}

// TestInjectTenantParams_EmptyTenantID 空租户 ID 应原样返回 params。
func TestInjectTenantParams_EmptyTenantID(t *testing.T) {
	f := NewTenantFilter()
	in := url.Values{"start": []string{"100"}}
	out := f.InjectTenantParams(in, "")
	if out.Get("start") != "100" {
		t.Fatalf("expected start=100, got %q", out.Get("start"))
	}
	if vals := out["match[]"]; len(vals) != 0 {
		t.Fatalf("expected no match[] for empty tenant, got %v", vals)
	}
}

// TestInjectTenantParams_AppendsMatch 应追加 match[] 过滤且不修改原始 params。
func TestInjectTenantParams_AppendsMatch(t *testing.T) {
	f := NewTenantFilter()
	in := url.Values{"start": []string{"100"}}
	out := f.InjectTenantParams(in, "tenant-1")

	// 原始 params 不应被修改。
	if got := in.Get("match[]"); got != "" {
		t.Fatalf("original params mutated: match[]=%q", got)
	}
	// 输出应包含 tenant_id match。
	matches := out["match[]"]
	if len(matches) != 1 {
		t.Fatalf("expected 1 match[], got %d: %v", len(matches), matches)
	}
	const want = `{tenant_id="tenant-1"}`
	if matches[0] != want {
		t.Fatalf("expected match[]=%q, got %q", want, matches[0])
	}
	// 原 start 参数应保留。
	if out.Get("start") != "100" {
		t.Fatalf("expected start=100 retained, got %q", out.Get("start"))
	}
}

// TestInjectTenantLabelValues_TenantIDLabel 查询 tenant_id 自身值不应注入过滤。
func TestInjectTenantLabelValues_TenantIDLabel(t *testing.T) {
	f := NewTenantFilter()
	in := url.Values{}
	out := f.InjectTenantLabelValues(in, "tenant_id", "acme")
	if vals := out["match[]"]; len(vals) != 0 {
		t.Fatalf("expected no match[] for tenant_id label, got %v", vals)
	}
}

// TestInjectTenantLabelValues_OtherLabel 其他标签应注入过滤。
func TestInjectTenantLabelValues_OtherLabel(t *testing.T) {
	f := NewTenantFilter()
	in := url.Values{}
	out := f.InjectTenantLabelValues(in, "job", "acme")
	matches := out["match[]"]
	if len(matches) != 1 {
		t.Fatalf("expected 1 match[], got %d", len(matches))
	}
	const want = `{tenant_id="acme"}`
	if matches[0] != want {
		t.Fatalf("expected match[]=%q, got %q", want, matches[0])
	}
}

// TestPlatformQuery_NoFilter 平台方请求应原样返回 PromQL。
func TestPlatformQuery_NoFilter(t *testing.T) {
	f := NewTenantFilter()
	const promql = `up{job="api"}`
	if got := f.PlatformQuery(promql); got != promql {
		t.Fatalf("platform query should be no-op, got %q", got)
	}
}

// TestPlatformParams_NoFilter 平台方请求应原样返回 params。
func TestPlatformParams_NoFilter(t *testing.T) {
	f := NewTenantFilter()
	in := url.Values{"start": []string{"100"}}
	out := f.PlatformParams(in)
	if out.Get("start") != "100" {
		t.Fatalf("expected start=100, got %q", out.Get("start"))
	}
}
