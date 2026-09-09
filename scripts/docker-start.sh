#!/usr/bin/env bash
# 只管理交易应用，数据库及中间件生命周期由dev_infra负责。
set -euo pipefail
for trade_tool in docker python3; do
  if ! command -v "$trade_tool" >/dev/null 2>&1; then
    echo "缺少启动依赖：$trade_tool" >&2
    exit 1
  fi
done
trade_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
trade_env="${TRADE_ENV_FILE:-$trade_root/deploy/.env}"
trade_compose=(docker compose --project-directory "$trade_root/deploy" -f "$trade_root/deploy/compose.yaml")
if [[ -f "$trade_env" ]]; then
  trade_compose+=(--env-file "$trade_env")
elif [[ -n "${TRADE_ENV_FILE:-}" ]]; then
  echo "配置文件不存在：$trade_env" >&2
  exit 1
fi
trade_local_build=false
for trade_arg in "$@"; do
  case "$trade_arg" in
    --worker) trade_compose+=(--profile worker) ;;
    --local-build) trade_local_build=true; export TRADE_BUILD_TARGET=prebuilt TRADE_CONSOLE_BUILD_TARGET=prebuilt ;;
    *) echo "用法：$0 [--worker] [--local-build]；可通过TRADE_ENV_FILE指定配置文件。" >&2; exit 2 ;;
  esac
done
# 只校验，不打印含有凭据的完整Compose配置。
if ! "${trade_compose[@]}" config --quiet; then
  echo "请填写deploy/.env（参照.env.example），或导出所需TRADE环境变量。" >&2
  exit 1
fi
docker info >/dev/null
# 读取Compose解析后的网络名，避免source执行.env里的内容。
trade_network="$("${trade_compose[@]}" config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["networks"]["dev-infra"]["name"])')"
if ! docker network inspect "$trade_network" >/dev/null 2>&1; then
  echo "缺少共享网络$trade_network，请先启动dev_infra。" >&2
  exit 1
fi
if [[ "$trade_local_build" == true ]]; then
  command -v pnpm >/dev/null || { echo "本机构建前端需要pnpm。" >&2; exit 1; }
  (cd "$trade_root" && mvn --batch-mode -DskipTests package)
  (cd "$trade_root/frontend/apps/console" && pnpm install --frozen-lockfile && pnpm build)
fi
"${trade_compose[@]}" up --build --detach --wait --wait-timeout 600
"${trade_compose[@]}" ps
echo "交易应用已通过容器健康检查。"
