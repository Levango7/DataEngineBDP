package cmd

import (
	"fmt"
	"text/tabwriter"

	"github.com/spf13/cobra"
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

// runStatus 执行 status 子命令逻辑。
func runStatus(cmd *cobra.Command, args []string) error {
	// MVP 阶段：调用各组件健康端点，此处返回模拟结果。
	components := []struct {
		name   string
		status string
		url    string
	}{
		{"encaps-layer", "healthy", "/api/v1/health"},
		{"sql-gateway", "healthy", "/api/v1/health"},
		{"rule-engine", "healthy", "/api/v1/health"},
		{"catalog", "healthy", "/api/v1/health"},
	}

	w := tabwriter.NewWriter(cmd.OutOrStdout(), 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "COMPONENT\tSTATUS\tENDPOINT")
	for _, c := range components {
		fmt.Fprintf(w, "%s\t%s\t%s\n", c.name, c.status, c.url)
	}
	return w.Flush()
}
