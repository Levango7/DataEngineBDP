// Package handler - health_handler.go 实现健康检查与Token签发API。
package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"github.com/shuqing/infra-provider-baremetal/src/internal/middleware"
	"github.com/shuqing/infra-provider-baremetal/src/internal/model"
)

// HealthHandler 健康检查与系统API handler
type HealthHandler struct {
	auth    *middleware.JWTAuthenticator
	version string
	logger  *logrus.Entry
}

// NewHealthHandler 创建健康检查handler
func NewHealthHandler(auth *middleware.JWTAuthenticator, version string, logger *logrus.Entry) *HealthHandler {
	return &HealthHandler{auth: auth, version: version, logger: logger}
}

// RegisterRoutes 注册系统路由(健康检查、登录签发Token)
func (h *HealthHandler) RegisterRoutes(rg *gin.RouterGroup, engine *gin.Engine) {
	// 健康检查与指标(无需鉴权)
	engine.GET("/healthz", h.Healthz)
	engine.GET("/readyz", h.Readyz)
	engine.GET("/version", h.Version)

	// 登录签发Token
	rg.POST("/auth/login", h.Login)
	rg.POST("/auth/refresh", h.Refresh)
}

// Healthz 存活探针
// GET /healthz
func (h *HealthHandler) Healthz(c *gin.Context) {
	c.JSON(http.StatusOK, model.HealthResponse{
		Status:    "ok",
		Version:   h.version,
		Timestamp: time.Now(),
	})
}

// Readyz 就绪探针
// GET /readyz
func (h *HealthHandler) Readyz(c *gin.Context) {
	c.JSON(http.StatusOK, model.HealthResponse{
		Status:    "ready",
		Version:   h.version,
		Timestamp: time.Now(),
	})
}

// Version 版本信息
// GET /version
func (h *HealthHandler) Version(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"version":    h.version,
		"name":       "infra-provider-baremetal",
		"build_time": time.Now().Format(time.RFC3339),
	})
}

// LoginRequest 登录请求
type LoginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

// LoginResponse 登录响应
type LoginResponse struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	Username  string    `json:"username"`
	Role      string    `json:"role"`
}

// Login 签发JWT Token
// POST /api/v1/auth/login
//
// 简化实现: 接受任意非空username/password，role根据username推断。
// 生产环境应接入LDAP/OIDC。
func (h *HealthHandler) Login(c *gin.Context) {
	var req LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, model.APIResponse{
			Code:    http.StatusBadRequest,
			Message: "请求体解析失败",
			Data:    err.Error(),
		})
		return
	}

	role := "user"
	if req.Username == "admin" {
		role = "admin"
	}

	token, err := h.auth.GenerateToken(req.Username, role)
	if err != nil {
		h.logger.WithError(err).Error("签发Token失败")
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "签发Token失败",
		})
		return
	}

	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "登录成功",
		Data: LoginResponse{
			Token:     token,
			ExpiresAt: time.Now().Add(24 * time.Hour),
			Username:  req.Username,
			Role:      role,
		},
	})
}

// Refresh 刷新Token
// POST /api/v1/auth/refresh
func (h *HealthHandler) Refresh(c *gin.Context) {
	username, _ := c.Get("username")
	role, _ := c.Get("role")

	userStr, _ := username.(string) //nolint:errcheck // gin上下文值类型断言，空值已由后续空串判断覆盖
	roleStr, _ := role.(string)     //nolint:errcheck // gin上下文值类型断言，空值已由后续空串判断覆盖
	if userStr == "" {
		c.JSON(http.StatusUnauthorized, model.APIResponse{
			Code:    http.StatusUnauthorized,
			Message: "未提供有效Token",
		})
		return
	}

	token, err := h.auth.GenerateToken(userStr, roleStr)
	if err != nil {
		c.JSON(http.StatusInternalServerError, model.APIResponse{
			Code:    http.StatusInternalServerError,
			Message: "刷新Token失败",
		})
		return
	}

	c.JSON(http.StatusOK, model.APIResponse{
		Code:    http.StatusOK,
		Message: "刷新成功",
		Data: LoginResponse{
			Token:     token,
			ExpiresAt: time.Now().Add(24 * time.Hour),
			Username:  userStr,
			Role:      roleStr,
		},
	})
}
