package cmd

import (
	"fmt"
	"runtime"

	"github.com/spf13/cobra"
)

// 以下变量可通过 ldflags 在构建时注入，默认值为 dev。
var (
	version   = "0.1.0"
	commit    = "dev"
	goVersion = runtime.Version()
	platform  = fmt.Sprintf("%s/%s", runtime.GOOS, runtime.GOARCH)
)

// versionCmd 实现 `dqctl version` 子命令，输出版本信息。
var versionCmd = &cobra.Command{
	Use:   "version",
	Short: "输出版本信息",
	Long:  "输出 dqctl 的版本、Go 版本、平台及 commit 信息。",
	RunE:  runVersion,
}

// init 注册 versionCmd。
func init() {
	rootCmd.AddCommand(versionCmd)
}

// runVersion 执行 version 子命令逻辑。
func runVersion(cmd *cobra.Command, args []string) error {
	fmt.Printf("dqctl version %s\n", version)
	fmt.Printf("  Go version: %s\n", goVersion)
	fmt.Printf("  Platform: %s\n", platform)
	fmt.Printf("  Commit: %s\n", commit)
	return nil
}
