package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"github.com/spf13/cobra"
)

// queryEngine 是 --engine flag 指定的查询引擎（trino/doris）。
var queryEngine string

// queryOutput 是 --output flag 指定的输出格式（json/table/csv）。
var queryOutput string

// queryCmd 实现 `dqctl query <sql>` 子命令：通过 SQL 网关执行 SQL 查询。
var queryCmd = &cobra.Command{
	Use:   "query [sql]",
	Short: "执行 SQL 查询",
	Long:  "通过 SQL 网关执行 SQL 查询，支持指定引擎 (trino/doris) 与输出格式 (json/table/csv)。",
	Args:  cobra.MinimumNArgs(1),
	RunE:  runQuery,
}

// init 注册 queryCmd 及其 flag。
func init() {
	queryCmd.Flags().StringVar(&queryEngine, "engine", "trino", "查询引擎 (trino/doris)")
	queryCmd.Flags().StringVar(&queryOutput, "output", "table", "输出格式 (json/table/csv)")
	rootCmd.AddCommand(queryCmd)
}

// runQuery 执行 query 子命令逻辑。
//
// 当已配置 platform_url 时，通过 SQL 网关（POST /api/v1/sql/execute）执行查询并输出结果；
// 未配置时降级为本地模拟输出，保证 CLI 在离线状态下仍可展示查询元信息。
func runQuery(cmd *cobra.Command, args []string) error {
	sql := args[0]

	if flagVerbose {
		fmt.Printf("[verbose] engine=%s output=%s sql=%s\n", queryEngine, queryOutput, sql)
	}

	// 输出查询元信息（用户可见的 CLI 输出格式保持不变）。
	fmt.Printf("查询引擎: %s\n", queryEngine)
	fmt.Printf("SQL: %s\n", sql)
	fmt.Printf("输出格式: %s\n", queryOutput)

	c := getClient()
	if c == nil {
		// 未配置 platform_url，降级为本地模拟输出。
		fmt.Fprintln(cmd.ErrOrStderr(), "[提示] 未配置 platform_url，以下为模拟结果。请运行 dqctl init 配置后端地址。")
		fmt.Println("(未配置后端地址，查询结果为模拟数据)")
		return nil
	}

	// 调用 SQL 网关执行查询。
	resp, err := c.Post("/api/v1/sql/execute", map[string]interface{}{
		"engine": queryEngine,
		"sql":    sql,
		"output": queryOutput,
	})
	if err != nil {
		return fmt.Errorf("调用 SQL 网关失败: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= http.StatusBadRequest {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("SQL 网关返回错误: HTTP %d, body=%s", resp.StatusCode, string(body))
	}

	// 输出查询结果。
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("读取查询响应失败: %w", err)
	}
	printQueryResult(body)
	return nil
}

// printQueryResult 解析并输出 SQL 网关返回的查询结果。
//
// 兼容 {"data": ...} 包装格式与裸 JSON；非 JSON 响应原样输出。
func printQueryResult(body []byte) {
	var wrapped struct {
		Data interface{} `json:"data"`
	}
	if err := json.Unmarshal(body, &wrapped); err == nil && wrapped.Data != nil {
		encoded, err := json.Marshal(wrapped.Data)
		if err == nil {
			fmt.Printf("结果: %s\n", string(encoded))
			return
		}
	}
	fmt.Printf("结果: %s\n", string(body))
}
