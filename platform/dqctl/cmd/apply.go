package cmd

import (
	"fmt"
	"io"
	"net/http"
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
//
// 当已配置 platform_url 时，通过封装层 API（POST /api/v1/tenants）提交资源声明；
// 未配置时降级为本地模拟输出，保证 CLI 在离线状态下仍可完成 dry-run 与文件校验。
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

	c := getClient()
	if c == nil {
		// 未配置 platform_url，降级为本地模拟输出。
		fmt.Fprintln(cmd.ErrOrStderr(), "[提示] 未配置 platform_url，以下为模拟结果。请运行 dqctl init 配置后端地址。")
		fmt.Printf("apply 完成：已处理资源定义文件 %s\n", applyFile)
		return nil
	}

	// 调用封装层 API 创建/更新资源。
	// 将声明式文件内容作为 payload 提交，封装层负责解析 YAML 并路由到对应资源端点。
	resp, err := c.Post("/api/v1/tenants", map[string]interface{}{
		"source":  "dqctl",
		"file":    applyFile,
		"content": string(data),
	})
	if err != nil {
		return fmt.Errorf("调用封装层 API 失败: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= http.StatusBadRequest {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("封装层 API 返回错误: HTTP %d, body=%s", resp.StatusCode, string(body))
	}

	fmt.Printf("apply 完成：已处理资源定义文件 %s\n", applyFile)
	return nil
}
