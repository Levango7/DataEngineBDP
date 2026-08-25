// Package service - baremetal_service_test.go 供应服务单元测试。
package service

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/sirupsen/logrus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
)

// newTestService 创建测试用供应服务(内存SQLite)
func newTestService(t *testing.T) *BareMetalService {
	t.Helper()
	db, err := InitDatabase("sqlite", ":memory:", 1, 1)
	if err != nil {
		t.Fatalf("初始化测试DB失败: %v", err)
	}

	logger := logrus.NewEntry(logrus.New())
	redfish := NewRedfishClient(0, true, "admin", "admin")
	k8s := NewK8sBootstrapper(&mockExecutor{}, "registry.k8s.io", "10.244.0.0/16", "10.96.0.0/12", 6443)

	svc := NewBareMetalService(db, redfish, k8s, logger)
	if err := svc.AutoMigrate(); err != nil {
		t.Fatalf("AutoMigrate失败: %v", err)
	}
	return svc
}

// mockExecutor 测试用命令执行器
type mockExecutor struct{}

func (m *mockExecutor) Execute(ctx context.Context, host, command string) (string, string, int, error) {
	return "kubeadm join 127.0.0.1:6443 --token dev --discovery-token-ca-cert-hash sha256:hash\n", "", 0, nil
}

func (m *mockExecutor) CopyFile(ctx context.Context, host, localPath, remotePath string) error {
	return nil
}

func TestBareMetalService_CreateCluster_Validation(t *testing.T) {
	svc := newTestService(t)

	tests := []struct {
		name string
		req  *model.CreateClusterRequest
	}{
		{
			name: "空名称",
			req: &model.CreateClusterRequest{
				Name: "",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
		},
		{
			name: "无节点",
			req: &model.CreateClusterRequest{
				Name:  "test",
				K8s:   model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{},
			},
		},
		{
			name: "无控制平面",
			req: &model.CreateClusterRequest{
				Name: "test",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "w1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
		},
		{
			name: "缺少BMC",
			req: &model.CreateClusterRequest{
				Name: "test",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, ManagementIP: "10.0.0.11"},
				},
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := svc.CreateCluster(context.Background(), tt.req)
			if err == nil {
				t.Errorf("期望返回错误，但CreateCluster成功")
			}
		})
	}
}

func TestBareMetalService_CreateCluster_Success(t *testing.T) {
	svc := newTestService(t)

	req := &model.CreateClusterRequest{
		Name: "test-cluster",
		K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
		Nodes: []model.NodeSpec{
			{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
			{Hostname: "w1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.2"}, ManagementIP: "10.0.0.12"},
		},
	}

	cluster, err := svc.CreateCluster(context.Background(), req)
	if err != nil {
		t.Fatalf("CreateCluster失败: %v", err)
	}
	if cluster.Name != "test-cluster" {
		t.Errorf("期望Name=test-cluster，得到 %s", cluster.Name)
	}
	if cluster.ControlPlaneCount != 1 {
		t.Errorf("期望ControlPlaneCount=1，得到 %d", cluster.ControlPlaneCount)
	}
	if cluster.WorkerCount != 1 {
		t.Errorf("期望WorkerCount=1，得到 %d", cluster.WorkerCount)
	}
	if cluster.NodeCount != 2 {
		t.Errorf("期望NodeCount=2，得到 %d", cluster.NodeCount)
	}
}

func TestBareMetalService_GetCluster_NotFound(t *testing.T) {
	svc := newTestService(t)

	_, err := svc.GetCluster(context.Background(), "non-existent-id")
	if err == nil {
		t.Fatal("期望返回错误，但GetCluster成功")
	}
}

func TestBareMetalService_ListClusters(t *testing.T) {
	svc := newTestService(t)

	clusters, err := svc.ListClusters(context.Background())
	if err != nil {
		t.Fatalf("ListClusters失败: %v", err)
	}
	if len(clusters) != 0 {
		t.Errorf("期望空列表，得到 %d 个集群", len(clusters))
	}
}

func TestValidateCreateRequest(t *testing.T) {
	tests := []struct {
		name    string
		req     *model.CreateClusterRequest
		wantErr bool
	}{
		{
			name: "有效请求",
			req: &model.CreateClusterRequest{
				Name: "valid",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
				},
			},
			wantErr: false,
		},
		{
			name: "重复hostname",
			req: &model.CreateClusterRequest{
				Name: "dup",
				K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
				Nodes: []model.NodeSpec{
					{Hostname: "cp1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "10.0.0.1"}, ManagementIP: "10.0.0.11"},
					{Hostname: "cp1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "10.0.0.2"}, ManagementIP: "10.0.0.12"},
				},
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validateCreateRequest(tt.req)
			if (err != nil) != tt.wantErr {
				t.Errorf("validateCreateRequest错误=%v, wantErr=%v", err, tt.wantErr)
			}
		})
	}
}

func TestCountNodes(t *testing.T) {
	nodes := []model.NodeSpec{
		{Role: model.NodeRoleControlPlane},
		{Role: model.NodeRoleControlPlane},
		{Role: model.NodeRoleWorker},
	}
	cp, worker := countNodes(nodes)
	if cp != 2 {
		t.Errorf("期望cp=2，得到 %d", cp)
	}
	if worker != 1 {
		t.Errorf("期望worker=1，得到 %d", worker)
	}
}

func TestBuildNodeFromSpec(t *testing.T) {
	spec := model.NodeSpec{
		Hostname:     "node1",
		Role:         model.NodeRoleWorker,
		ManagementIP: "10.0.0.11",
		BMC:          model.BMCCredential{Host: "10.0.0.1", Username: "admin", Password: "pass"},
	}
	node := buildNodeFromSpec("cluster-1", spec)
	if node.ClusterID != "cluster-1" {
		t.Errorf("期望ClusterID=cluster-1，得到 %s", node.ClusterID)
	}
	if node.Hostname != "node1" {
		t.Errorf("期望Hostname=node1，得到 %s", node.Hostname)
	}
	if node.BMCHost != "10.0.0.1" {
		t.Errorf("期望BMCHost=10.0.0.1，得到 %s", node.BMCHost)
	}
	if node.OSImage == "" {
		t.Error("期望默认OSImage非空")
	}
}

// newScaleTestCluster 直接落库一个running集群(跳过异步供应流程)
func newScaleTestCluster(t *testing.T, svc *BareMetalService, id string, cpCount, workerCount int) {
	t.Helper()
	cluster := &model.BareMetalCluster{
		ID:                id,
		Name:              id,
		State:             model.ClusterStateRunning,
		K8sVersion:        "v1.29.2",
		PodCIDR:           "10.244.0.0/16",
		ServiceCIDR:       "10.96.0.0/12",
		APIServerPort:     6443,
		NodeCount:         cpCount + workerCount,
		ControlPlaneCount: cpCount,
		WorkerCount:       workerCount,
	}
	require.NoError(t, svc.db.Create(cluster).Error)
}

func newScaleTestNode(t *testing.T, svc *BareMetalService, clusterID, hostname string, role model.NodeRole) {
	t.Helper()
	node := &model.BareMetalNode{
		UUID:      uuid.NewString(),
		ClusterID: clusterID,
		Hostname:  hostname,
		Role:      role,
		State:     model.NodeStateRunning,
		BMCHost:   "127.0.0.1",
	}
	require.NoError(t, svc.db.Create(node).Error)
}

func loadCluster(t *testing.T, svc *BareMetalService, clusterID string) model.BareMetalCluster {
	t.Helper()
	var cluster model.BareMetalCluster
	require.NoError(t, svc.db.First(&cluster, "id = ?", clusterID).Error)
	return cluster
}

func TestScaleCluster_ScaleOut_MixedRoles_CountsAccurate(t *testing.T) {
	svc := newTestService(t)
	const clusterID = "scale-out-mixed"
	newScaleTestCluster(t, svc, clusterID, 1, 1)
	newScaleTestNode(t, svc, clusterID, "cp-old", model.NodeRoleControlPlane)
	newScaleTestNode(t, svc, clusterID, "w-old", model.NodeRoleWorker)

	req := &model.ScaleRequest{
		Action: "add",
		Nodes: []model.NodeSpec{
			{Hostname: "w-new-1", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.21"},
			{Hostname: "w-new-2", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.22"},
			{Hostname: "cp-new-1", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.23"},
		},
	}

	require.NoError(t, svc.ScaleCluster(context.Background(), clusterID, req))

	cluster := loadCluster(t, svc, clusterID)
	assert.Equal(t, model.ClusterStateRunning, cluster.State)
	assert.Equal(t, 5, cluster.NodeCount)
	assert.Equal(t, 2, cluster.ControlPlaneCount)
	assert.Equal(t, 3, cluster.WorkerCount)

	var cpRows, workerRows int64
	require.NoError(t, svc.db.Model(&model.BareMetalNode{}).Where("cluster_id = ? AND role = ?", clusterID, model.NodeRoleControlPlane).Count(&cpRows).Error)
	require.NoError(t, svc.db.Model(&model.BareMetalNode{}).Where("cluster_id = ? AND role = ?", clusterID, model.NodeRoleWorker).Count(&workerRows).Error)
	assert.EqualValues(t, 2, cpRows)
	assert.EqualValues(t, 3, workerRows)
}

func TestScaleCluster_ScaleIn_MissingHostname_DecrementsOnlyRemovedByRole(t *testing.T) {
	svc := newTestService(t)
	const clusterID = "scale-in-ghost"
	newScaleTestCluster(t, svc, clusterID, 1, 2)
	newScaleTestNode(t, svc, clusterID, "cp-keep", model.NodeRoleControlPlane)
	newScaleTestNode(t, svc, clusterID, "w-del", model.NodeRoleWorker)
	newScaleTestNode(t, svc, clusterID, "w-keep", model.NodeRoleWorker)

	req := &model.ScaleRequest{
		Action: "remove",
		Nodes: []model.NodeSpec{
			{Hostname: "w-del"},
			{Hostname: "ghost-node"},
		},
	}

	require.NoError(t, svc.ScaleCluster(context.Background(), clusterID, req))

	cluster := loadCluster(t, svc, clusterID)
	assert.Equal(t, model.ClusterStateRunning, cluster.State)
	assert.Equal(t, 2, cluster.NodeCount)
	assert.Equal(t, 1, cluster.ControlPlaneCount)
	assert.Equal(t, 1, cluster.WorkerCount)

	var total int64
	require.NoError(t, svc.db.Model(&model.BareMetalNode{}).Where("cluster_id = ?", clusterID).Count(&total).Error)
	assert.EqualValues(t, 2, total)

	var removed int64
	require.NoError(t, svc.db.Model(&model.BareMetalNode{}).Where("cluster_id = ? AND hostname = ?", clusterID, "w-del").Count(&removed).Error)
	assert.EqualValues(t, 0, removed)
}

func TestScaleCluster_ScaleOut_Error_RollbackStateRunning(t *testing.T) {
	svc := newTestService(t)
	const clusterID = "scale-out-fail"
	newScaleTestCluster(t, svc, clusterID, 1, 1)

	require.NoError(t, svc.db.Callback().Create().Before("gorm:create").Register("test_fail_boom_node", func(tx *gorm.DB) {
		if node, ok := tx.Statement.Dest.(*model.BareMetalNode); ok && node.Hostname == "boom-node" {
			_ = tx.AddError(errors.New("injected create failure"))
		}
	}))

	req := &model.ScaleRequest{
		Action: "add",
		Nodes: []model.NodeSpec{
			{Hostname: "ok-node", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.31"},
			{Hostname: "boom-node", Role: model.NodeRoleWorker, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.32"},
		},
	}

	err := svc.ScaleCluster(context.Background(), clusterID, req)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "injected create failure")

	cluster := loadCluster(t, svc, clusterID)
	assert.Equal(t, model.ClusterStateRunning, cluster.State)
	assert.Equal(t, 2, cluster.NodeCount)
	assert.Equal(t, 1, cluster.ControlPlaneCount)
	assert.Equal(t, 1, cluster.WorkerCount)
}

func newRedfishTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	systemJSON := map[string]interface{}{
		"Id":           "sys-1",
		"Name":         "node1",
		"Manufacturer": "acme",
		"Model":        "m1",
		"SerialNumber": "SN1",
		"PowerState":   "On",
		"ProcessorSummary": map[string]interface{}{
			"Count": 2,
			"Cores": 8,
		},
		"MemorySummary": map[string]interface{}{
			"TotalSystemMemoryGiB": 64.0,
		},
	}
	writeOK := func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/redfish/v1", writeOK)
	mux.HandleFunc("/redfish/v1/Systems", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"Members":             []map[string]string{{"@odata.id": "/redfish/v1/Systems/sys-1"}},
			"Members@odata.count": 1,
		})
	})
	mux.HandleFunc("/redfish/v1/Systems/sys-1", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPatch {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(systemJSON)
	})
	mux.HandleFunc("/redfish/v1/Systems/sys-1/Actions/ComputerSystem.Reset", writeOK)
	return httptest.NewTLSServer(mux)
}

func provisionMapSize(svc *BareMetalService) int {
	svc.mu.RLock()
	defer svc.mu.RUnlock()
	return len(svc.provision)
}

func TestCreateCluster_ProvisionSuccess_CleansProvisionMap(t *testing.T) {
	server := newRedfishTestServer(t)
	defer server.Close()

	svc := newTestService(t)

	req := &model.CreateClusterRequest{
		Name: "prov-success",
		K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
		Nodes: []model.NodeSpec{
			{Hostname: "cp-prov", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: server.URL}, ManagementIP: "127.0.0.41"},
		},
	}

	cluster, err := svc.CreateCluster(context.Background(), req)
	require.NoError(t, err)

	require.Eventually(t, func() bool {
		return provisionMapSize(svc) == 0
	}, 5*time.Second, 10*time.Millisecond, "供应完成后provision map应无残留条目")

	got := loadCluster(t, svc, cluster.ID)
	assert.Equal(t, model.ClusterStateRunning, got.State)
}

func TestCreateCluster_ProvisionFailure_CleansProvisionMap(t *testing.T) {
	svc := newTestService(t)

	req := &model.CreateClusterRequest{
		Name: "prov-fail",
		K8s:  model.K8sConfig{KubernetesVersion: "v1.29.2", PodCIDR: "10.244.0.0/16", ServiceCIDR: "10.96.0.0/12", APIServerPort: 6443},
		Nodes: []model.NodeSpec{
			{Hostname: "cp-dead-bmc", Role: model.NodeRoleControlPlane, BMC: model.BMCCredential{Host: "127.0.0.1:1"}, ManagementIP: "127.0.0.42"},
		},
	}

	cluster, err := svc.CreateCluster(context.Background(), req)
	require.NoError(t, err)

	require.Eventually(t, func() bool {
		return provisionMapSize(svc) == 0
	}, 5*time.Second, 10*time.Millisecond, "供应失败后provision map应无残留条目")

	got := loadCluster(t, svc, cluster.ID)
	assert.Equal(t, model.ClusterStateFailed, got.State)
}
