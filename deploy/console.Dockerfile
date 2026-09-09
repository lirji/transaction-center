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
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=4 \
  CMD curl --fail --silent http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off;"]

FROM runtime-base AS prebuilt
COPY frontend/apps/console/dist/ /usr/share/nginx/html/

FROM runtime-base AS runtime
COPY --from=build /src/dist/ /usr/share/nginx/html/
