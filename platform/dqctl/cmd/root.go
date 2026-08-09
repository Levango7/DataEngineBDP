// Package cmd 定义 dqctl 的所有命令行子命令。
//
// root.go 定义根命令及全局配置加载逻辑，包括 --config、--tenant、--verbose、--output 等全局 flag。
package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
	"github.com/spf13/viper"

	"github.com/Levango7/DataEngineBDP/dqctl/internal/client"
	"github.com/Levango7/DataEngineBDP/dqctl/internal/config"
)

// 全局配置实例，由 root 命令在 PersistentPreRun 中加载，供各子命令使用。
var globalCfg *config.Config

// 全局 flag 变量。
var (
	flagConfig  string // --config 指定配置文件路径
	flagTenant  string // --tenant 指定租户 ID
	flagVerbose bool   // --verbose 输出详细日志
	flagOutput  string // --output 输出格式 json/yaml/table
)

// rootCmd 是 dqctl 的根命令。
var rootCmd = &cobra.Command{
	Use:   "dqctl",
	Short: "数据引擎大数据平台命令行管理工具",
	Long:  "dqctl 是数据引擎大数据平台的声明式资源管理命令行工具",
	// PersistentPreRun 在所有子命令执行前加载配置文件。
	PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
		return loadConfig()
	},
}

// Execute 执行根命令，作为 main 入口。
func Execute() error {
	return rootCmd.Execute()
}

// init 注册全局 flag 并绑定到 viper。
func init() {
	cobra.OnInitialize(initViper)

	rootCmd.PersistentFlags().StringVar(&flagConfig, "config", "", "配置文件路径（默认 ~/.dqctl/config.yaml）")
	rootCmd.PersistentFlags().StringVar(&flagTenant, "tenant", "", "租户 ID")
	rootCmd.PersistentFlags().BoolVarP(&flagVerbose, "verbose", "v", false, "输出详细日志")
	rootCmd.PersistentFlags().StringVarP(&flagOutput, "output", "o", "table", "输出格式 (json/yaml/table)")
}

// initViper 初始化 viper 默认值。
func initViper() {
	viper.SetDefault("output", "table")
}

// loadConfig 加载配置文件，优先使用 --config 指定路径，否则使用 ~/.dqctl/config.yaml。
func loadConfig() error {
	configPath := flagConfig
	if configPath == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return fmt.Errorf("获取用户主目录失败: %w", err)
		}
		configPath = filepath.Join(home, ".dqctl", "config.yaml")
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		// 配置文件不存在时使用空配置，不阻断命令执行（如 init 命令）。
		if flagVerbose {
			fmt.Fprintf(os.Stderr, "[verbose] 未加载配置文件: %v\n", err)
		}
		cfg = &config.Config{Output: flagOutput}
	}
	globalCfg = cfg

	// 命令行 flag 覆盖配置文件中的值。
	if flagTenant != "" {
		cfg.TenantID = flagTenant
	}
	if flagOutput != "" && flagOutput != "table" {
		cfg.Output = flagOutput
	}

	return nil
}

// getClient 根据全局配置构造 API 客户端。
//
// 当未配置 platform_url（globalCfg 为空或 PlatformURL 为空字符串）时返回 nil，
// 调用方应据此降级为本地模拟输出，以保证 CLI 在离线/未初始化状态下仍可用。
func getClient() *client.Client {
	if globalCfg == nil || globalCfg.PlatformURL == "" {
		return nil
	}
	return client.NewClient(globalCfg.PlatformURL, globalCfg.TenantID, globalCfg.Token)
}
