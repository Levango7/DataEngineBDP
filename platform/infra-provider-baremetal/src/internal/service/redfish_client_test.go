// Package service - redfish_client_test.go Redfish客户端单元测试。
package service

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/shuqing/infra-provider-baremetal/src/internal/model"
)

// newTestRedfishServer 创建模拟BMC Redfish服务器
func newTestRedfishServer() *httptest.Server {
	mux := http.NewServeMux()

	// /redfish/v1 根
	mux.HandleFunc("/redfish/v1/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/redfish/v1/":
			json.NewEncoder(w).Encode(map[string]interface{}{
				"Systems": map[string]string{"@odata.id": "/redfish/v1/Systems"},
			})
		case "/redfish/v1/Systems":
			if r.Method == http.MethodGet {
				json.NewEncoder(w).Encode(map[string]interface{}{
					"Members": []map[string]string{
						{"@odata.id": "/redfish/v1/Systems/1"},
					},
					"Members@odata.count": 1,
				})
			}
		case "/redfish/v1/Systems/1":
			if r.Method == http.MethodGet {
				json.NewEncoder(w).Encode(RedfishSystem{
					ID:           "1",
					Name:         "Test Server",
					Manufacturer: "TestVendor",
					Model:        "TestModel",
					SerialNumber: "SN12345",
					PowerState:   "Off",
					ProcessorSummary: RedfishProcessorSummary{
						Count: 2,
						Cores: 16,
						Model: "Xeon",
					},
					MemorySummary: RedfishMemorySummary{
						TotalSystemMemoryGiB: 64,
					},
				})
			} else if r.Method == http.MethodPatch {
				w.WriteHeader(http.StatusNoContent)
			}
		case "/redfish/v1/Systems/1/Actions/ComputerSystem.Reset":
			if r.Method == http.MethodPost {
				w.WriteHeader(http.StatusOK)
			}
		case "/redfish/v1/Systems/1/Bios/Settings":
			if r.Method == http.MethodPatch {
				w.WriteHeader(http.StatusNoContent)
			}
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	})

	return httptest.NewServer(mux)
}

func TestRedfishClient_ListSystems(t *testing.T) {
	srv := newTestRedfishServer()
	defer srv.Close()

	client := NewRedfishClient(0, true, "admin", "admin")
	bmc := model.BMCCredential{
		Host:     srv.URL,
		Username: "admin",
		Password: "admin",
	}

	systems, err := client.ListSystems(context.Background(), bmc)
	if err != nil {
		t.Fatalf("ListSystems失败: %v", err)
	}
	if len(systems) != 1 {
		t.Fatalf("期望1个系统，得到 %d", len(systems))
	}
	if systems[0].Manufacturer != "TestVendor" {
		t.Errorf("期望Manufacturer=TestVendor，得到 %s", systems[0].Manufacturer)
	}
}

func TestRedfishClient_ResetSystem(t *testing.T) {
	srv := newTestRedfishServer()
	defer srv.Close()

	client := NewRedfishClient(0, true, "admin", "admin")
	bmc := model.BMCCredential{Host: srv.URL, Username: "admin", Password: "admin"}

	if err := client.ResetSystem(context.Background(), bmc, "1", model.PowerOn); err != nil {
		t.Fatalf("ResetSystem失败: %v", err)
	}
}

func TestRedfishClient_SetBootSource(t *testing.T) {
	srv := newTestRedfishServer()
	defer srv.Close()

	client := NewRedfishClient(0, true, "admin", "admin")
	bmc := model.BMCCredential{Host: srv.URL, Username: "admin", Password: "admin"}

	if err := client.SetBootSource(context.Background(), bmc, "1", model.BootPxe, model.BootOnce); err != nil {
		t.Fatalf("SetBootSource失败: %v", err)
	}
}

func TestRedfishClient_CollectHardwareInfo(t *testing.T) {
	srv := newTestRedfishServer()
	defer srv.Close()

	client := NewRedfishClient(0, true, "admin", "admin")
	bmc := model.BMCCredential{Host: srv.URL, Username: "admin", Password: "admin"}

	hw, err := client.CollectHardwareInfo(context.Background(), bmc, "1")
	if err != nil {
		t.Fatalf("CollectHardwareInfo失败: %v", err)
	}
	if hw.Manufacturer != "TestVendor" {
		t.Errorf("期望Manufacturer=TestVendor，得到 %s", hw.Manufacturer)
	}
	if hw.CPUCount != 2 {
		t.Errorf("期望CPUCount=2，得到 %d", hw.CPUCount)
	}
	if hw.MemoryGB != 64 {
		t.Errorf("期望MemoryGB=64，得到 %d", hw.MemoryGB)
	}
}

func TestRedfishClient_HealthCheck(t *testing.T) {
	srv := newTestRedfishServer()
	defer srv.Close()

	client := NewRedfishClient(0, true, "admin", "admin")
	bmc := model.BMCCredential{Host: srv.URL, Username: "admin", Password: "admin"}

	if err := client.HealthCheck(context.Background(), bmc); err != nil {
		t.Fatalf("HealthCheck失败: %v", err)
	}
}
