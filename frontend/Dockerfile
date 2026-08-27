# syntax=docker/dockerfile:1
# 多阶段构建：构建阶段
FROM node:22-alpine AS build

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
FROM nginx:alpine

COPY --from=build /build/dist /usr/share/nginx/html

# 注入自定义服务器块（替代默认 conf.d/default.conf）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 非 root 运行：nginx 用户监听 8080 端口（避免 privileged port 80）
USER nginx

# 健康检查：静态文件是否可访问
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null 2>&1 || exit 1

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
