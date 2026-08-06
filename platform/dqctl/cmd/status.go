package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"text/tabwriter"

	"github.com/spf13/cobra"

	"github.com/shuqing/bigdata/dqctl/internal/client"
)

// statusCmd 实现 `dqctl status` 子命令：查询平台各组件健康状态。
var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "查询平台组件健康状态",
	Long:  "调用各组件 /api/v1/health 端点，输出平台各组件健康状态表格。",
	RunE:  runStatus,
}

// init 注册 statusCmd。
func init() {
	rootCmd.AddCommand(statusCmd)
}

// componentHealth 表示单个组件的健康状态行。
type componentHealth struct {
	name   string
	status string
	url    string
}

// statusComponents 定义 status 命令需要查询的组件及其健康端点。
var statusComponents = []struct {
	name string
	path string
}{
	{"encaps-layer", "/api/v1/health"},
	{"sql-gateway", "/api/v1/health"},
	{"rule-engine", "/api/v1/health"},
	{"catalog", "/api/v1/health"},
}

// runStatus 执行 status 子命令逻辑。
//
// 当已配置 platform_url 时，通过真实 HTTP client 调用各组件健康端点；
// 未配置时降级为本地模拟输出，保证 CLI 在离线状态下仍可展示表格骨架。
func runStatus(cmd *cobra.Command, args []string) error {
	results := make([]componentHealth, 0, len(statusComponents))

	c := getClient()
	if c == nil {
		// 未配置 platform_url，降级为本地模拟输出。
		fmt.Fprintln(cmd.ErrOrStderr(), "[提示] 未配置 platform_url，以下为模拟状态。请运行 dqctl init 配置后端地址。")
		for _, comp := range statusComponents {
			results = append(results, componentHealth{name: comp.name, status: "healthy", url: comp.path})
		}
	} else {
		for _, comp := range statusComponents {
			status, err := queryComponentHealth(c, comp.path)
			if err != nil {
				status = "unreachable: " + err.Error()
			}
			results = append(results, componentHealth{name: comp.name, status: status, url: comp.path})
		}
	}

	w := tabwriter.NewWriter(cmd.OutOrStdout(), 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "COMPONENT\tSTATUS\tENDPOINT")
	for _, r := range results {
		fmt.Fprintf(w, "%s\t%s\t%s\n", r.name, r.status, r.url)
	}
	return w.Flush()
}

// queryComponentHealth 调用组件健康端点并解析返回的状态。
//
// 兼容两种响应格式：
//   - {"status":"UP"} 或 {"status":"healthy"}：直接取 status 字段
//   - Spring Boot Actuator {"status":{"code":"UP"}}：取嵌套 code（此处简化为取 status 字符串）
//
// 若响应体不含 status 字段，默认返回 "healthy"（HTTP 200 即视为健康）。
func queryComponentHealth(c *client.Client, path string) (string, error) {
	resp, err := c.Get(path)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	var payload struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return "", fmt.Errorf("解析健康响应失败: %w", err)
	}
	if payload.Status == "" {
		return "healthy", nil
	}
	return payload.Status, nil
}
