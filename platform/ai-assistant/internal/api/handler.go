package api

import (
	"net/http"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/service"

	"github.com/gin-gonic/gin"
)

// AssistantHandler AI 助手 HTTP handler。
type AssistantHandler struct {
	svc   *service.AssistantService
	proxy *service.DownstreamProxy
}

// NewAssistantHandler 创建 handler。
func NewAssistantHandler(svc *service.AssistantService, proxy *service.DownstreamProxy) *AssistantHandler {
	return &AssistantHandler{svc: svc, proxy: proxy}
}

// Health 健康检查端点（匿名注册，供 K8s 探针/Docker HEALTHCHECK）。
func Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "UP", "service": "ai-assistant"})
}

// resolveTenant 租户强制：校验请求体 tenantId 与 JWT claim 一致，
// 不一致返回 403；请求体为空时回填 claim 值后透传给下游 sql-gateway。
func resolveTenant(c *gin.Context, bodyTenantID string) (string, bool) {
	claim := c.GetString("tenantId")
	if bodyTenantID == "" || bodyTenantID == claim {
		return claim, true
	}
	c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "tenantId 与认证租户不一致"})
	return "", false
}

// RegisterRoutes 在已挂认证中间件的路由组上注册 /ai-assistant 业务端点。
func RegisterRoutes(g *gin.RouterGroup, svc *service.AssistantService, cfg *config.Config) {
	proxy := service.NewDownstreamProxy(cfg)
	h := NewAssistantHandler(svc, proxy)

	{
		// 对话（非流式，聚合 NL→SQL→执行→回复）
		g.POST("/chat", h.chat)
		// 对话（SSE 流式，对齐前端 chatStream）
		g.POST("/chat/stream", h.chatStream)

		// SQL 生成 / 执行 / 图表推荐 / 数据解读 / 仪表盘（P1 骨架：返回结构化占位或调用下游）
		g.POST("/nl2sql", h.nl2sql)
		g.POST("/execute", h.execute)
		g.POST("/recommend-chart", h.recommendChart)
		g.POST("/summarize", h.summarize)
		g.POST("/dashboard", h.dashboard)

		// 会话
		g.GET("/sessions", h.listSessions)
		g.POST("/sessions", h.createSession)
		g.GET("/sessions/:id", h.getSession)
		g.DELETE("/sessions/:id", h.deleteSession)

		// 会话管理增强（Sprint 2.2：对齐前端 ai-assistant.ts）
		g.POST("/sessions/:id/pin", h.pinSession)
		g.POST("/sessions/:id/rename", h.renameSession)

		// 消息反馈（Sprint 2.2）
		g.POST("/messages/:id/feedback", h.messageFeedback)

		// 示例提问（Sprint 2.2，空状态引导）
		g.GET("/example-prompts", h.examplePrompts)

		// Superset 数据源（Sprint 2.2，P1 骨架：静态清单，后续接 llm-gateway 动态拉取）
		g.GET("/superset/datasources", h.listSupersetDatasources)
	}
}

// chat POST /chat
func (h *AssistantHandler) chat(c *gin.Context) {
	var req service.ChatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	if tenantID, ok := resolveTenant(c, req.TenantID); ok {
		req.TenantID = tenantID
	} else {
		return
	}
	resp, err := h.svc.Chat(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, resp)
}

// nl2sql POST /nl2sql
func (h *AssistantHandler) nl2sql(c *gin.Context) {
	var req struct {
		Query     string `json:"query"`
		Dialect   string `json:"dialect"`
		SessionID string `json:"sessionId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	out, err := h.proxy.Nl2Sql(c.Request.Context(), req.Query, req.Dialect)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"sql":        out.SQL,
		"dialect":    out.Dialect,
		"tables":     out.Tables,
		"confidence": out.Confidence,
	})
}

// execute POST /execute
func (h *AssistantHandler) execute(c *gin.Context) {
	var req struct {
		SQL      string `json:"sql"`
		Dialect  string `json:"dialect"`
		TenantID string `json:"tenantId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	tenantID, ok := resolveTenant(c, req.TenantID)
	if !ok {
		return
	}
	out, err := h.proxy.ExecuteSql(c.Request.Context(), req.SQL, req.Dialect, tenantID)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"status":  out.Status,
		"columns": out.Columns,
		"rows":    out.Rows,
	})
}

// recommendChart POST /recommend-chart（P1：基于列类型返回建议）
func (h *AssistantHandler) recommendChart(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"recommendations": []gin.H{{
			"chartType": "bar",
			"reason":    "基于查询结果的类别列推荐柱状图",
			"xField":    "col_1",
			"yField":    "col_2",
		}},
	})
}

// summarize POST /summarize（P1：规则摘要）
func (h *AssistantHandler) summarize(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"summary": "查询执行完成，返回了 N 行数据（规则摘要，LLM 润色见 P1）。",
		"metrics": []gin.H{},
	})
}

// dashboard POST /dashboard（P1：占位，后续接 Superset）
func (h *AssistantHandler) dashboard(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"dashboardId": "new",
		"url":         "",
		"embedUrl":    "",
		"createdAt":   "",
	})
}

// listSessions GET /sessions
func (h *AssistantHandler) listSessions(c *gin.Context) {
	sessions, err := h.svc.ListSessions(50)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"sessions": sessions})
}

// createSession POST /sessions
func (h *AssistantHandler) createSession(c *gin.Context) {
	var req struct {
		Locale string `json:"locale"`
	}
	_ = c.ShouldBindJSON(&req)
	sess, err := h.svc.CreateSession(req.Locale)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, sess)
}

// getSession GET /sessions/:id
func (h *AssistantHandler) getSession(c *gin.Context) {
	sess, msgs, err := h.svc.GetSession(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "会话不存在"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"session": sess, "messages": msgs})
}

// deleteSession DELETE /sessions/:id
func (h *AssistantHandler) deleteSession(c *gin.Context) {
	if err := h.svc.DeleteSession(c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"deleted": true})
}

// pinSession POST /sessions/:id/pin（Sprint 2.2）body: {"pinned": true|false}
func (h *AssistantHandler) pinSession(c *gin.Context) {
	var req struct {
		Pinned bool `json:"pinned"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	if err := h.svc.PinSession(c.Param("id"), req.Pinned); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// renameSession POST /sessions/:id/rename（Sprint 2.2）body: {"title": "新标题"}
func (h *AssistantHandler) renameSession(c *gin.Context) {
	var req struct {
		Title string `json:"title"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Title == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "title 不能为空"})
		return
	}
	if err := h.svc.RenameSession(c.Param("id"), req.Title); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// messageFeedback POST /messages/:id/feedback（Sprint 2.2）body: {"feedback": "like"|"dislike"|null}
func (h *AssistantHandler) messageFeedback(c *gin.Context) {
	var req struct {
		Feedback *string `json:"feedback"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误: " + err.Error()})
		return
	}
	fb := ""
	if req.Feedback != nil {
		switch *req.Feedback {
		case "like", "dislike":
			fb = *req.Feedback
		default:
			c.JSON(http.StatusBadRequest, gin.H{"error": "feedback 仅允许 like/dislike/null"})
			return
		}
	}
	if err := h.svc.SetMessageFeedback(c.Param("id"), fb); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// examplePrompts GET /example-prompts?locale=zh|en（Sprint 2.2）
func (h *AssistantHandler) examplePrompts(c *gin.Context) {
	locale := c.DefaultQuery("locale", "zh")
	c.JSON(http.StatusOK, h.svc.ExamplePrompts(locale))
}

// listSupersetDatasources GET /superset/datasources（Sprint 2.2，P1 骨架）
//
// 前端 createDashboard 的数据源下拉需要此清单；当前为静态骨架，
// 后续接入 llm-gateway/Superset API 动态拉取（与 dashboard 端点同一节奏演进）。
func (h *AssistantHandler) listSupersetDatasources(c *gin.Context) {
	c.JSON(http.StatusOK, []gin.H{
		{"id": "ds-orders", "name": "订单宽表 (dwd_orders)", "type": "table"},
		{"id": "ds-tenants", "name": "租户维表 (dim_tenants)", "type": "table"},
		{"id": "ds-cost", "name": "存储成本 (finops_storage_cost)", "type": "table"},
		{"id": "ds-quality", "name": "质量评分 (dq_scores)", "type": "table"},
	})
}
