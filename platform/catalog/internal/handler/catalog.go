package handler

import (
	"crypto/rand"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/store"
)

// CatalogHandler 处理 Catalog 的 REST API 请求。
type CatalogHandler struct {
	store store.Store
}

// NewCatalogHandler 创建一个新的 Catalog handler。
func NewCatalogHandler(s store.Store) *CatalogHandler {
	return &CatalogHandler{store: s}
}

// RegisterRoutes 在给定的 router group 上注册所有 Catalog 路由。
func (h *CatalogHandler) RegisterRoutes(rg *gin.RouterGroup) {
	rg.GET("/databases", h.ListDatabases)
	rg.POST("/databases", h.CreateDatabase)
	rg.GET("/databases/:id", h.GetDatabase)
	rg.DELETE("/databases/:id", h.DeleteDatabase)

	rg.GET("/tables", h.ListTables)
	rg.POST("/tables", h.CreateTable)
	rg.GET("/tables/:id", h.GetTable)
	rg.PUT("/tables/:id", h.UpdateTable)
	rg.DELETE("/tables/:id", h.DeleteTable)
}

// newUUID 生成一个 RFC 4122 v4 UUID 字符串，仅依赖标准库。
func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// 极端情况下 crypto/rand 失败，退化为基于时间的占位 ID。
		return fmt.Sprintf("fallback-%d", time.Now().UnixNano())
	}
	// 设置 version 4 与 variant 位。
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// ============ Database 端点 ============

// ListDatabases 列出所有数据库。
// GET /api/v1/catalog/databases
func (h *CatalogHandler) ListDatabases(c *gin.Context) {
	dbs, err := h.store.ListDatabases()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"data": dbs, "total": len(dbs)})
}

// CreateDatabase 创建一个数据库。
// POST /api/v1/catalog/databases
func (h *CatalogHandler) CreateDatabase(c *gin.Context) {
	var db model.Database
	if err := c.ShouldBindJSON(&db); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(db.Name) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "database name is required"})
		return
	}
	// 若未提供 ID，则生成 UUID v4。
	if db.ID == "" {
		db.ID = newUUID()
	}
	now := time.Now().UTC()
	if db.CreatedAt.IsZero() {
		db.CreatedAt = now
	}
	if err := h.store.CreateDatabase(&db); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, db)
}

// GetDatabase 获取一个数据库。
// GET /api/v1/catalog/databases/{id}
func (h *CatalogHandler) GetDatabase(c *gin.Context) {
	id := c.Param("id")
	db, err := h.store.GetDatabase(id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, db)
}

// DeleteDatabase 删除一个数据库。
// DELETE /api/v1/catalog/databases/{id}
func (h *CatalogHandler) DeleteDatabase(c *gin.Context) {
	id := c.Param("id")
	if err := h.store.DeleteDatabase(id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusNoContent, nil)
}

// ============ Table 端点 ============

// ListTables 列出表。可选 query 参数 database 过滤库名。
// GET /api/v1/catalog/tables?database={name}
func (h *CatalogHandler) ListTables(c *gin.Context) {
	dbName := c.Query("database")
	tables, err := h.store.ListTables(dbName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"data": tables, "total": len(tables)})
}

// CreateTable 创建一张表。
// POST /api/v1/catalog/tables
func (h *CatalogHandler) CreateTable(c *gin.Context) {
	var t model.Table
	if err := c.ShouldBindJSON(&t); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if strings.TrimSpace(t.DatabaseName) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "databaseName is required"})
		return
	}
	if strings.TrimSpace(t.TableName) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "tableName is required"})
		return
	}
	if len(t.Columns) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "columns must not be empty"})
		return
	}
	if t.ID == "" {
		t.ID = newUUID()
	}
	now := time.Now().UTC()
	if t.CreatedAt.IsZero() {
		t.CreatedAt = now
	}
	t.UpdatedAt = now
	if err := h.store.CreateTable(&t); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, t)
}

// GetTable 获取一张表。
// GET /api/v1/catalog/tables/{id}
func (h *CatalogHandler) GetTable(c *gin.Context) {
	id := c.Param("id")
	t, err := h.store.GetTable(id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, t)
}

// UpdateTable 更新一张表。
// PUT /api/v1/catalog/tables/{id}
func (h *CatalogHandler) UpdateTable(c *gin.Context) {
	id := c.Param("id")
	var t model.Table
	if err := c.ShouldBindJSON(&t); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	// 路径参数 id 优先于 body 中的 id，保证幂等。
	t.ID = id
	t.UpdatedAt = time.Now().UTC()
	// 若原表存在且未在 body 中提供 createdAt，则保留原值。
	if t.CreatedAt.IsZero() {
		if existing, err := h.store.GetTable(id); err == nil {
			t.CreatedAt = existing.CreatedAt
		} else {
			t.CreatedAt = t.UpdatedAt
		}
	}
	if err := h.store.UpdateTable(&t); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, t)
}

// DeleteTable 删除一张表。
// DELETE /api/v1/catalog/tables/{id}
func (h *CatalogHandler) DeleteTable(c *gin.Context) {
	id := c.Param("id")
	if err := h.store.DeleteTable(id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusNoContent, nil)
}
