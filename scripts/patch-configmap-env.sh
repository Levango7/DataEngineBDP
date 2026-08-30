#!/usr/bin/env bash
# 一次性脚本（纯 bash）：为全部 chart 的 configmap.yaml 增加 env: 块渲染。
# 背景：各 chart values 的 env: 块此前无模板消费（死配置）；
# configmap 只渲染 config:（文件语义），local values 误注入 env 语义键导致渲染失败。
set -u
cd "$(dirname "$0")/.."

patched=0
skipped=0
while IFS= read -r f; do
  if grep -q 'range $key, $val := .Values.env' "$f"; then
    skipped=$((skipped+1))
    continue
  fi
  awk 'BEGIN{done=0}
    /^data:$/ && done==0 {
      print
      print "  {{- range $key, $val := .Values.env }}"
      print "  {{ $key }}: {{ $val | toString | quote }}"
      print "  {{- end }}"
      done=1
      next
    }
    {print}
  ' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
  patched=$((patched+1))
done < <(find design/deploy/charts platform/industry-templates/charts -name configmap.yaml -path "*/templates/*" 2>/dev/null)
echo "patched=$patched skipped=$skipped"
