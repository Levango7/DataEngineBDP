package embedding

import (
	"context"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"
)

func l2dist(a, b []float32) float64 {
	var s float64
	for i := range a {
		d := float64(a[i] - b[i])
		s += d * d
	}
	return math.Sqrt(s)
}

func TestHashEmbedder_SimilarTextsClose(t *testing.T) {
	e := &HashEmbedder{}
	ctx := context.Background()
	v1, err := e.Embed(ctx, []string{"数据引擎大数据平台"})
	if err != nil {
		t.Fatalf("Embed 失败: %v", err)
	}
	v2, err := e.Embed(ctx, []string{"数据引擎大数据平台"})
	if err != nil {
		t.Fatalf("Embed 失败: %v", err)
	}
	v3, err := e.Embed(ctx, []string{"农业灌溉物联网传感器"})
	if err != nil {
		t.Fatalf("Embed 失败: %v", err)
	}
	// 相同文本 → 向量一致；不同文本 → 距离明显更大
	if l2dist(v1[0], v2[0]) != 0 {
		t.Fatalf("相同文本应产生相同向量，实际距离 %v", l2dist(v1[0], v2[0]))
	}
	close := l2dist(v1[0], v2[0])
	far := l2dist(v1[0], v3[0])
	if far <= close {
		t.Fatalf("不同文本距离应大于相同文本距离: close=%v far=%v", close, far)
	}
	if e.Mode() != "hash-fallback" {
		t.Fatalf("Mode 应为 hash-fallback，实际 %s", e.Mode())
	}
}

func TestHashEmbedder_SharedTokensCloser(t *testing.T) {
	e := &HashEmbedder{}
	ctx := context.Background()
	base, _ := e.Embed(ctx, []string{"数据质量管理与血缘分析"})
	sameToken, _ := e.Embed(ctx, []string{"数据质量"})
	diff, _ := e.Embed(ctx, []string{"Web IDE 在线开发与调度"})
	if !(l2dist(base[0], sameToken[0]) < l2dist(base[0], diff[0])) {
		t.Fatalf("共享词元文本应比无关文本更接近")
	}
}

func TestHTTPEmbedder_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			t.Errorf("缺少 Authorization 头")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"data":[{"embedding":[0.1,0.2,0.3]}]}`))
	}))
	defer srv.Close()

	e := &HTTPEmbedder{API: srv.URL, Key: "test-key"}
	vectors, err := e.Embed(context.Background(), []string{"hello"})
	if err != nil {
		t.Fatalf("Embed 失败: %v", err)
	}
	if len(vectors) != 1 || len(vectors[0]) != 3 {
		t.Fatalf("向量维度不符: %v", vectors)
	}
	if e.Mode() != "semantic" {
		t.Fatalf("Mode 应为 semantic")
	}
}

func TestHTTPEmbedder_NonOKStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("boom"))
	}))
	defer srv.Close()

	e := &HTTPEmbedder{API: srv.URL}
	if _, err := e.Embed(context.Background(), []string{"hello"}); err == nil {
		t.Fatalf("非 2xx 应返回错误")
	}
}

func TestNew_WithoutAPI_FallsBack(t *testing.T) {
	t.Setenv("VECTOR_EMBEDDING_API", "")
	e := New()
	if e.Mode() != "hash-fallback" {
		t.Fatalf("未配置 API 应降级 hash-fallback，实际 %s", e.Mode())
	}
}
