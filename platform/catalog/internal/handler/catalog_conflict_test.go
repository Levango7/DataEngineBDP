package handler

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/store"
)

type uniqueViolateStore struct {
	mockStore
}

func (m *uniqueViolateStore) CreateDatabase(db *model.Database) error {
	return fmt.Errorf("%w: database %s (UNIQUE constraint failed)", store.ErrAlreadyExists, db.ID)
}

func (m *uniqueViolateStore) CreateTable(t *model.Table) error {
	return fmt.Errorf("%w: table %s (UNIQUE constraint failed)", store.ErrAlreadyExists, t.ID)
}

func setupConflictRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	h := NewCatalogHandler(&uniqueViolateStore{mockStore: *newMockStore()})
	r := gin.New()
	rg := r.Group("/api/v1/catalog")
	rg.Use(func(c *gin.Context) {
		c.Set("tenantId", "t1")
		c.Next()
	})
	h.RegisterRoutes(rg)
	return r
}

func TestCreateDatabase_ConcurrentWindowConflict409(t *testing.T) {
	r := setupConflictRouter()

	body := map[string]string{"id": "cw-001", "name": "cw_db"}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/databases", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusConflict, w.Code)
	assert.Contains(t, w.Body.String(), "already exists")
}

func TestCreateTable_ConcurrentWindowConflict409(t *testing.T) {
	r := setupConflictRouter()

	body := map[string]interface{}{
		"id":           "cw-tbl",
		"databaseName": "db1",
		"tableName":    "users",
		"columns": []map[string]interface{}{
			{"name": "id", "type": "BIGINT"},
		},
	}
	jsonBody, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodPost, "/api/v1/catalog/tables", bytes.NewReader(jsonBody))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusConflict, w.Code)
	assert.Contains(t, w.Body.String(), "already exists")
}
