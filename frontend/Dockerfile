# syntax=docker/dockerfile:1
# 多阶段构建：构建阶段
ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/node:22-alpine AS build

LABEL org.opencontainers.image.source="https://github.com/Levango7/DataEngineBDP"
LABEL org.opencontainers.image.description="DataEngineBDP frontend console"

# 上下文已提前设置为 frontend/，构建过程与原生完全一致
WORKDIR /build

# 优先拷贝依赖定义，利用 Docker 层缓存加速依赖安装
COPY package.json package-lock.json* ./

RUN npm ci

# 拷贝源码并构建产物
COPY . .
RUN npm run build

# 运行阶段：最小化镜像
# 用 nginx:alpine-slim 而非 nginx:alpine（台账 #74/#87「漂一次打一次」循环的收口，主理人授权「换别的方式」）：
# slim 变体不含 libexpat / libx11 / curl / brotli-libs 等在浮动 tag 上反复曝 CVE 的包，
# 本机 trivy v0.71.1 与 CI 完全同口径（--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed）实测
# **0 命中**（同期 nginx:alpine = 3 条 libexpat HIGH：CVE-2026-113351/-117610/-118370）；
# 体积 19.7MB vs 89.3MB，`--with-` 模块数同为 30，`nginx -t` 用本仓 nginx.conf 实测 rc=0。
# 官方多架构（amd64+arm64）不变。旧 apk upgrade 行随之删除（slim 无那些包可升）。
FROM ${BASE_REGISTRY}/library/nginx:alpine-slim

COPY --from=build /build/dist /usr/share/nginx/html

# 注入自定义服务器块（替代默认 conf.d/default.conf）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 非 root 运行补正（本机实跑发现）：官方镜像 pid 路径 /run/nginx.pid 与
# **编进二进制的** temp 路径 /var/cache/nginx/* 均为 root 所有，USER nginx 下
# master 起不来（`mkdir() "/var/cache/nginx/client_temp" failed (13: Permission denied)`；
# 旧 nginx:alpine 镜像同款失败——既有缺陷，非本次换基座引入。temp 路径不在
# nginx.conf 里、是 configure 时写死二进制的，sed 改 conf 改不到，故双管齐下：
# ① pid 挪进任何用户可写的 /tmp（nginx-unprivileged 同款做法）；
# ② temp 目录预建并归属 nginx——nginx 仅在目录缺失时才尝试 mkdir，预建即免权限问题。
RUN sed -i 's|/run/nginx.pid|/tmp/nginx.pid|g' /etc/nginx/nginx.conf \
 && mkdir -p /var/cache/nginx/client_temp /var/cache/nginx/proxy_temp \
             /var/cache/nginx/fastcgi_temp /var/cache/nginx/uwsgi_temp \
             /var/cache/nginx/scgi_temp \
 && chown -R nginx:nginx /var/cache/nginx

# 非 root 运行：nginx 用户监听 8080 端口（避免 privileged port 80）
USER nginx

# 健康检查：静态文件是否可访问
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null 2>&1 || exit 1

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
