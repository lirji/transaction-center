#!/usr/bin/env bash
# 本地与CI共用验收入口；没有Docker时失败，禁止静默跳过真实MySQL测试。
set -euo pipefail
trade_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$trade_script_dir/.."
docker info >/dev/null
mvn --batch-mode verify

./scripts/verify-contracts.sh
