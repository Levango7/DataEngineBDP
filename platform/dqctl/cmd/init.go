package cmd

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"

	"github.com/shuqing/bigdata/dqctl/internal/config"
)

// initCmd 实现 `dqctl init` 子命令：交互式生成配置文件模板。
var initCmd = &cobra.Command{
	Use:   "init",
	Short: "初始化 dqctl 配置文件",
	Long:  "初始化 dqctl 配置文件，交互式询问平台地址、租户名、认证 token 并生成 ~/.dqctl/config.yaml。",
	RunE:  runInit,
}

// init 将 initCmd 注册到根命令。
func init() {
	rootCmd.AddCommand(initCmd)
}

// runInit 执行 init 子命令逻辑。
func runInit(cmd *cobra.Command, args []string) error {
	reader := bufio.NewReader(os.Stdin)

	platformURL := prompt(reader, "请输入平台地址 (如 https://platform.example.com): ")
	tenantID := prompt(reader, "请输入租户名: ")
	token := prompt(reader, "请输入认证 token: ")
	output := prompt(reader, "请输入默认输出格式 (json/yaml/table) [table]: ")
	if output == "" {
		output = "table"
	}

	home, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("获取用户主目录失败: %w", err)
	}
	configDir := filepath.Join(home, ".dqctl")
	if err := os.MkdirAll(configDir, 0o755); err != nil {
		return fmt.Errorf("创建配置目录失败: %w", err)
	}
	configPath := filepath.Join(configDir, "config.yaml")

	cfg := &config.Config{
		PlatformURL: platformURL,
		TenantID:    tenantID,
		Token:       token,
		Output:      output,
	}
	if err := config.Save(configPath, cfg); err != nil {
		return fmt.Errorf("保存配置文件失败: %w", err)
	}

	fmt.Printf("配置文件已生成: %s\n", configPath)
	return nil
}

// prompt 从 reader 读取一行输入并返回去除首尾空白后的字符串。
func prompt(reader *bufio.Reader, text string) string {
	fmt.Print(text)
	line, _ := reader.ReadString('\n')
	return strings.TrimSpace(line)
}
