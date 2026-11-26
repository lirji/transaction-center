FROM node:22-bookworm-slim AS build
WORKDIR /src
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
COPY frontend/apps/console/package.json frontend/apps/console/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/apps/console/ ./
RUN pnpm build

# 固定已验证的镜像摘要，运行时只提供静态文件和同源API代理。
FROM nginx@sha256:8541484afbc9c8a5a8a99b379568ebbc957f658583ec9448fc43104229c03cf8 AS runtime-base
COPY deploy/console.nginx.conf /etc/nginx/nginx.conf
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=4 \
  CMD curl --fail --silent http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off;"]

# 构建目录可能带有宿主环境的限制权限，统一权限后以非 root Nginx 提供静态资源。
FROM runtime-base AS prebuilt
COPY frontend/apps/console/dist/ /usr/share/nginx/html/
RUN chmod -R u=rwX,go=rX /usr/share/nginx/html
USER nginx

FROM runtime-base AS runtime
COPY --from=build /src/dist/ /usr/share/nginx/html/
RUN chmod -R u=rwX,go=rX /usr/share/nginx/html
USER nginx
