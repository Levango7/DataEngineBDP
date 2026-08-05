package middleware

import (
	"context"
	"log/slog"
	"os"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// InitTracer 初始化 OpenTelemetry TracerProvider，配置 OTLP gRPC exporter。
// 返回 shutdown 函数，调用方应在程序退出时调用以刷新未发送的 span。
func InitTracer(serviceName string) (func(context.Context) error, error) {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "localhost:4317"
	}

	exporter, err := otlptrace.New(
		context.Background(),
		otlptracegrpc.NewClient(
			otlptracegrpc.WithEndpoint(endpoint),
			otlptracegrpc.WithInsecure(),
		),
	)
	if err != nil {
		slog.Warn("failed to create OTLP trace exporter, tracing disabled", "error", err)
		// 返回空 shutdown，不阻止启动。
		return func(_ context.Context) error { return nil }, nil
	}

	res, err := sdkresource.New(
		context.Background(),
		sdkresource.WithAttributes(
			attribute.String("service.name", serviceName),
		),
	)
	if err != nil {
		slog.Warn("failed to create OTel resource", "error", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)

	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	return tp.Shutdown, nil
}

// TracingMiddleware 是 OpenTelemetry 追踪中间件。
// 从请求 Header 提取 trace context，创建 span 记录请求处理。
func TracingMiddleware(serviceName string) gin.HandlerFunc {
	tracer := otel.Tracer(serviceName)
	propagator := otel.GetTextMapPropagator()

	return func(c *gin.Context) {
		// 从请求 Header 提取 trace context。
		ctx := propagator.Extract(c.Request.Context(), propagation.HeaderCarrier(c.Request.Header))

		// 创建 span。
		spanName := c.Request.Method + " " + c.FullPath()
		if spanName == "" {
			spanName = c.Request.Method + " " + c.Request.URL.Path
		}
		ctx, span := tracer.Start(ctx, spanName)
		defer span.End()

		// 将带有 trace context 的 ctx 写回 request。
		c.Request = c.Request.WithContext(ctx)

		// 提取 traceId 写入 gin.Context，供日志中间件使用。
		traceId := trace.SpanContextFromContext(ctx).TraceID().String()
		c.Set("traceId", traceId)

		c.Next()

		// 记录 HTTP 状态码到 span。
		span.SetAttributes(attribute.Int("http.status_code", c.Writer.Status()))
	}
}
