{{/*
Expand the name of the chart.
*/}}
{{- define "infra-provider-baremetal.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "infra-provider-baremetal.fullname" -}}
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

{{/*
Chart labels.
*/}}
{{- define "infra-provider-baremetal.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "infra-provider-baremetal.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "infra-provider-baremetal.selectorLabels" -}}
app.kubernetes.io/name: {{ include "infra-provider-baremetal.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "infra-provider-baremetal.configmapName" -}}
{{- printf "%s-config" (include "infra-provider-baremetal.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "infra-provider-baremetal.serviceName" -}}
{{- include "infra-provider-baremetal.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "infra-provider-baremetal.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "infra-provider-baremetal.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
