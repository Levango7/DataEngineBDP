{{/*
owasp-crs 共用模板片段。
*/}}

{{- define "owasp-crs.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "owasp-crs.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "owasp-crs.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "owasp-crs.selectorLabels" -}}
app.kubernetes.io/name: {{ include "owasp-crs.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "owasp-crs.labels" -}}
helm.sh/chart: {{ include "owasp-crs.chart" . }}
{{ include "owasp-crs.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: shuqing-bigdata
app.kubernetes.io/component: edge-waf
{{- end -}}

{{/*
边缘 WAF 的后端 URL。
上游镜像以反代模式工作：nginx 的 proxy_pass 指向 ${BACKEND}
（上游 nginx/templates/includes/proxy_backend.conf.template:18），必须是完整 URL。
values.backend.url 非空时直接采用，否则按 scheme/serviceName/namespace/port 拼装 FQDN。
*/}}
{{- define "owasp-crs.backendUrl" -}}
{{- if .Values.backend.url -}}
{{- .Values.backend.url -}}
{{- else -}}
{{- printf "%s://%s.%s.svc.cluster.local:%d" .Values.backend.scheme .Values.backend.serviceName .Values.backend.namespace (int .Values.backend.port) -}}
{{- end -}}
{{- end -}}
