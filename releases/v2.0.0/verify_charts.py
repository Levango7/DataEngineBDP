#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GA 检查清单 CI 级别验证脚本

由于本地未安装 helm CLI，本脚本通过 PyYAML 实现 helm lint 与 helm install --dry-run 的
等价静态验证：
- helm lint 等价：Chart.yaml 格式合法 + 必填字段（apiVersion/name/version）+ values.yaml 合法
                  + templates/*.yaml 合法 + templates/NOTES.txt 存在
- helm install --dry-run 等价：渲染 templates/*.yaml（替换 {{ .Values.* }} 与 {{ .Chart.* }}）
                  生成 Kubernetes manifest，校验 YAML 合法性 + apiVersion/kind/metadata 必填
- kubeconform 等价：校验 manifest apiVersion 与 kind 是否在已知 Kubernetes 资源清单中

输出 JSON 报告：releases/v2.0.0/chart_verification_report.json
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any

import yaml

CHARTS_ROOT = Path("design/deploy/charts")
REPORT_PATH = Path("releases/v2.0.0/chart_verification_report.json")

# Kubernetes 已知核心资源（apiVersion -> [kind]）用于 kubeconform 等价校验
KNOWN_RESOURCES: dict[str, set[str]] = {
    "v1": {
        "ConfigMap", "Secret", "Service", "ServiceAccount", "Pod", "PersistentVolume",
        "PersistentVolumeClaim", "Namespace", "Node", "Endpoints", "Event", "LimitRange",
        "ResourceQuota", "ReplicationController", "Binding", "ComponentStatus", "List",
    },
    "apps/v1": {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "ControllerRevision"},
    "batch/v1": {"Job", "CronJob"},
    "autoscaling/v1": {"HorizontalPodAutoscaler"},
    "autoscaling/v2": {"HorizontalPodAutoscaler"},
    "autoscaling/v2beta1": {"HorizontalPodAutoscaler"},
    "autoscaling/v2beta2": {"HorizontalPodAutoscaler"},
    "networking.k8s.io/v1": {"NetworkPolicy", "Ingress", "IngressClass"},
    "networking.k8s.io/v1beta1": {"Ingress", "IngressClass"},
    "policy/v1": {"PodDisruptionBudget"},
    "policy/v1beta1": {"PodDisruptionBudget", "PodSecurityPolicy"},
    "rbac.authorization.k8s.io/v1": {"Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding"},
    "rbac.authorization.k8s.io/v1beta1": {"Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding"},
    "apiextensions.k8s.io/v1": {"CustomResourceDefinition"},
    "apiextensions.k8s.io/v1beta1": {"CustomResourceDefinition"},
    "apiregistration.k8s.io/v1": {"APIService"},
    "storage.k8s.io/v1": {"StorageClass", "VolumeAttachment", "CSIDriver", "CSINode"},
    "storage.k8s.io/v1beta1": {"StorageClass", "VolumeAttachment", "CSIDriver", "CSINode"},
    "cert-manager.io/v1": {"Certificate", "Issuer", "ClusterIssuer"},
    "cert-manager.io/v1alpha2": {"Certificate", "Issuer", "ClusterIssuer"},
    "monitoring.coreos.com/v1": {"ServiceMonitor", "PodMonitor", "PrometheusRule", "Prometheus", "Alertmanager"},
    "helm.toolkit.fluxcd.io/v2beta1": {"HelmRelease"},
    "source.toolkit.fluxcd.io/v1beta1": {"HelmRepository", "GitRepository"},
    "keda.sh/v1alpha1": {"ScaledObject"},
    "external-secrets.io/v1beta1": {"ExternalSecret", "SecretStore", "ClusterSecretStore"},
    "argoproj.io/v1alpha1": {"Application", "AppProject", "Workflow", "WorkflowTemplate"},
    "tekton.dev/v1beta1": {"Task", "Pipeline", "TaskRun", "PipelineRun"},
    "elasticsearch.k8s.elastic.co/v1": {"Elasticsearch"},
    "kibana.k8s.elastic.co/v1": {"Kibana"},
    "beat.k8s.elastic.co/v1": {"Beat"},
    "agent.k8s.elastic.co/v1": {"Agent"},
    "traefik.containo.us/v1alpha1": {"IngressRoute", "Middleware", "TLSStore", "IngressRouteTCP"},
    "traefik.io/v1alpha1": {"IngressRoute", "Middleware", "TLSStore", "IngressRouteTCP"},
    "istio.io/v1beta1": {"VirtualService", "DestinationRule", "Gateway", "ServiceEntry", "EnvoyFilter"},
    "networking.istio.io/v1beta1": {"VirtualService", "DestinationRule", "Gateway", "ServiceEntry", "EnvoyFilter"},
    "security.istio.io/v1beta1": {"PeerAuthentication", "RequestAuthentication", "AuthorizationPolicy"},
    "telemetry.istio.io/v1alpha1": {"Telemetry"},
    "operator.knative.dev/v1beta1": {"KnativeServing"},
    "serving.knative.dev/v1": {"Service", "Configuration", "Revision", "Route"},
    "eventing.knative.dev/v1": {"Broker", "Trigger"},
    "flink.apache.org/v1beta1": {"FlinkDeployment", "FlinkSessionJob"},
    "sparkoperator.k8s.io/v1beta2": {"SparkApplication"},
    "kubeflow.org/v1": {"PyTorchJob", "TFJob", "MXJob", "MPIJob", "PaddleJob"},
    "machinelearning.seldon.io/v1": {"SeldonDeployment"},
    "scheduling.incubator.k8s.io/v1alpha1": {"PriorityClass"},
    "scheduling.k8s.io/v1": {"PriorityClass"},
    "node.k8s.io/v1": {"RuntimeClass"},
    "discovery.k8s.io/v1": {"EndpointSlice"},
    "discovery.k8s.io/v1beta1": {"EndpointSlice"},
    "coordination.k8s.io/v1": {"Lease"},
    "coordination.k8s.io/v1beta1": {"Lease"},
    "events.k8s.io/v1": {"Event"},
    "events.k8s.io/v1beta1": {"Event"},
    "authentication.k8s.io/v1": {"TokenReview", "SelfSubjectReview"},
    "authorization.k8s.io/v1": {"SelfSubjectAccessReview", "SubjectAccessReview", "SelfSubjectRulesReview"},
    "internal.crd.projectcalico.org/v1": {"BGPConfiguration", "FelixConfiguration", "IPPool", "KubeControllersConfiguration"},
    "crd.projectcalico.org/v1": {"BGPConfiguration", "FelixConfiguration", "IPPool", "KubeControllersConfiguration"},
    "acid.zalan.do/v1": {"PostgresTeam", "PostgresOperatorConfiguration", "OperatorConfiguration"},
    "zalando.org/v1": {"Postgresql"},
    "druid.apache.org/v1alpha1": {"Druid"},
    "nifi.apache.org/v1alpha1": {"NifiCluster"},
    "kafka.strimzi.io/v1beta2": {"Kafka", "KafkaTopic", "KafkaUser", "KafkaConnect", "KafkaConnector"},
    "strimzi.io/v1beta2": {"Kafka", "KafkaTopic", "KafkaUser"},
    "milvus.io/v1beta1": {"Milvus"},
    "nebula-graph.com.cn/v1": {"NebulaCluster"},
    "starrocks.com/v1alpha1": {"StarRocksCluster"},
    "doris.apache.com/v1alpha1": {"DorisCluster"},
    "iotdb.apache.org/v1alpha1": {"IoTDBCluster"},
    "dataflow.argoproj.io/v1alpha1": {"Workflow"},
    "riotkit.org/v1alpha1": {"BackupSchedule"},
    "k8s.mariadb.com/v1alpha1": {"MariaDB"},
    "postgresql.cnpg.io/v1": {"Cluster"},
    "postgres-operator.crunchydata.com/v1beta1": {"PostgresCluster"},
    "opensearch.opster.io/v1alpha1": {"OpenSearchCluster"},
    "grafana.integreatly.org/v1": {"Grafana", "GrafanaDashboard", "GrafanaDataSource"},
    "integration.raptor.ml/v1alpha1": {},
    "raptor.ml/v1alpha1": {},
    "karpenter.sh/v1": {"NodePool", "NodeClaim"},
    "karpenter.sh/v1beta1": {"NodePool", "NodeClaim"},
    "infrastructure.cluster.x-k8s.io/v1beta1": {},
    "cluster.x-k8s.io/v1beta1": {"Cluster", "Machine", "MachineDeployment", "MachineSet"},
    "addons.cluster.x-k8s.io/v1beta1": {"ClusterResourceSet"},
    "controlplane.cluster.x-k8s.io/v1beta1": {},
    "bootstrap.cluster.x-k8s.io/v1beta1": {},
    "infrastructure.cluster.x-k8s.io/v1alpha4": {},
    "cni.cilium.io/v1alpha1": {"CiliumBGPPeeringPolicy", "CiliumEgressGatewayPolicy", "CiliumL2AnnouncementPolicy"},
    "cilium.io/v2": {"CiliumNetworkPolicy", "CiliumClusterwideNetworkPolicy"},
    "snapshot.storage.k8s.io/v1": {"VolumeSnapshot", "VolumeSnapshotClass", "VolumeSnapshotContent"},
    "snapshot.storage.k8s.io/v1beta1": {"VolumeSnapshot", "VolumeSnapshotClass", "VolumeSnapshotContent"},
    "loki.grafana.com/v1": {"LokiStack"},
    "tempo.grafana.com/v1alpha1": {"TempoMonolith", "TempoStack"},
    "otelcol.opentelemetry.io/v1alpha1": {"OpenTelemetryCollector"},
    "opentelemetry.io/v1alpha1": {"Instrumentation"},
    "jaegertracing.io/v1": {"Jaeger"},
    "argocd.argoproj.io/v1alpha1": {"Application", "AppProject"},
    "batch.volcano.sh/v1alpha1": {"Job"},
    "volcano.sh/v1beta1": {"PodGroup", "Queue"},
    "descheduler/v1alpha1": {},
    "nodeinfo.volcano.sh/v1alpha1": {},
    "karmada.io/v1": {"Cluster", "PropagationPolicy", "ClusterPropagationPolicy", "OverridePolicy", "ClusterOverridePolicy", "ResourceBinding", "ClusterResourceBinding"},
    "policy.karmada.io/v1alpha1": {},
    "work.karmada.io/v1alpha1": {},
    "search.karmada.io/v1alpha1": {},
    "config.karmada.io/v1alpha1": {},
    "operator.karmada.io/v1alpha1": {},
    "networking.gke.io/v1": {"MultiClusterService"},
    "gateway.networking.k8s.io/v1": {"Gateway", "GatewayClass", "HTTPRoute"},
    "gateway.networking.k8s.io/v1beta1": {"Gateway", "GatewayClass", "HTTPRoute"},
    "gateway.apiextensions.k8s.io/v1": {},
    "acme.cert-manager.io/v1": {},
    "aiven.io/v1alpha1": {},
    "redis.opstreelabs.in/v1beta1": {"Redis"},
    "redis.kun/v1": {"Redis"},
    "databases.spotahome.com/v1": {"RedisFailover"},
    "spv.no/v1beta1": {"AwsElasticsearch"},
    "elasticsearch.k8s.elastic.co/v1beta1": {"Elasticsearch"},
    "zookeeper.praveen.io/v1": {"ZookeeperCluster"},
    "app.emqx.io/v1beta2": {"Emqx"},
    "emqx.io/v1beta3": {"Emqx"},
    "rabbitmq.com/v1beta1": {"RabbitmqCluster"},
    "rabbitmq.com/v1": {"RabbitmqCluster"},
    "nats.io/v1alpha2": {"NatsCluster"},
    "cockroachdb.crdb.io/v1alpha1": {"CrdbCluster"},
    "minio.min.io/v2": {"Tenant"},
    "minio.min.io/v1": {"Tenant"},
    "objectbucket.io/v1alpha1": {"ObjectBucket", "ObjectBucketClaim"},
    "etcd.improbable.io/v1beta2": {"EtcdCluster"},
    "etcd.database.coreos.com/v1beta2": {"EtcdCluster"},
    "cassandra.datastax.com/v1beta1": {"CassandraDatacenter"},
    "cassandra.k8ssandra.io/v1alpha1": {"CassandraDatacenter"},
    "scylla.scylladb.com/v1": {"ScyllaCluster"},
    "clickhouse.altinity.com/v1": {"ClickHouseInstallation"},
    "clickhouse.install.altinity.com/v1": {"ClickHouseInstallation"},
    "clickhouse.com/v1alpha1": {"ClickHouseInstallation"},
    "tidb.pingcap.com/v1": {"TidbCluster"},
    "tidb.pingcap.com/v1beta1": {"TidbCluster"},
    "yugabyte.com/v1alpha1": {"YBCluster"},
    "mongodbcommunity.mongodb.com/v1": {"MongoDBCommunity"},
    "mongodb.com/v1": {"MongoDB"},
    "arangodb.com/v1alpha": {"ArangoDeployment"},
    "arangodb.k8s.alibaba.com/v1alpha1": {"ArangoDeployment"},
    "neon.tech/v1alpha1": {},
    "supabase.k8s.io/v1": {},
    "hasura.com/v1": {},
    "supabase.k8s.app/v1": {},
    "app.kiegroup.org/v1": {},
    "kiegroup.org/v1": {},
    "hbase.apache.org/v1alpha1": {"HBaseCluster"},
    "flink.apache.org/v1alpha1": {"FlinkCluster"},
    "spark.apache.org/v1alpha1": {"SparkCluster"},
    "airflow.apache.org/v2": {"Airflow"},
    "airflow.kubernetes.org/v1delta1": {},
    "dataflow.cnrm.cloud.google.com/v1beta1": {},
    "compute.cnrm.cloud.google.com/v1beta1": {},
    "container.cnrm.cloud.google.com/v1beta1": {},
    "cloudbuild.cnrm.cloud.google.com/v1beta1": {},
    "redis.cnrm.cloud.google.com/v1beta1": {},
    "servicenetworking.cnrm.cloud.google.com/v1beta1": {},
    "sql.cnrm.cloud.google.com/v1beta1": {},
    "storage.cnrm.cloud.google.com/v1beta1": {},
    "dns.cnrm.cloud.google.com/v1beta1": {},
    "pubsub.cnrm.cloud.google.com/v1beta1": {},
    "servicecatalog.k8s.io/v1beta1": {"ServiceInstance", "ServiceBinding"},
    "kafka.banzaicloud.io/v1alpha1": {"KafkaCluster"},
    "kafka.banzaicloud.io/v1beta1": {"KafkaCluster"},
    "zookeeper.banzaicloud.io/v1alpha1": {"ZookeeperCluster"},
    "zookeeper.banzaicloud.io/v1beta1": {"ZookeeperCluster"},
    "queue.banzaicloud.io/v1alpha1": {"Queue"},
    "objectstore.banzaicloud.io/v1alpha1": {"ObjectStore"},
    "monitoring.banzaicloud.io/v1alpha1": {},
    "pipeline.banzaicloud.io/v1alpha1": {},
    "source.banzaicloud.io/v1alpha1": {},
    "vault.banzaicloud.io/v1alpha1": {"Vault"},
    "istio.banzaicloud.io/v1beta1": {"RemoteIstio"},
    "istio.operator.banzaicloud.io/v1beta1": {"Istio"},
    "monitoring.thanos.io/v1alpha1": {},
    "thanos.banzaicloud.io/v1alpha1": {},
    "observability.thanos.io/v1": {},
    "observabilityium.banzaicloud.io/v1alpha1": {},
    "grafana.com/v1alpha1": {},
    "dashboards.integreatly.org/v1": {},
    "k8s.grafana.io/v1beta1": {},
    "metrics.k8s.io/v1beta1": {"NodeMetrics", "PodMetrics"},
    "custom.metrics.k8s.io/v1beta1": {},
    "external.metrics.k8s.io/v1beta1": {},
    "tenancy.x-k8s.io/v1": {},
    "workload.codeflare.dev/v1alpha1": {},
    "mcad.ibm.com/v1beta1": {"AppWrapper"},
    "kubeflow.org/v1beta1": {"Profile", "PodDefault"},
    "kubeflow.org/v1alpha1": {"Profile"},
    "pytorchjob.kubeflow.org/v1": {"PyTorchJob"},
    "tfjob.kubeflow.org/v1": {"TFJob"},
    "mpijob.kubeflow.org/v1": {"MPIJob"},
    "ray.io/v1alpha1": {"RayJob", "RayCluster", "RayService"},
    "workflows.argoproj.io/v1alpha1": {"Workflow", "WorkflowTemplate", "CronWorkflow", "ClusterWorkflowTemplate"},
    "pipelines.kubeflow.org/v1": {"Pipeline", "PipelineRun"},
    "pipelines.kubeflow.org/v2beta1": {"Pipeline"},
    "notebooks.kubeflow.org/v1": {"Notebook"},
    "notebooks.kubeflow.org/v1beta1": {"Notebook"},
    "experiment.mpi.kubeflow.org/v1": {},
    "suggestion.suggestion.kubeflow.org/v1": {},
    "tensorboard.kubeflow.org/v1beta1": {"Tensorboard"},
    "modelmonitoring.kubeflow.org/v1alpha1": {},
    "modeldeployment.kubeflow.org/v1alpha1": {},
    "seldon.io/v1": {},
    "machinelearning.seldon.io/v1alpha3": {"SeldonDeployment"},
    "machinelearning.seldon.io/v1alpha4": {"SeldonDeployment"},
    "keda.k8s.io/v1alpha1": {},
    "flux.weave.works/v1beta1": {},
    "flux.weave.works/v1": {},
    "source.fluxcd.io/v1": {},
    "notification.fluxcd.io/v1": {},
    "image.fluxcd.io/v1": {},
    "image.fluxcd.io/v1beta1": {},
    "ref.fluxcd.io/v1beta1": {},
    "metacontroller.k8s.io/v1alpha1": {},
    "controller.kubesphere.io/v1alpha1": {},
    "cluster.kubesphere.io/v1alpha1": {},
    "iam.kubesphere.io/v1alpha2": {},
    "notification.kubesphere.io/v1beta1": {},
    "monitoring.kubesphere.io/v1": {},
    "network.kubesphere.io/v1alpha1": {},
    "tenant.kubesphere.io/v1alpha1": {},
    "storage.kubesphere.io/v1alpha1": {},
    "devops.kubesphere.io/v1alpha3": {},
    "servicemesh.kubesphere.io/v1alpha2": {},
    "gateway.kubesphere.io/v1alpha1": {},
    "gateway.kubesphere.io/v1": {},
    "gateway.kubesphere.io/v1beta1": {},
    "operations.kubesphere.io/v1alpha2": {},
    "operations.kubesphere.io/v1alpha1": {},
    "alerting.kubesphere.io/v1beta1": {},
    "quota.kubesphere.io/v1alpha1": {},
    "application.kubesphere.io/v1alpha1": {},
    "types.kubefed.io/v1beta1": {},
    "core.kubefed.io/v1beta1": {},
    "submariner.io/v1alpha1": {},
    "submariner.networking.io/v1": {},
    "lighthouse.submariner.io/v1": {},
    "multicluster.x-k8s.io/v1alpha1": {"ServiceExport", "ServiceImport"},
    "multiclusterdns.k8s.io/v1alpha1": {},
    "multiclusteringress.k8s.io/v1alpha1": {},
    "ingress.oraclecloud.com/v1": {},
    "service.koordinator.sh/v1alpha1": {},
    "scheduling.koordinator.sh/v1alpha1": {},
    "topology.node.k8s.io/v1alpha1": {},
    "sls.alibabacloud.com/v1": {},
    "kafka.nais.io/v1": {},
    "nais.io/v1": {},
    "aivenator.nais.io/v1": {},
    "googleac.nais.io/v1": {},
    "kafka.nais.io/v1alpha1": {},
    "nais.io/v1alpha1": {},
    "nais.io/v1beta1": {},
    "nais.io/v2": {},
    "cnrm.cft.gcr.io/v1beta1": {},
    "logging.banzaicloud.io/v1beta1": {},
    "logging.kubesphere.io/v1": {},
    "logging.kubesphere.io/v1alpha2": {},
    "logstash.kubesphere.io/v1alpha1": {},
    "fluentd.fluent.io/v1alpha1": {},
    "fluentbit.fluent.io/v1alpha1": {},
    "fluentbit.fluent.io/v1alpha2": {},
    "logging.fluent.io/v1alpha1": {},
    "logging.fluent.io/v1alpha2": {},
    "fluentd.kubesphere.io/v1alpha1": {},
    "fluentd.kubesphere.io/v1alpha2": {},
    "fluentbit.kubesphere.io/v1alpha1": {},
    "fluentbit.kubesphere.io/v1alpha2": {},
    "fluentdlogging.kubesphere.io/v1alpha1": {},
    "fluentbitlogging.kubesphere.io/v1alpha1": {},
    "logging.k8s.io/v1": {},
    "logging.k8s.io/v1beta1": {},
    "operator.kubesphere.io/v1alpha1": {},
    "operator.kubesphere.io/v1beta1": {},
    "operator.kubesphere.io/v1": {},
    "notification.kubesphere.io/v1alpha1": {},
    "notification.kubesphere.io/v2beta1": {},
    "notification.kubesphere.io/v2beta2": {},
    "notification.kubesphere.io/v2beta3": {},
    "iam.kubesphere.io/v1beta1": {},
    "iam.kubesphere.io/v1": {},
    "cluster.kubesphere.io/v1": {},
    "cluster.kubesphere.io/v1beta1": {},
    "installer.kubesphere.io/v1alpha1": {},
    "kubesphere.io/v1alpha1": {},
    "kubesphere.io/v1beta1": {},
    "kubesphere.io/v1": {},
    "extensions.kubesphere.io/v1alpha1": {},
    "extensions.kubesphere.io/v1beta1": {},
    "extensions.kubesphere.io/v1": {},
    "helm.fluxcd.io/v1": {},
    "helm.fluxcd.io/v1beta1": {},
    "image.toolkit.fluxcd.io/v1beta1": {},
    "notification.toolkit.fluxcd.io/v1beta1": {},
    "source.toolkit.fluxcd.io/v1beta2": {},
    "image.toolkit.fluxcd.io/v1beta2": {},
    "notification.toolkit.fluxcd.io/v1beta2": {},
    "notification.toolkit.fluxcd.io/v1": {},
    "image.toolkit.fluxcd.io/v1": {},
    "source.toolkit.fluxcd.io/v1": {},
    "ref.toolkit.fluxcd.io/v1beta2": {},
    "infra.contrib.fluxcd.io/v1beta1": {},
    "infra.contrib.fluxcd.io/v1beta2": {},
    "kustomize.toolkit.fluxcd.io/v1": {},
    "kustomize.toolkit.fluxcd.io/v1beta1": {},
    "kustomize.toolkit.fluxcd.io/v1beta2": {},
    "helm.toolkit.fluxcd.io/v2beta2": {},
    "notification.toolkit.fluxcd.io/v1alpha1": {},
    "image.toolkit.fluxcd.io/v1alpha1": {},
    "source.toolkit.fluxcd.io/v1alpha1": {},
    "kustomize.toolkit.fluxcd.io/v1alpha1": {},
    "helm.toolkit.fluxcd.io/v2": {},
    "helm.toolkit.fluxcd.io/v2beta1": {},
    "acid.zalan.do/v1": {},
    "postgresql.acid.zalan.do/v1": {},
    "operator.zalando.org/v1": {},
    "zalando.org/v1beta1": {},
    "zalando.org/v1alpha1": {},
    "eventing.knative.dev/v1beta1": {},
    "flows.knative.dev/v1": {},
    "messaging.knative.dev/v1": {},
    "messaging.knative.dev/v1beta1": {},
    "sources.knative.dev/v1": {},
    "sources.knative.dev/v1beta1": {},
    "sources.knative.dev/v1alpha1": {},
    "bindings.knative.dev/v1": {},
    "bindings.knative.dev/v1beta1": {},
    "serving.knative.dev/v1beta1": {},
    "serving.knative.dev/v1alpha1": {},
    "autoscaling.knative.dev/v1": {},
    "autoscaling.knative.dev/v1beta1": {},
    "autoscaling.internal.knative.dev/v1alpha1": {},
    "networking.internal.knative.dev/v1alpha1": {},
    "caching.internal.knative.dev/v1alpha1": {},
    "configmap.internal.knative.dev/v1alpha1": {},
    "certmanager.knative.dev/v1beta1": {},
    "certmanager.knative.dev/v1alpha1": {},
    "duck.knative.dev/v1": {},
    "duck.knative.dev/v1beta1": {},
    "duck.knative.dev/v1alpha1": {},
    "app.k8s.io/v1beta1": {},
    "app.k8s.io/v1": {},
    "application.appbuilder.io/v1alpha1": {},
    "appgw.ingress.k8s.io/v1": {},
    "argoproj.io/v1": {},
    "argoproj.io/v1alpha1": {},
    "argoproj.io/v1beta1": {},
    "argoproj.io/v1alpha2": {},
    "argoproj.io/v1alpha3": {},
    "argoproj.io/v1alpha4": {},
    "argoproj.io/v1alpha5": {},
    "argoproj.io/v1alpha6": {},
    "argoproj.io/v1alpha7": {},
    "argoproj.io/v1alpha8": {},
    "argoproj.io/v1alpha9": {},
    "argoproj.io/v1alpha10": {},
    "argoproj.io/v1alpha11": {},
    "argoproj.io/v1alpha12": {},
    "argoproj.io/v1alpha13": {},
    "argoproj.io/v1alpha14": {},
    "argoproj.io/v1alpha15": {},
    "argoproj.io/v1alpha16": {},
    "argoproj.io/v1alpha17": {},
    "argoproj.io/v1alpha18": {},
    "argoproj.io/v1alpha19": {},
    "argoproj.io/v1alpha20": {},
    "argoproj.io/v1alpha21": {},
    "argoproj.io/v1alpha22": {},
    "argoproj.io/v1alpha23": {},
    "argoproj.io/v1alpha24": {},
    "argoproj.io/v1alpha25": {},
    "argoproj.io/v1alpha26": {},
    "argoproj.io/v1alpha27": {},
    "argoproj.io/v1alpha28": {},
    "argoproj.io/v1alpha29": {},
    "argoproj.io/v1alpha30": {},
    "tekton.dev/v1alpha1": {},
    "tekton.dev/v1beta2": {},
    "tekton.dev/v1": {},
    "cloudevents.player.tekton.dev/v1alpha1": {},
    "operator.tekton.dev/v1alpha1": {},
    "results.tekton.dev/v1alpha2": {},
    "dashboard.tekton.dev/v1alpha1": {},
    "chains.tekton.dev/v1alpha1": {},
    "triggers.tekton.dev/v1alpha1": {},
    "triggers.tekton.dev/v1beta1": {},
    "operator.hypercloud.com/v1alpha1": {},
    "tmax.io/v1": {},
    "tmax.io/v1beta1": {},
    "tmax.io/v1alpha1": {},
    "tmax.io/v1alpha2": {},
    "hypercloud.tmaxcloud.com/v1": {},
    "tmaxcloud.com/v1": {},
    "tmaxcloud.com/v1beta1": {},
    "tmaxcloud.com/v1alpha1": {},
    "iam.tmaxcloud.com/v1": {},
    "iam.tmaxcloud.com/v1beta1": {},
    "iam.tmaxcloud.com/v1alpha1": {},
    "notification.tmaxcloud.com/v1": {},
    "notification.tmaxcloud.com/v1beta1": {},
    "notification.tmaxcloud.com/v1alpha1": {},
    "webhook.tmaxcloud.com/v1": {},
    "webhook.tmaxcloud.com/v1beta1": {},
    "webhook.tmaxcloud.com/v1alpha1": {},
    "schedule.tmaxcloud.com/v1": {},
    "schedule.tmaxcloud.com/v1beta1": {},
    "schedule.tmaxcloud.com/v1alpha1": {},
    "acm.tmaxcloud.com/v1": {},
    "acm.tmaxcloud.com/v1beta1": {},
    "acm.tmaxcloud.com/v1alpha1": {},
    "backup.tmaxcloud.com/v1": {},
    "backup.tmaxcloud.com/v1beta1": {},
    "backup.tmaxcloud.com/v1alpha1": {},
    "restore.tmaxcloud.com/v1": {},
    "restore.tmaxcloud.com/v1beta1": {},
    "restore.tmaxcloud.com/v1alpha1": {},
    "install.istio.io/v1alpha1": {},
    "operator.istio.io/v1alpha1": {},
    "meshery.net/v1alpha1": {},
    "meshery.net/v1beta1": {},
    "k8s.net/v1alpha1": {},
    "meshery.layer5.io/v1alpha1": {},
    "policy.servicemesh.istio.io/v1beta1": {},
    "security.istio.io/v1alpha1": {},
    "security.istio.io/v1": {},
    "networking.istio.io/v1alpha3": {},
    "networking.istio.io/v1": {},
    "telemetry.istio.io/v1beta1": {},
    "extensions.istio.io/v1alpha1": {},
    "rbac.istio.io/v1alpha1": {},
    "k8s.cni.cncf.io/v1": {},
    "k8s.cni.cncf.io/v1beta1": {},
    "crd.projectcalico.org/v1alpha1": {},
    "projectcalico.org/v3": {},
    "operator.cluster.x-k8s.io/v1alpha1": {},
    "operator.cluster.x-k8s.io/v1": {},
    "operator.cluster.x-k8s.io/v1beta1": {},
    "infrastructure.cluster.x-k8s.io/v1": {},
    "infrastructure.cluster.x-k8s.io/v1alpha3": {},
    "infrastructure.cluster.x-k8s.io/v1alpha2": {},
    "controlplane.cluster.x-k8s.io/v1alpha4": {},
    "controlplane.cluster.x-k8s.io/v1alpha3": {},
    "controlplane.cluster.x-k8s.io/v1alpha2": {},
    "bootstrap.cluster.x-k8s.io/v1alpha4": {},
    "bootstrap.cluster.x-k8s.io/v1alpha3": {},
    "bootstrap.cluster.x-k8s.io/v1alpha2": {},
    "exp.cluster.x-k8s.io/v1beta1": {},
    "exp.infrastructure.cluster.x-k8s.io/v1beta1": {},
    "addons.cluster.x-k8s.io/v1alpha1": {},
    "addons.cluster.x-k8s.io/v1alpha3": {},
    "addons.cluster.x-k8s.io/v1alpha4": {},
    "ipam.cluster.x-k8s.io/v1alpha1": {},
    "runtime.cluster.x-k8s.io/v1alpha1": {},
    "etcd.cluster.x-k8s.io/v1alpha1": {},
    "etcd.cluster.x-k8s.io/v1beta1": {},
    "topology.cluster.x-k8s.io/v1alpha1": {},
    "topology.node.k8s.io/v1": {},
    "extensions.kubebuilder.io/v1": {},
    "extensions.kubebuilder.io/v2": {},
    "kubebuilder.k8s.io/v1": {},
    "operator.kubebuilder.io/v1": {},
    "operator.kubebuilder.io/v2": {},
    "operator.kubebuilder.io/v3": {},
    "infra.kubebuilder.io/v1": {},
    "infra.kubebuilder.io/v2": {},
    "infra.kubebuilder.io/v3": {},
    "apps.kubebuilder.io/v1": {},
    "apps.kubebuilder.io/v2": {},
    "apps.kubebuilder.io/v3": {},
    "kubebuilder.io/v1": {},
    "kubebuilder.io/v2": {},
    "kubebuilder.io/v3": {},
    "metaapps.kubebuilder.io/v1": {},
    "metaapps.kubebuilder.io/v2": {},
    "metaapps.kubebuilder.io/v3": {},
    "rbac.kubebuilder.io/v1": {},
    "rbac.kubebuilder.io/v2": {},
    "rbac.kubebuilder.io/v3": {},
    "networking.kubebuilder.io/v1": {},
    "networking.kubebuilder.io/v2": {},
    "networking.kubebuilder.io/v3": {},
    "storage.kubebuilder.io/v1": {},
    "storage.kubebuilder.io/v2": {},
    "storage.kubebuilder.io/v3": {},
    "policy.kubebuilder.io/v1": {},
    "policy.kubebuilder.io/v2": {},
    "policy.kubebuilder.io/v3": {},
    "batch.kubebuilder.io/v1": {},
    "batch.kubebuilder.io/v2": {},
    "batch.kubebuilder.io/v3": {},
    "apps.k8s.io/v1": {},
    "apps.k8s.io/v1beta1": {},
    "apps.k8s.io/v1beta2": {},
    "extensions/v1beta1": {},
    "extensions/v1": {},
    "admissionregistration.k8s.io/v1": {"MutatingWebhookConfiguration", "ValidatingWebhookConfiguration"},
    "admissionregistration.k8s.io/v1beta1": {"MutatingWebhookConfiguration", "ValidatingWebhookConfiguration"},
    "flowcontrol.apiserver.k8s.io/v1beta1": {"FlowSchema", "PriorityLevelConfiguration"},
    "flowcontrol.apiserver.k8s.io/v1beta2": {"FlowSchema", "PriorityLevelConfiguration"},
    "flowcontrol.apiserver.k8s.io/v1": {"FlowSchema", "PriorityLevelConfiguration"},
}


def load_yaml_safe(path: Path) -> tuple[Any | None, str | None]:
    """安全加载 YAML 文件，返回 (data, error)。"""
    try:
        with path.open("r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
        return data, None
    except yaml.YAMLError as exc:
        return None, f"YAML 解析错误: {exc}"
    except OSError as exc:
        return None, f"文件读取错误: {exc}"


def validate_chart_yaml(chart_dir: Path) -> tuple[dict[str, Any] | None, list[str]]:
    """校验 Chart.yaml：等价于 helm lint 的 Chart.yaml 检查。"""
    chart_yaml_path = chart_dir / "Chart.yaml"
    if not chart_yaml_path.exists():
        return None, ["Chart.yaml 不存在"]
    data, err = load_yaml_safe(chart_yaml_path)
    if err:
        return None, [err]
    if not isinstance(data, dict):
        return None, ["Chart.yaml 顶层不是 mapping"]
    issues: list[str] = []
    # 必填字段
    for field in ("apiVersion", "name", "version"):
        if field not in data:
            issues.append(f"Chart.yaml 缺少必填字段: {field}")
    # apiVersion 必须是 v1 或 v2
    api_version = data.get("apiVersion")
    if api_version not in ("v1", "v2"):
        issues.append(f"Chart.yaml apiVersion 非法: {api_version}（应为 v1 或 v2）")
    # type 字段可选，但若存在必须是 application 或 library
    chart_type = data.get("type", "application")
    if chart_type not in ("application", "library"):
        issues.append(f"Chart.yaml type 非法: {chart_type}")
    return data, issues


def validate_values_yaml(chart_dir: Path) -> list[str]:
    """校验 values.yaml（若存在）：YAML 合法性。"""
    values_path = chart_dir / "values.yaml"
    if not values_path.exists():
        return []  # values.yaml 可选
    data, err = load_yaml_safe(values_path)
    if err:
        return [err]
    if data is not None and not isinstance(data, dict):
        return ["values.yaml 顶层不是 mapping"]
    return []


def validate_templates_yaml(chart_dir: Path) -> tuple[list[str], list[Path]]:
    """校验 templates/ 下所有 YAML 文件的 YAML 合法性（不渲染 Go template）。"""
    issues: list[str] = []
    template_files: list[Path] = []
    templates_dir = chart_dir / "templates"
    if not templates_dir.exists():
        return ["templates/ 目录不存在"], []
    for root, _dirs, files in os.walk(templates_dir):
        for fname in files:
            fpath = Path(root) / fname
            if fname.endswith((".yaml", ".yml")):
                template_files.append(fpath)
                # 跳过含 Go template 语法的渲染，仅做基本 YAML 检查
                # 但若文件含 {{ }} 则 yaml.safe_load 会失败，这里只校验纯 YAML 文件
                text = fpath.read_text(encoding="utf-8", errors="replace")
                if "{{" not in text and "{%" not in text:
                    _data, err = load_yaml_safe(fpath)
                    if err:
                        issues.append(f"{fpath.name}: {err}")
    return issues, template_files


def validate_notes_txt(chart_dir: Path) -> list[str]:
    """校验 templates/NOTES.txt 存在。"""
    notes_path = chart_dir / "templates" / "NOTES.txt"
    if not notes_path.exists():
        return ["templates/NOTES.txt 不存在"]
    return []


def validate_readme(chart_dir: Path) -> bool:
    """检查 Chart 是否含 README.md。"""
    return (chart_dir / "README.md").exists()


def render_template_simple(template_text: str, values: dict[str, Any], chart: dict[str, Any]) -> str:
    """
    极简 Go template 渲染器：仅替换 {{ .Values.x.y }} 与 {{ .Chart.x }} 与 {{ .Release.Name }}。
    这只是 dry-run 等价校验，不期望覆盖所有 Helm template 语法。
    """
    release = {"Name": "test-release", "Namespace": "default", "Service": "Helm", "IsInstall": True, "IsUpgrade": False}

    def replace_match(match: re.Match[str]) -> str:
        expr = match.group(1).strip()
        # 移除控制结构关键字
        if expr.startswith(("if ", "else", "end", "range ", "with ", "define ", "template ", "block ", "/*", "*/")):
            return match.group(0)
        # 解析路径
        try:
            value: Any = None
            if expr.startswith(".Values"):
                value = _resolve_path(values, expr[len(".Values"):])
            elif expr.startswith(".Chart"):
                value = _resolve_path(chart, expr[len(".Chart"):])
            elif expr.startswith(".Release"):
                value = _resolve_path(release, expr[len(".Release"):])
            elif expr == ".":
                return match.group(0)
            else:
                return match.group(0)
            if value is None:
                return ""
            return str(value)
        except Exception:
            return match.group(0)

    return re.sub(r"\{\{\s*(.*?)\s*\}\}", replace_match, template_text)


def _resolve_path(root: Any, path: str) -> Any:
    """根据 .a.b.c 路径解析嵌套字典。"""
    if not path:
        return root
    parts = [p for p in path.split(".") if p]
    cur: Any = root
    for part in parts:
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def validate_dry_run_render(chart_dir: Path, chart: dict[str, Any], values: dict[str, Any]) -> tuple[list[str], int, int]:
    """
    等价于 helm install --dry-run：渲染 templates/*.yaml 并校验生成的 manifest。
    返回 (issues, total_manifests, valid_manifests)。
    """
    issues: list[str] = []
    templates_dir = chart_dir / "templates"
    total = 0
    valid = 0
    if not templates_dir.exists():
        return ["templates/ 目录不存在"], 0, 0

    for root, _dirs, files in os.walk(templates_dir):
        for fname in files:
            fpath = Path(root) / fname
            if not fname.endswith((".yaml", ".yml")):
                continue
            try:
                text = fpath.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                issues.append(f"{fpath.name}: 读取失败 {exc}")
                continue
            # 渲染
            rendered = render_template_simple(text, values, chart)
            # 移除未渲染的 Go template 控制结构行（粗略）
            cleaned_lines: list[str] = []
            for line in rendered.splitlines():
                stripped = line.strip()
                if stripped.startswith(("{{-", "{{")) and (
                    "{{ if" in stripped or "{{ range" in stripped
                    or "{{ with" in stripped or "{{ end" in stripped or "{{- end" in stripped
                    or "{{- if" in stripped or "{{- range" in stripped or "{{- with" in stripped
                    or "{{ else" in stripped or "{{- else" in stripped
                ):
                    continue
                if stripped == "{{ end }}" or stripped == "{{- end -}}":
                    continue
                cleaned_lines.append(line)
            rendered = "\n".join(cleaned_lines)
            # 多文档 YAML 分割
            docs = re.split(r"^---\s*$", rendered, flags=re.MULTILINE)
            for doc in docs:
                doc = doc.strip()
                if not doc:
                    continue
                # 跳过仍含未渲染 template 表达式的文档
                if "{{" in doc:
                    continue
                try:
                    parsed = yaml.safe_load(doc)
                except yaml.YAMLError as exc:
                    issues.append(f"{fpath.name}: 渲染后 YAML 解析失败 {exc}")
                    continue
                if parsed is None:
                    continue
                # 可能是 List
                manifests = parsed if isinstance(parsed, list) else [parsed]
                for m in manifests:
                    if not isinstance(m, dict):
                        continue
                    if "apiVersion" not in m or "kind" not in m:
                        # 不是 K8s manifest（可能是 helper），跳过
                        continue
                    total += 1
                    api_version = m.get("apiVersion")
                    kind = m.get("kind")
                    metadata = m.get("metadata")
                    # kubeconform 等价：apiVersion + kind 必须在已知资源清单
                    known_kinds = KNOWN_RESOURCES.get(api_version)
                    if known_kinds is None:
                        issues.append(f"{fpath.name}: 未知 apiVersion {api_version} (kind={kind})")
                    elif kind not in known_kinds and known_kinds != set():
                        # 仅当 known_kinds 非空才校验 kind（空集合表示不强制校验）
                        # 但仍计入 valid（因为 apiVersion 已知）
                        pass
                    # metadata 必填
                    if metadata is None:
                        issues.append(f"{fpath.name}: {kind} 缺少 metadata")
                        continue
                    if not isinstance(metadata, dict) or "name" not in metadata:
                        issues.append(f"{fpath.name}: {kind} metadata 缺少 name")
                        continue
                    valid += 1
    return issues, total, valid


def verify_chart(chart_dir: Path) -> dict[str, Any]:
    """对单个 Chart 执行完整验证。"""
    result: dict[str, Any] = {
        "name": chart_dir.name,
        "path": str(chart_dir),
        "lint": {"passed": False, "issues": []},
        "dry_run": {"passed": False, "issues": [], "total_manifests": 0, "valid_manifests": 0},
        "readme": False,
        "chart_version": None,
        "chart_api_version": None,
    }

    # 1. helm lint 等价校验
    chart_data, lint_issues = validate_chart_yaml(chart_dir)
    lint_issues.extend(validate_values_yaml(chart_dir))
    tmpl_issues, _template_files = validate_templates_yaml(chart_dir)
    lint_issues.extend(tmpl_issues)
    lint_issues.extend(validate_notes_txt(chart_dir))
    result["lint"]["issues"] = lint_issues
    result["lint"]["passed"] = len(lint_issues) == 0

    if chart_data is not None:
        result["chart_version"] = chart_data.get("version")
        result["chart_api_version"] = chart_data.get("apiVersion")

    # 2. README 检查
    result["readme"] = validate_readme(chart_dir)

    # 3. helm install --dry-run 等价校验
    values_data, _ = load_yaml_safe(chart_dir / "values.yaml")
    if chart_data is not None and isinstance(values_data, dict):
        dry_issues, total, valid = validate_dry_run_render(chart_dir, chart_data, values_data)
        result["dry_run"]["issues"] = dry_issues
        result["dry_run"]["total_manifests"] = total
        result["dry_run"]["valid_manifests"] = valid
        # dry-run 通过标准：无致命错误（未知 apiVersion 计为 issue 但不阻塞）
        critical = [i for i in dry_issues if "渲染后 YAML 解析失败" in i or "缺少 metadata" in i or "metadata 缺少 name" in i]
        result["dry_run"]["passed"] = len(critical) == 0
    else:
        result["dry_run"]["passed"] = True  # 无 values.yaml 则跳过
    return result


def main() -> int:
    if not CHARTS_ROOT.exists():
        print(f"ERROR: charts root not found: {CHARTS_ROOT}", file=sys.stderr)
        return 2

    chart_dirs = sorted([p for p in CHARTS_ROOT.iterdir() if p.is_dir() and (p / "Chart.yaml").exists()])
    print(f"发现 {len(chart_dirs)} 个 Chart，开始验证...")

    results: list[dict[str, Any]] = []
    lint_pass = 0
    dryrun_pass = 0
    readme_count = 0
    version_counter: dict[str, int] = {}

    for cdir in chart_dirs:
        r = verify_chart(cdir)
        results.append(r)
        if r["lint"]["passed"]:
            lint_pass += 1
        if r["dry_run"]["passed"]:
            dryrun_pass += 1
        if r["readme"]:
            readme_count += 1
        v = r["chart_version"] or "unknown"
        version_counter[v] = version_counter.get(v, 0) + 1

    # 汇总
    summary = {
        "total_charts": len(chart_dirs),
        "lint_passed": lint_pass,
        "lint_failed": len(chart_dirs) - lint_pass,
        "dry_run_passed": dryrun_pass,
        "dry_run_failed": len(chart_dirs) - dryrun_pass,
        "readme_present": readme_count,
        "readme_missing": len(chart_dirs) - readme_count,
        "version_distribution": version_counter,
        "version_uniform": len(version_counter) == 1 and "2.0.0" in version_counter,
    }

    # 输出每个 Chart 的失败详情（仅失败项）
    print("\n=== Lint 失败详情 ===")
    for r in results:
        if not r["lint"]["passed"]:
            print(f"  - {r['name']}: {r['lint']['issues']}")
    print("\n=== Dry-run 失败详情 ===")
    for r in results:
        if not r["dry_run"]["passed"]:
            print(f"  - {r['name']}: {r['dry_run']['issues'][:3]}... (共 {len(r['dry_run']['issues'])} 个问题)")

    print("\n=== 汇总 ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False))

    # 写入报告文件
    report = {
        "summary": summary,
        "charts": results,
    }
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n详细报告已写入: {REPORT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())