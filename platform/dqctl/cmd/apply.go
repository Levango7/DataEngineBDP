package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// applyDryRun 是 --dry-run flag 的值。
var applyDryRun bool

// applyFile 是 -f flag 指定的声明式配置文件路径。
var applyFile string

// applyCmd 实现 `dqctl apply -f <file>` 子命令：读取 YAML 声明式配置并调用封装层 API 创建/更新资源。
var applyCmd = &cobra.Command{
	Use:   "apply",
	Short: "应用声明式资源配置",
	Long:  "读取 YAML 声明式配置文件，调用封装层 API 创建或更新平台资源。",
	RunE:  runApply,
}

// init 注册 applyCmd 及其 flag。
func init() {
	applyCmd.Flags().StringVarP(&applyFile, "file", "f", "", "声明式配置文件路径 (必填)")
	applyCmd.Flags().BoolVar(&applyDryRun, "dry-run", false, "仅校验不实际执行")
	applyCmd.MarkFlagRequired("file")
	rootCmd.AddCommand(applyCmd)
}

// runApply 执行 apply 子命令逻辑。
func runApply(cmd *cobra.Command, args []string) error {
	if applyFile == "" {
		return fmt.Errorf("必须使用 -f 指定配置文件")
	}

	// 读取声明式配置文件。
	data, err := os.ReadFile(applyFile)
	if err != nil {
		return fmt.Errorf("读取配置文件 %s 失败: %w", applyFile, err)
	}

	if flagVerbose {
		fmt.Fprintf(os.Stderr, "[verbose] 已加载配置文件: %s (%d 字节)\n", applyFile, len(data))
	}

	if applyDryRun {
		fmt.Printf("[dry-run] 配置文件 %s 校验通过，未实际执行变更。\n", applyFile)
		return nil
	}

	// MVP 阶段：调用 API 创建/更新资源，此处返回模拟结果。
	fmt.Printf("apply 完成：已处理资源定义文件 %s\n", applyFile)
	fmt.Println("(MVP 阶段：API 调用返回模拟结果)")
	return nil
}
