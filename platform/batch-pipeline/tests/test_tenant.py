"""M1 多租户化测试.

覆盖：
    1. tenant id 校验（合法/非法、大小写、长度、特殊字符）
    2. resolve_tenant 优先级：环境变量 > config tenant.id；未启用 → None
    3. apply_tenant 路径分区：run_dir/state_dir 追加、storage.prefix 前置、
       warehouse（URI / iceberg 本地 / parquet segment）、sqlite catalog_uri、
       openlineage namespace；输入 cfg 不被原地修改
    4. Manifest / MetricsRecorder 的 tenant_id 条件序列化（单租户 schema 不变）
    5. 端到端：tenant.enabled=true 时 run 目录落在 run/<tenant>/<batch>/，
       manifest/metrics 带 tenant_id；环境变量注入等价生效
"""

from __future__ import annotations

import copy
import json
import os
import shutil
import tempfile
import uuid
from typing import Any

import pytest

from batch_pipeline.config_schema import validate_config
from batch_pipeline.helpers import ROOT, abs_path, json_load
from batch_pipeline.lineage import Manifest
from batch_pipeline.metrics import MetricsRecorder
from batch_pipeline.pipeline import run_pipeline
from batch_pipeline.tenant import (
    DEFAULT_TENANT_ID,
    TENANT_ENV_VAR,
    TenantError,
    apply_tenant,
    resolve_tenant,
    validate_tenant_id,
)


class TestValidateTenantId:
    @pytest.mark.parametrize("tid", ["a", "tenant-a", "tenant01", "0-9", "a" * 63])
    def test_ok(self, tid):
        assert validate_tenant_id(tid) == tid

    @pytest.mark.parametrize(
        "tid", ["", "   ", "Tenant", "tenant_a", "-lead", "trail-", "a" * 64, "ten ant", "租户"]
    )
    def test_rejects(self, tid):
        with pytest.raises(TenantError):
            validate_tenant_id(tid)


class TestResolveTenant:
    def test_disabled_returns_none(self):
        assert resolve_tenant({}) is None
        assert resolve_tenant({"tenant": {"enabled": False, "id": "acme"}}) is None

    def test_enabled_uses_id(self):
        assert resolve_tenant({"tenant": {"enabled": True, "id": "acme"}}) == "acme"

    def test_enabled_empty_id_falls_back_to_default(self):
        assert resolve_tenant({"tenant": {"enabled": True}}) == DEFAULT_TENANT_ID

    def test_enabled_invalid_id_raises(self):
        with pytest.raises(TenantError):
            resolve_tenant({"tenant": {"enabled": True, "id": "Bad_ID"}})

    def test_env_overrides_config(self, monkeypatch):
        monkeypatch.setenv(TENANT_ENV_VAR, "env-tenant")
        assert resolve_tenant({"tenant": {"enabled": True, "id": "acme"}}) == "env-tenant"

    def test_env_works_without_config_section(self, monkeypatch):
        monkeypatch.setenv(TENANT_ENV_VAR, "env-tenant")
        assert resolve_tenant({}) == "env-tenant"

    def test_env_invalid_raises(self, monkeypatch):
        monkeypatch.setenv(TENANT_ENV_VAR, "BAD_ID")
        with pytest.raises(TenantError):
            resolve_tenant({})

    def test_env_blank_falls_back_to_config(self, monkeypatch):
        monkeypatch.setenv(TENANT_ENV_VAR, "   ")
        assert resolve_tenant({"tenant": {"enabled": True, "id": "acme"}}) == "acme"


class TestApplyTenant:
    @staticmethod
    def _base_cfg() -> dict[str, Any]:
        return validate_config({})

    def test_container_roots_and_prefix(self):
        out = apply_tenant(self._base_cfg(), "acme")
        assert out["pipeline"]["run_dir"] == "run/acme"
        assert out["incremental"]["state_dir"] == "state/acme"
        assert out["storage"]["prefix"] == "acme"
        assert out["openlineage"]["namespace"] == "batch-pipeline.acme"
        assert out["tenant"]["enabled"] is True
        assert out["tenant"]["id"] == "acme"

    def test_input_cfg_not_mutated(self):
        cfg = self._base_cfg()
        snapshot = copy.deepcopy(cfg)
        apply_tenant(cfg, "acme")
        assert cfg == snapshot

    def test_run_dir_absolute_root_appended(self):
        cfg = self._base_cfg()
        cfg["pipeline"]["run_dir"] = os.path.join(str(ROOT), "run")
        out = apply_tenant(cfg, "acme")
        # 配置字段统一正斜杠；与 os.path.join 产物按归一化路径比较
        assert os.path.normpath(out["pipeline"]["run_dir"]) == os.path.normpath(
            os.path.join(str(ROOT), "run", "acme")
        )

    def test_prefix_preserved(self):
        cfg = self._base_cfg()
        cfg["storage"]["prefix"] = "pre"
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["prefix"] == "acme/pre"

    def test_warehouse_uri_partitioned(self):
        cfg = self._base_cfg()
        cfg["storage"]["warehouse"] = "s3://lake/warehouse"
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["warehouse"] == "s3://lake/acme/warehouse"

    def test_warehouse_iceberg_local_partitioned(self):
        cfg = self._base_cfg()
        cfg["storage"]["backend"] = "iceberg"
        cfg["storage"]["warehouse"] = "state/warehouse"
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["warehouse"] == "state/acme/warehouse"

    def test_warehouse_parquet_segment_untouched(self):
        # parquet 后端 warehouse 只参与 S3 key 拼接，分区由 prefix 承担
        cfg = self._base_cfg()
        cfg["storage"]["backend"] = "parquet"
        cfg["storage"]["warehouse"] = "state/warehouse"
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["warehouse"] == "state/warehouse"
        assert out["storage"]["prefix"] == "acme"

    def test_sqlite_catalog_uri_partitioned(self):
        cfg = self._base_cfg()
        cfg["storage"]["backend"] = "iceberg"
        cfg["storage"]["iceberg"] = {"catalog_uri": "sqlite:///state/iceberg_catalog.db"}
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["iceberg"]["catalog_uri"] == "sqlite:///state/acme/iceberg_catalog.db"

    def test_rest_catalog_uri_untouched(self):
        cfg = self._base_cfg()
        cfg["storage"]["iceberg"] = {"catalog_uri": "http://iceberg-rest:8181/catalog"}
        out = apply_tenant(cfg, "acme")
        assert out["storage"]["iceberg"]["catalog_uri"] == "http://iceberg-rest:8181/catalog"

    def test_invalid_id_raises(self):
        with pytest.raises(TenantError):
            apply_tenant(self._base_cfg(), "Bad_ID")

    def test_digest_differs_per_tenant(self):
        from batch_pipeline.pipeline import config_digest

        base = self._base_cfg()
        acme = apply_tenant(base, "acme")
        other = apply_tenant(base, "other")
        assert config_digest(acme) != config_digest(other)
        assert config_digest(acme) != config_digest(base)


class TestManifestMetricsTenant:
    def test_manifest_tenant_absent_by_default(self):
        m = Manifest("b-1", "digest", os.path.join("run", "b-1"))
        assert "tenant_id" not in m.to_dict()

    def test_manifest_tenant_included(self):
        m = Manifest("b-1", "digest", os.path.join("run", "acme", "b-1"), tenant_id="acme")
        assert m.to_dict()["tenant_id"] == "acme"

    def test_metrics_tenant_absent_by_default(self):
        r = MetricsRecorder("b-1")
        assert "tenant_id" not in r.to_dict()

    def test_metrics_tenant_included(self):
        r = MetricsRecorder("b-1", tenant_id="acme")
        assert r.to_dict()["tenant_id"] == "acme"


class TestTenantE2E:
    """端到端：租户分区后 run 目录与 manifest/metrics 的 tenant 标识."""

    @pytest.fixture
    def workdir(self, _same_drive_tmp_root):
        d = tempfile.mkdtemp(prefix="tenant_test_", dir=_same_drive_tmp_root)
        yield d
        shutil.rmtree(d, ignore_errors=True)

    @staticmethod
    def _prepared_cfg(workdir: str) -> dict[str, Any]:
        cfg = json_load(abs_path("config/pipeline_small.json"))
        data_dir = os.path.join(workdir, "data", "raw")
        cfg["generator"]["output_dir"] = data_dir
        from batch_pipeline.generator import main as gen_main

        gen_main(cfg)
        cfg["source"]["files"] = {
            "orders": os.path.join(data_dir, "orders.csv"),
            "customers": os.path.join(data_dir, "customers.csv"),
            "products": os.path.join(data_dir, "products.csv"),
        }
        cfg["pipeline"]["run_dir"] = os.path.join(ROOT, "run")
        cfg["generator"]["enabled"] = False
        return cfg

    @staticmethod
    def _cleanup_tenant_run(tenant_id: str) -> None:
        shutil.rmtree(os.path.join(ROOT, "run", tenant_id), ignore_errors=True)

    def test_run_config_section(self, workdir):
        cfg = self._prepared_cfg(workdir)
        cfg["tenant"] = {"enabled": True, "id": "acme"}
        batch_id = "test-tenant-" + uuid.uuid4().hex[:6]
        try:
            rc = run_pipeline(cfg, batch_id, "")
            assert rc == 0, "租户模式端到端流水线应成功"
            batch_dir = os.path.join(ROOT, "run", "acme", batch_id)
            assert os.path.isdir(batch_dir), f"批次目录应位于 run/acme/<batch>: {batch_dir}"
            manifest = json_load(os.path.join(batch_dir, "manifest.json"))
            assert manifest["status"] == "success"
            assert manifest["tenant_id"] == "acme"
            metrics = json_load(os.path.join(batch_dir, "metrics.json"))
            assert metrics["tenant_id"] == "acme"
        finally:
            self._cleanup_tenant_run("acme")

    def test_run_env_var_injection(self, workdir, monkeypatch):
        cfg = self._prepared_cfg(workdir)
        assert "tenant" not in cfg, "前置：配置未启用租户"
        monkeypatch.setenv(TENANT_ENV_VAR, "envco")
        batch_id = "test-tenant-" + uuid.uuid4().hex[:6]
        try:
            rc = run_pipeline(cfg, batch_id, "")
            assert rc == 0, "环境变量注入的租户批次应成功"
            batch_dir = os.path.join(ROOT, "run", "envco", batch_id)
            assert os.path.isdir(batch_dir), f"批次目录应位于 run/envco/<batch>: {batch_dir}"
            manifest = json_load(os.path.join(batch_dir, "manifest.json"))
            assert manifest["tenant_id"] == "envco"
        finally:
            self._cleanup_tenant_run("envco")

    def test_run_env_var_invalid_id_fails(self, workdir, monkeypatch):
        cfg = self._prepared_cfg(workdir)
        monkeypatch.setenv(TENANT_ENV_VAR, "BAD_ID")
        with pytest.raises(TenantError):
            run_pipeline(cfg, "test-tenant-bad", "")
