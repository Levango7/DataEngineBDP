// Package service - bmc_guard.go BMC 出网地址门禁。
package service

import (
	"fmt"
	"net"
	"os"
	"strings"
)

const (
	// envAllowInsecureBMC 实验室/测试放行标记：允许 http 明文与回环地址。
	// 生产不设该变量，默认拒绝——凭据只走 https 到带外管理网。
	envAllowInsecureBMC = "BAREMETAL_ALLOW_INSECURE_BMC"
	// envBMCHostAllowlist 可选出网白名单，逗号分隔，元素为 IP、CIDR 或主机名（不含端口）。
	envBMCHostAllowlist = "BAREMETAL_BMC_HOST_ALLOWLIST"
)

// validateBMCBase 校验调用方提供的 BMC 地址并归一化为 Redfish 根 URL。
//
// BMC 地址来自建集群/纳管节点的请求载荷，RedfishClient 随后会带着 Basic Auth 凭据
// 访问它，因此必须按不可信输入处理：只接受 host[:port]；无条件拒绝链路本地段
// （云元数据 169.254.169.254 即在段内）、组播与未指定地址。
func validateBMCBase(raw string) (string, error) {
	rest := strings.TrimSpace(raw)
	if rest == "" {
		return "", fmt.Errorf("BMC 地址为空")
	}

	scheme := "https"
	if i := strings.Index(rest, "://"); i >= 0 {
		s := strings.ToLower(rest[:i])
		if s != "http" && s != "https" {
			return "", fmt.Errorf("BMC 地址协议 %q 不受支持，仅允许 http/https", s)
		}
		scheme = s
		rest = rest[i+3:]
	}
	if strings.ContainsAny(rest, "/?#@\\") {
		return "", fmt.Errorf("BMC 地址只接受 host[:port]，不接受路径/查询/凭据: %q", rest)
	}

	name := rest
	if h, _, err := net.SplitHostPort(rest); err == nil {
		name = h
	}
	name = strings.TrimSuffix(strings.TrimPrefix(name, "["), "]")
	if name == "" {
		return "", fmt.Errorf("BMC 地址缺少主机部分")
	}

	lab := strings.EqualFold(os.Getenv(envAllowInsecureBMC), "true")
	if scheme == "http" && !lab {
		return "", fmt.Errorf("BMC 地址使用 http 明文会外泄凭据，生产需 https（实验室可设 %s=true 放行）", envAllowInsecureBMC)
	}

	ip := net.ParseIP(name)
	if ip != nil {
		switch {
		case ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast():
			return "", fmt.Errorf("BMC 地址 %s 属链路本地段（云元数据 169.254.169.254 亦在此段），拒绝", name)
		case ip.IsMulticast():
			return "", fmt.Errorf("BMC 地址 %s 为组播地址，拒绝", name)
		case ip.IsUnspecified():
			return "", fmt.Errorf("BMC 地址 %s 为未指定地址，拒绝", name)
		case ip.IsLoopback() && !lab:
			return "", fmt.Errorf("BMC 地址 %s 为回环地址，拒绝（实验室可设 %s=true 放行）", name, envAllowInsecureBMC)
		}
	} else if !isDomainLabelList(name) {
		return "", fmt.Errorf("BMC 主机名 %q 形式不合法", name)
	}

	if err := checkBMCHostAllowlist(name, ip); err != nil {
		return "", err
	}

	return scheme + "://" + rest + "/redfish/v1", nil
}

// checkBMCHostAllowlist 在配置了白名单时校验主机是否放行；未配置则不启用该层。
func checkBMCHostAllowlist(name string, ip net.IP) error {
	entries := strings.TrimSpace(os.Getenv(envBMCHostAllowlist))
	if entries == "" {
		return nil
	}
	for _, item := range strings.Split(entries, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if strings.EqualFold(item, name) {
			return nil
		}
		if ip != nil {
			if _, block, err := net.ParseCIDR(item); err == nil && block.Contains(ip) {
				return nil
			}
		}
	}
	return fmt.Errorf("BMC 地址 %s 不在 %s 白名单内，拒绝出网", name, envBMCHostAllowlist)
}

// isDomainLabelList 判定主机名形式：标签 1-63 字符、仅限字母数字与连字符且不以连字符首尾。
func isDomainLabelList(name string) bool {
	if name == "" || len(name) > 253 {
		return false
	}
	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return false
		}
		for _, r := range label {
			asciiAlphaNum := r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9'
			if !asciiAlphaNum && r != '-' {
				return false
			}
		}
	}
	return true
}
