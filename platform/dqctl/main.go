// Package main 是 dqctl 命令行工具的入口。
//
// dqctl 是数据引擎大数据平台的声明式资源管理命令行工具，提供 init/apply/query/status 等子命令，
// 用于初始化配置、应用声明式资源、执行 SQL 查询以及查询平台组件健康状态。
package main

import (
	"fmt"
	"os"

	"github.com/Levango7/DataEngineBDP/dqctl/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		fmt.Fprintf(os.Stderr, "dqctl 执行失败: %v\n", err)
		os.Exit(1)
	}
}
