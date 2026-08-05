package middleware

import (
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	// requests_total 记录 HTTP 请求总数。
	requestsTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total number of HTTP requests",
		},
		[]string{"method", "path", "status"},
	)

	// request_duration_seconds 记录 HTTP 请求延迟（秒）。
	requestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request duration in seconds",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "path"},
	)

	// in_flight_requests 记录当前正在处理的请求数。
	inFlightRequests = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "http_in_flight_requests",
			Help: "Current number of in-flight HTTP requests",
		},
		[]string{"method"},
	)
)

func init() {
	prometheus.MustRegister(requestsTotal)
	prometheus.MustRegister(requestDuration)
	prometheus.MustRegister(inFlightRequests)
}

// MetricsMiddleware 是 Prometheus metrics 中间件。
// 记录请求总数、请求延迟和活跃请求数。
func MetricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		method := c.Request.Method
		path := c.FullPath()
		if path == "" {
			path = "unmatched"
		}

		// 增加活跃请求计数。
		inFlightRequests.WithLabelValues(method).Inc()
		defer inFlightRequests.WithLabelValues(method).Dec()

		// 记录请求开始时间。
		timer := prometheus.NewTimer(requestDuration.WithLabelValues(method, path))
		defer timer.ObserveDuration()

		c.Next()

		// 记录请求总数。
		status := strconv.Itoa(c.Writer.Status())
		requestsTotal.WithLabelValues(method, path, status).Inc()
	}
}

// MetricsHandler 返回 /metrics 端点的 HandlerFunc。
func MetricsHandler() gin.HandlerFunc {
	h := promhttp.Handler()
	return func(c *gin.Context) {
		h.ServeHTTP(c.Writer, c.Request)
	}
}
