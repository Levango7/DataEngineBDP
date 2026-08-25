// Package service 实现裸金属供应的核心业务逻辑。
//
// redfish_client.go 实现DMTF Redfish API客户端，用于通过BMC带外管理物理机。
// 参考: DMTF Redfish Specification DSP0266
package service

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

// RedfishClient Redfish API客户端
//
// 通过HTTPS与BMC管理控制器通信，实现DMTF Redfish标准接口。
// 用于电源控制、BIOS设置、PXE启动配置、硬件信息采集等。
type RedfishClient struct {
	httpClient      *http.Client
	defaultUsername string
	defaultPassword string
}

// NewRedfishClient 创建Redfish客户端
func NewRedfishClient(timeout time.Duration, insecureSkipVerify bool, defaultUser, defaultPass string) *RedfishClient {
	transport := &http.Transport{
		TLSClientConfig: &tls.Config{
			// BMC通常使用自签证书，开发环境跳过校验。
			// #nosec G402 -- InsecureSkipVerify 仅在部署方显式配置
			// TLS 跳过校验开关时为 true（隔离内网带外网络场景）；
			// 生产默认 false，走正常证书校验。
			InsecureSkipVerify: insecureSkipVerify,
		},
	}
	return &RedfishClient{
		httpClient: &http.Client{
			Timeout:   timeout,
			Transport: transport,
		},
		defaultUsername: defaultUser,
		defaultPassword: defaultPass,
	}
}

// RedfishSystem Redfish System资源(简化)
type RedfishSystem struct {
	ID               string                  `json:"Id"`
	Name             string                  `json:"Name"`
	Manufacturer     string                  `json:"Manufacturer"`
	Model            string                  `json:"Model"`
	SerialNumber     string                  `json:"SerialNumber"`
	PowerState       string                  `json:"PowerState"`
	Status           RedfishStatus           `json:"Status"`
	Boot             RedfishBoot             `json:"Boot"`
	ProcessorSummary RedfishProcessorSummary `json:"ProcessorSummary"`
	MemorySummary    RedfishMemorySummary    `json:"MemorySummary"`
	BIOSVersion      string                  `json:"BiosVersion"`
}

// RedfishStatus Redfish状态
type RedfishStatus struct {
	State  string `json:"State"`
	Health string `json:"Health"`
}

// RedfishBoot Redfish启动配置
type RedfishBoot struct {
	BootSourceOverrideEnabled string   `json:"BootSourceOverrideEnabled"`
	BootSourceOverrideTarget  string   `json:"BootSourceOverrideTarget"`
	BootSourceOverrideMode    string   `json:"BootSourceOverrideMode"`
	BootOrder                 []string `json:"BootOrder"`
}

// RedfishProcessorSummary CPU摘要
type RedfishProcessorSummary struct {
	Count int    `json:"Count"`
	Cores int    `json:"Cores"`
	Model string `json:"Model"`
}

// RedfishMemorySummary 内存摘要
type RedfishMemorySummary struct {
	TotalSystemMemoryGiB float64 `json:"TotalSystemMemoryGiB"`
}

// RedfishCollection Redfish集合响应
type RedfishCollection struct {
	Members []struct {
		ODataID string `json:"@odata.id"`
	} `json:"Members"`
	MembersCount int `json:"Members@odata.count"`
}

// RedfishError Redfish错误响应
type RedfishError struct {
	Error struct {
		Code          string `json:"code"`
		Message       string `json:"message"`
		ExtendedInfos []struct {
			MessageID string `json:"MessageId"`
			Message   string `json:"Message"`
		} `json:"@Message.ExtendedInfo"`
	} `json:"error"`
}

// baseURL 构造BMC的Redfish根URL
func baseURL(bmc model.BMCCredential) string {
	host := bmc.Host
	if !strings.HasPrefix(host, "https://") && !strings.HasPrefix(host, "http://") {
		host = "https://" + host
	}
	return strings.TrimRight(host, "/") + "/redfish/v1"
}

// doRequest 执行Redfish HTTP请求
func (c *RedfishClient) doRequest(ctx context.Context, method, url, username, password string, body interface{}) ([]byte, error) {
	var reqBody io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("序列化请求体失败: %w", err)
		}
		reqBody = bytes.NewReader(data)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, reqBody)
	if err != nil {
		return nil, fmt.Errorf("创建请求失败: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	req.SetBasicAuth(username, password)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Redfish请求失败: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("读取响应体失败: %w", err)
	}

	if resp.StatusCode >= 400 {
		var rerr RedfishError
		_ = json.Unmarshal(respBody, &rerr)
		msg := rerr.Error.Message
		if msg == "" {
			msg = string(respBody)
		}
		return respBody, fmt.Errorf("Redfish返回错误: HTTP %d: %s", resp.StatusCode, msg)
	}

	return respBody, nil
}

// resolveCredentials 解析凭据，使用默认值作为回退
func (c *RedfishClient) resolveCredentials(bmc model.BMCCredential) (string, string) {
	user := bmc.Username
	if user == "" {
		user = c.defaultUsername
	}
	pass := bmc.Password
	if pass == "" {
		pass = c.defaultPassword
	}
	return user, pass
}

// ListSystems 列出BMC上所有系统
// GET /redfish/v1/Systems
func (c *RedfishClient) ListSystems(ctx context.Context, bmc model.BMCCredential) ([]RedfishSystem, error) {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)

	body, err := c.doRequest(ctx, http.MethodGet, base+"/Systems", user, pass, nil)
	if err != nil {
		return nil, fmt.Errorf("列出Systems失败: %w", err)
	}

	var col RedfishCollection
	if err := json.Unmarshal(body, &col); err != nil {
		return nil, fmt.Errorf("解析Systems集合失败: %w", err)
	}

	systems := make([]RedfishSystem, 0, col.MembersCount)
	for _, m := range col.Members {
		sysURL := strings.TrimRight(bmc.Host, "/") + m.ODataID
		if !strings.HasPrefix(sysURL, "https://") && !strings.HasPrefix(sysURL, "http://") {
			sysURL = "https://" + sysURL
		}
		sysBody, err := c.doRequest(ctx, http.MethodGet, sysURL, user, pass, nil)
		if err != nil {
			// 单个系统查询失败不应阻塞整个列表，但需记录便于排障。
			// 修复：原代码静默 continue，错误被完全吞掉，运维无法定位问题。
			log.Printf("[redfish] list systems: fetch %s failed: %v", sysURL, err)
			continue
		}
		var sys RedfishSystem
		if err := json.Unmarshal(sysBody, &sys); err != nil {
			log.Printf("[redfish] list systems: unmarshal %s failed: %v", sysURL, err)
			continue
		}
		systems = append(systems, sys)
	}
	return systems, nil
}

// GetSystem 获取单个系统详情
// GET /redfish/v1/Systems/{id}
func (c *RedfishClient) GetSystem(ctx context.Context, bmc model.BMCCredential, systemID string) (*RedfishSystem, error) {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)

	body, err := c.doRequest(ctx, http.MethodGet, fmt.Sprintf("%s/Systems/%s", base, systemID), user, pass, nil)
	if err != nil {
		return nil, fmt.Errorf("获取System失败: %w", err)
	}

	var sys RedfishSystem
	if err := json.Unmarshal(body, &sys); err != nil {
		return nil, fmt.Errorf("解析System失败: %w", err)
	}
	return &sys, nil
}

// ResetSystem 电源控制(开机/关机/重启)
// POST /redfish/v1/Systems/{id}/Actions/ComputerSystem.Reset
func (c *RedfishClient) ResetSystem(ctx context.Context, bmc model.BMCCredential, systemID string, resetType model.PowerState) error {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)

	url := fmt.Sprintf("%s/Systems/%s/Actions/ComputerSystem.Reset", base, systemID)
	payload := map[string]string{
		"ResetType": string(resetType),
	}
	_, err := c.doRequest(ctx, http.MethodPost, url, user, pass, payload)
	if err != nil {
		return fmt.Errorf("电源控制失败(type=%s): %w", resetType, err)
	}
	return nil
}

// SetBootSource 设置启动源(用于PXE启动)
// PATCH /redfish/v1/Systems/{id}
func (c *RedfishClient) SetBootSource(ctx context.Context, bmc model.BMCCredential, systemID string, target model.BootSourceType, override model.BootSourceOverride) error {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)

	url := fmt.Sprintf("%s/Systems/%s", base, systemID)
	payload := map[string]interface{}{
		"Boot": map[string]string{
			"BootSourceOverrideEnabled": string(override),
			"BootSourceOverrideTarget":  string(target),
		},
	}
	_, err := c.doRequest(ctx, http.MethodPatch, url, user, pass, payload)
	if err != nil {
		return fmt.Errorf("设置启动源失败(target=%s): %w", target, err)
	}
	return nil
}

// SetBIOSAttribute 设置BIOS属性
// PATCH /redfish/v1/Systems/{id}/Bios/Settings
func (c *RedfishClient) SetBIOSAttribute(ctx context.Context, bmc model.BMCCredential, systemID string, attributes map[string]string) error {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)

	url := fmt.Sprintf("%s/Systems/%s/Bios/Settings", base, systemID)
	payload := map[string]interface{}{
		"Attributes": attributes,
	}
	_, err := c.doRequest(ctx, http.MethodPatch, url, user, pass, payload)
	if err != nil {
		return fmt.Errorf("设置BIOS属性失败: %w", err)
	}
	return nil
}

// CollectHardwareInfo 采集硬件信息
func (c *RedfishClient) CollectHardwareInfo(ctx context.Context, bmc model.BMCCredential, systemID string) (*model.HardwareInfo, error) {
	sys, err := c.GetSystem(ctx, bmc, systemID)
	if err != nil {
		return nil, err
	}
	return &model.HardwareInfo{
		Manufacturer: sys.Manufacturer,
		Model:        sys.Model,
		SerialNumber: sys.SerialNumber,
		CPUCount:     sys.ProcessorSummary.Count,
		CPUCores:     sys.ProcessorSummary.Cores,
		CPUModel:     sys.ProcessorSummary.Model,
		MemoryGB:     int(sys.MemorySummary.TotalSystemMemoryGiB),
	}, nil
}

// EnsurePXEBoot 设置节点下次PXE启动并重启
// 流程: 设置BootSourceOverrideTarget=Pxe + Reset(On/ForceRestart)
func (c *RedfishClient) EnsurePXEBoot(ctx context.Context, bmc model.BMCCredential, systemID string, powerOn bool) error {
	if err := c.SetBootSource(ctx, bmc, systemID, model.BootPxe, model.BootOnce); err != nil {
		return err
	}
	resetType := model.PowerReset
	if powerOn {
		// 若当前关机，使用On；否则使用ForceRestart
		sys, err := c.GetSystem(ctx, bmc, systemID)
		if err == nil && strings.EqualFold(sys.PowerState, "Off") {
			resetType = model.PowerOn
		}
	}
	return c.ResetSystem(ctx, bmc, systemID, resetType)
}

// PowerOnGracefully 优雅开机(若已开机则不操作)
func (c *RedfishClient) PowerOnGracefully(ctx context.Context, bmc model.BMCCredential, systemID string) error {
	sys, err := c.GetSystem(ctx, bmc, systemID)
	if err != nil {
		return err
	}
	if strings.EqualFold(sys.PowerState, "On") {
		return nil
	}
	return c.ResetSystem(ctx, bmc, systemID, model.PowerOn)
}

// PowerOffGracefully 优雅关机(若已关机则不操作)
func (c *RedfishClient) PowerOffGracefully(ctx context.Context, bmc model.BMCCredential, systemID string) error {
	sys, err := c.GetSystem(ctx, bmc, systemID)
	if err != nil {
		return err
	}
	if strings.EqualFold(sys.PowerState, "Off") {
		return nil
	}
	// 先尝试GracefulShutdown，失败则ForceOff
	if err := c.ResetSystem(ctx, bmc, systemID, "GracefulShutdown"); err != nil {
		return c.ResetSystem(ctx, bmc, systemID, "ForceOff")
	}
	return nil
}

// HealthCheck 检查BMC连通性
func (c *RedfishClient) HealthCheck(ctx context.Context, bmc model.BMCCredential) error {
	base := baseURL(bmc)
	user, pass := c.resolveCredentials(bmc)
	_, err := c.doRequest(ctx, http.MethodGet, base, user, pass, nil)
	return err
}
