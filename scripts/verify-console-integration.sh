#!/usr/bin/env bash
# 启动Testcontainers隔离MySQL和随机端口API/OIDC，Playwright访问真实接口，不改共享环境。
set -euo pipefail
trade_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
for trade_tool in docker mvn pnpm; do
  command -v "$trade_tool" >/dev/null || { echo "缺少工具：$trade_tool" >&2; exit 1; }
done
docker info >/dev/null
cd "$trade_root"
mvn --batch-mode -Dtrade.test.mysql-image="${TRADE_TEST_MYSQL_IMAGE:-mysql:8.4.11}" \
  -Dtest=ConsoleIntegrationIT -Dsurefire.failIfNoSpecifiedTests=false test
