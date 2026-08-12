{{- /*
Helm Chart 模板 - 模板导入 Job
用途：部署一个 Job，拉取 ConfigMap 中的模板资产，依次导入到 Doris/DolphinScheduler/Superset/Keycloak
*/ -}}
{{- if .Values.importJob.enabled }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ .Values.configMap.namePrefix }}-import
  namespace: {{ .Values.namespace }}
  labels:
    app.kubernetes.io/name: manufacturing-template
    app.kubernetes.io/instance: {{ .Release.Name }}
    app.kubernetes.io/version: "1.0.0"
    app.kubernetes.io/managed-by: {{ .Release.Service }}
    shuqing.io/template: manufacturing-template
  annotations:
    "helm.sh/hook": post-install,post-upgrade
    "helm.sh/hook-weight": "0"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  backoffLimit: {{ .Values.importJob.backoffLimit }}
  activeDeadlineSeconds: {{ .Values.importJob.activeDeadlineSeconds }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: manufacturing-template
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      restartPolicy: OnFailure
      volumes:
        - name: template-assets
          configMap:
            name: {{ .Values.configMap.namePrefix }}-assets
      containers:
        - name: template-importer
          image: "{{ .Values.importJob.image.repository }}:{{ .Values.importJob.image.tag }}"
          imagePullPolicy: {{ .Values.importJob.image.pullPolicy }}
          volumeMounts:
            - name: template-assets
              mountPath: /templates
              readOnly: true
          env:
            - name: TEMPLATE_NAME
              value: {{ .Values.template.name | quote }}
            - name: TEMPLATE_VERSION
              value: {{ .Values.template.version | quote }}
            - name: DORIS_FE_HOST
              value: {{ .Values.target.doris.feHost | quote }}
            - name: DORIS_FE_PORT
              value: {{ .Values.target.doris.fePort | quote }}
            - name: DORIS_DATABASE
              value: {{ .Values.target.doris.database | quote }}
            - name: DORIS_USER
              value: {{ .Values.target.doris.user | quote }}
            - name: DORIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.doris.passwordSecret.name }}
                  key: {{ .Values.target.doris.passwordSecret.key }}
            - name: DS_HOST
              value: {{ .Values.target.dolphinscheduler.host | quote }}
            - name: DS_PORT
              value: {{ .Values.target.dolphinscheduler.port | quote }}
            - name: DS_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.dolphinscheduler.tokenSecret.name }}
                  key: {{ .Values.target.dolphinscheduler.tokenSecret.key }}
            - name: SUPERSET_HOST
              value: {{ .Values.target.superset.host | quote }}
            - name: SUPERSET_PORT
              value: {{ .Values.target.superset.port | quote }}
            - name: SUPERSET_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.superset.tokenSecret.name }}
                  key: {{ .Values.target.superset.tokenSecret.key }}
            - name: KEYCLOAK_HOST
              value: {{ .Values.target.keycloak.host | quote }}
            - name: KEYCLOAK_PORT
              value: {{ .Values.target.keycloak.port | quote }}
            - name: KEYCLOAK_REALM
              value: {{ .Values.target.keycloak.realm | quote }}
            - name: KEYCLOAK_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.keycloak.adminSecret.name }}
                  key: {{ .Values.target.keycloak.adminSecret.key }}
            - name: IOTDB_HOST
              value: {{ .Values.target.iotdb.host | quote }}
            - name: IOTDB_PORT
              value: {{ .Values.target.iotdb.port | quote }}
            - name: IOTDB_USER
              value: {{ .Values.target.iotdb.user | quote }}
            - name: IOTDB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.target.iotdb.passwordSecret.name }}
                  key: {{ .Values.target.iotdb.passwordSecret.key }}
            - name: FLINK_JM_HOST
              value: {{ .Values.target.flink.jobmanagerHost | quote }}
            - name: FLINK_JM_PORT
              value: {{ .Values.target.flink.jobmanagerPort | quote }}
            - name: SPARK_MASTER_HOST
              value: {{ .Values.target.spark.masterHost | quote }}
            - name: SPARK_MASTER_PORT
              value: {{ .Values.target.spark.masterPort | quote }}
            - name: RBAC_SYNC_ENABLED
              value: {{ .Values.rbacSync.enabled | quote }}
            - name: RBAC_ROLLBACK_ON_FAILURE
              value: {{ .Values.rbacSync.rollbackOnFailure | quote }}
            - name: IOTDB_INGESTION_ENABLED
              value: {{ .Values.iotdbIngestion.enabled | quote }}
          command:
            - /bin/sh
            - -c
            - |
              set -e
              echo "=========================================="
              echo "制造行业模板导入开始"
              echo "模板: ${TEMPLATE_NAME} v${TEMPLATE_VERSION}"
              echo "=========================================="
              echo "[1/5] 导入 DDL 到 Doris..."
              for sql_file in /templates/ddl/*.sql; do
                echo "  执行: $(basename $sql_file)"
                mysql -h ${DORIS_FE_HOST} -P ${DORIS_FE_PORT} -u ${DORIS_USER} -p${DORIS_PASSWORD} ${DORIS_DATABASE} < $sql_file
              done
              echo "[1/5] DDL 导入完成"
              echo "[2/5] 导入 DAG 到 DolphinScheduler..."
              for dag_file in /templates/dag/*.py; do
                echo "  导入: $(basename $dag_file)"
                curl -s -X POST "http://${DS_HOST}:${DS_PORT}/dolphinscheduler/projects/import" \
                  -H "Authorization: Bearer ${DS_TOKEN}" \
                  -F "file=@${dag_file}"
              done
              echo "[2/5] DAG 导入完成"
              echo "[3/5] 导入 Dashboard 到 Superset..."
              for dash_file in /templates/dashboards/*.json; do
                echo "  导入: $(basename $dash_file)"
                curl -s -X POST "http://${SUPERSET_HOST}:${SUPERSET_PORT}/api/v1/dashboard/import/" \
                  -H "Authorization: Bearer ${SUPERSET_TOKEN}" \
                  -F "formData=@${dash_file}"
              done
              echo "[3/5] Dashboard 导入完成"
              echo "[4/5] 部署 IoTDB 接入作业到 Flink..."
              if [ "${IOTDB_INGESTION_ENABLED}" = "true" ]; then
                echo "  提交 IoTDB 数据接入 Flink 作业..."
                curl -s -X POST "http://${FLINK_JM_HOST}:${FLINK_JM_PORT}/jars/upload" \
                  -H "Authorization: Bearer ${DS_TOKEN}" \
                  -F "jarfile=@/templates/iotdb/flink-iotdb-connector.yaml"
                echo "  IoTDB 接入作业已提交"
              else
                echo "  IoTDB 接入已禁用，跳过"
              fi
              echo "[4/5] IoTDB 接入配置完成"
              if [ "${RBAC_SYNC_ENABLED}" = "true" ]; then
                echo "[5/5] 导入 RBAC 到 Keycloak..."
                KC_ADMIN_TOKEN=$(curl -s -X POST "http://${KEYCLOAK_HOST}:${KEYCLOAK_PORT}/realms/master/protocol/openid-connect/token" \
                  -d "client_id=admin-cli" \
                  -d "username=admin" \
                  -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
                  -d "grant_type=password" | jq -r .access_token)
                curl -s -X POST "http://${KEYCLOAK_HOST}:${KEYCLOAK_PORT}/admin/realms" \
                  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
                  -H "Content-Type: application/json" \
                  -d "{\"realm\":\"${KEYCLOAK_REALM}\",\"enabled\":true}"
                for rbac_file in /templates/rbac/*.yaml; do
                  echo "  导入 RBAC: $(basename $rbac_file)"
                  curl -s -X POST "http://${KEYCLOAK_HOST}:${KEYCLOAK_PORT}/admin/realms/${KEYCLOAK_REALM}/import" \
                    -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
                    -H "Content-Type: application/yaml" \
                    --data-binary "@${rbac_file}"
                done
                echo "[5/5] RBAC 导入完成"
              else
                echo "[5/5] RBAC 同步已禁用，跳过"
              fi
              echo "=========================================="
              echo "制造行业模板导入完成"
              echo "=========================================="
          resources:
            {{- toYaml .Values.importJob.resources | nindent 12 }}
{{- end }}