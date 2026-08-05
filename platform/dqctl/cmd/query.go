package cmd

import (
	"fmt"

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
func runQuery(cmd *cobra.Command, args []string) error {
	sql := args[0]

	if flagVerbose {
		fmt.Printf("[verbose] engine=%s output=%s sql=%s\n", queryEngine, queryOutput, sql)
	}

	// MVP 阶段：调用 SQL 网关执行查询，此处返回模拟结果。
	fmt.Printf("查询引擎: %s\n", queryEngine)
	fmt.Printf("SQL: %s\n", sql)
	fmt.Printf("输出格式: %s\n", queryOutput)
	fmt.Println("(MVP 阶段：查询结果为模拟数据)")
	return nil
}
