package service

import (
	"net/url"
	"testing"
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

// TestInjectTenantQuery_AlreadyContainsTenantID 已包含 tenant_id 不应重复注入。
func TestInjectTenantQuery_AlreadyContainsTenantID(t *testing.T) {
	f := NewTenantFilter()
	const promql = `up{tenant_id="abc"}`
	if got := f.InjectTenantQuery(promql, "abc"); got != promql {
		t.Fatalf("already contains tenant_id should be no-op, got %q", got)
	}
}

// TestInjectTenantQuery_SimpleVector 简单向量应注入 AND 过滤。
func TestInjectTenantQuery_SimpleVector(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("up", "tenant-1")
	const want = `(up) AND {tenant_id="tenant-1"}`
	if got != want {
		t.Fatalf("unexpected injected PromQL: got %q want %q", got, want)
	}
}

// TestInjectTenantQuery_FunctionCall 函数调用 PromQL 应被括号包裹后注入。
func TestInjectTenantQuery_FunctionCall(t *testing.T) {
	f := NewTenantFilter()
	got := f.InjectTenantQuery("rate(http_requests_total[5m])", "acme")
	const want = `(rate(http_requests_total[5m])) AND {tenant_id="acme"}`
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
