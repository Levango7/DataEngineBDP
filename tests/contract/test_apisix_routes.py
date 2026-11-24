from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[2]
GENERATOR = REPO / "scripts" / "gen-apisix-routes.py"
CONFIGMAP = REPO / "design" / "deploy" / "charts" / "apisix" / "templates" / "configmap-routes.yaml"
CHART_VALUES = REPO / "design" / "deploy" / "charts" / "apisix" / "values.yaml"


@pytest.fixture(scope="module")
def generator():
    spec = importlib.util.spec_from_file_location("gen_apisix_routes", GENERATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _route_nodes(routes: list[dict]) -> dict[str, dict]:
    by_uri = {route["uri"]: route for route in routes}
    assert len(by_uri) == len(routes), "APISIX 路由 URI 不得重复"
    return by_uri


def test_normal_and_fixup_prefixes_emit_exact_and_wildcard_routes(generator) -> None:
    routes, unmapped, conflicts, _ = generator.build_routes(
        {
            "/api/v1/widgets": ["widgets"],
            "/api/v1/clusters": ["infra-orchestrator"],
            "/api/v1/clusters/health": ["karmada/failover"],
        },
        {"widgets": 8080, "infra-orchestrator": 8080, "failover-api": 8094},
    )

    by_uri = _route_nodes(routes)
    assert by_uri["/api/v1/widgets"]["upstream"]["nodes"] == {"widgets:8080": 1}
    assert by_uri["/api/v1/widgets/**"]["upstream"] == by_uri["/api/v1/widgets"]["upstream"]
    assert by_uri["/api/v1/clusters/health"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert by_uri["/api/v1/clusters/health/**"]["upstream"] == by_uri["/api/v1/clusters/health"]["upstream"]
    assert not unmapped
    assert not conflicts


def test_cluster_health_exact_route_does_not_take_over_parent(generator) -> None:
    routes, _, conflicts, _ = generator.build_routes(
        {
            "/api/v1/clusters": ["infra-orchestrator"],
            "/api/v1/clusters/health": ["karmada/failover"],
        },
        {"infra-orchestrator": 8080, "failover-api": 8094},
    )

    by_uri = _route_nodes(routes)
    assert by_uri["/api/v1/clusters"]["upstream"]["nodes"] == {"infra-orchestrator:8080": 1}
    assert by_uri["/api/v1/clusters/**"]["upstream"]["nodes"] == {"infra-orchestrator:8080": 1}
    assert by_uri["/api/v1/clusters/health"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert by_uri["/api/v1/clusters/health/**"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert not conflicts


def test_excluded_and_conflicting_prefixes_do_not_emit_prefix_routes(generator) -> None:
    routes, unmapped, conflicts, _ = generator.build_routes(
        {
            "/api/v10": ["first", "second"],
            "/api/v1/health": ["first"],
        },
        {"first": 8080, "second": 8081, "failover-api": 8094},
    )

    assert [route["uri"] for route in routes] == list(generator.EXPLICIT_ROUTES)
    assert conflicts == ["/api/v10 为粗前缀，被 first, second 共享 —— 需按二级路径细化后再路由"]
    assert not unmapped


def test_dynamic_cluster_health_route_targets_failover_api(generator) -> None:
    routes, unmapped, conflicts, _ = generator.build_routes(
        {
            "/api/v1/clusters": ["infra-orchestrator"],
            "/api/v1/clusters/health": ["karmada/failover"],
        },
        {"infra-orchestrator": 8080, "failover-api": 8094},
    )

    by_uri = _route_nodes(routes)
    assert by_uri["/api/v1/clusters/*/health"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert by_uri["/api/v1/clusters/health"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert by_uri["/api/v1/clusters"]["upstream"]["nodes"] == {"infra-orchestrator:8080": 1}
    assert not unmapped
    assert not conflicts


def test_production_generator_and_configmap_cover_every_exact_prefix(generator) -> None:
    contract = generator.load_contract_module()
    prefixes = generator.collect_prefixes(contract)
    routes, unmapped, conflicts, accepted = generator.build_routes(prefixes, generator.collect_chart_ports())

    assert not conflicts
    assert unmapped == ["/api/finops/v1/billing", "/api/v1/audit"]
    assert accepted == [
        f"{prefix} —— {generator.ACCEPTED_SHARED_PREFIXES[prefix]}"
        for prefix in sorted(generator.ACCEPTED_SHARED_PREFIXES)
    ]
    by_uri = _route_nodes(routes)
    wildcards = [route for route in routes if route["uri"].endswith("/**")]
    exacts = [
        route for route in routes if not route["uri"].endswith("/**") and route["uri"] not in generator.EXPLICIT_ROUTES
    ]
    assert wildcards and exacts
    for route in wildcards:
        exact_uri = route["uri"][:-3]
        assert exact_uri in by_uri, f"缺少精确基线路径：{exact_uri}"
        assert by_uri[exact_uri]["upstream"] == route["upstream"]
    for route in exacts:
        wildcard_uri = f"{route['uri']}/**"
        assert wildcard_uri in by_uri, f"精确基线路径缺少子路径伴随项：{route['uri']}"
        assert by_uri[wildcard_uri]["upstream"] == route["upstream"]

    forbidden = (
        set(unmapped) | set(generator.ACCEPTED_SHARED_PREFIXES) | (generator.EXCLUDED_PREFIXES & prefixes.keys())
    )
    for prefix in forbidden:
        assert prefix not in by_uri, f"未映射或已排除前缀不应生成精确路由：{prefix}"
        assert f"{prefix}/**" not in by_uri, f"未映射或已排除前缀不应生成通配路由：{prefix}"

    chart_ports = generator.collect_chart_ports()
    for uri, target in generator.EXPLICIT_ROUTES.items():
        assert by_uri[uri]["upstream"]["nodes"] == {f"{target}:{chart_ports[target]}": 1}

    assert by_uri["/api/v1/clusters/health"]["upstream"]["nodes"] == {"failover-api:8094": 1}
    assert by_uri["/api/v1/clusters"]["upstream"]["nodes"] == {"infra-orchestrator:8080": 1}
    document = yaml.safe_load(CONFIGMAP.read_text(encoding="utf-8"))
    assert yaml.safe_load(document["data"]["routes.json"]) == {"routes": routes}


def test_chart_uses_runtime_validated_apisix_debian_image() -> None:
    values = yaml.safe_load(CHART_VALUES.read_text(encoding="utf-8"))

    assert values["image"]["repository"] == "apache/apisix"
    assert values["image"]["tag"] == "3.11.0-debian"
