package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestGlobalSearch_ReturnsModeAndResults 验证全局检索返回 {mode, results} 契约。
func TestGlobalSearch_ReturnsModeAndResults(t *testing.T) {
	r, _ := setupRouterWithCollection(t, "docs", 256)

	// 插入一条符合 256 维的数据
	vec := make([]float32, 256)
	vec[0] = 1.0
	insert := doRequest(t, r, http.MethodPost, "/api/v1/collections/docs/vectors", map[string]interface{}{
		"vectors": []map[string]interface{}{
			{"id": "doc-1", "vector": vec, "metadata": map[string]interface{}{"title": "数据引擎"}},
		},
	})
	require.Equal(t, http.StatusCreated, insert.Code, insert.Body.String())

	w := doRequest(t, r, http.MethodPost, "/api/v1/vector/search", map[string]interface{}{
		"query": "数据引擎",
		"topK":  3,
	})
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())

	var resp struct {
		Mode    string `json:"mode"`
		Results []struct {
			ID         string                 `json:"id"`
			Collection string                 `json:"collection"`
			Payload    map[string]interface{} `json:"payload"`
		} `json:"results"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &resp))
	assert.Equal(t, "hash-fallback", resp.Mode)
	assert.NotEmpty(t, resp.Results)
	assert.Equal(t, "doc-1", resp.Results[0].ID)
	assert.Equal(t, "docs", resp.Results[0].Collection)
}

// TestGlobalSearch_EmptyQuery 验证空查询返回 400。
func TestGlobalSearch_EmptyQuery(t *testing.T) {
	r, _ := setupRouter()
	w := doRequest(t, r, http.MethodPost, "/api/v1/vector/search", map[string]interface{}{
		"query": "",
	})
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestGlobalSearch_BadJSON 验证非法请求体返回 400。
func TestGlobalSearch_BadJSON(t *testing.T) {
	r, _ := setupRouter()
	w := doRequest(t, r, http.MethodPost, "/api/v1/vector/search", "not-json")
	assert.Equal(t, http.StatusBadRequest, w.Code)
}
