#!/usr/bin/env bash
# 机器合同与反例检查独立于数据库，可在本地或任意CI平台快速执行。
set -euo pipefail
trade_contract_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$trade_contract_root"
python3 scripts/contracts/error_contract.py
if command -v uv >/dev/null 2>&1; then
  uv run --with-requirements scripts/contracts/requirements.txt python scripts/contracts/asyncapi_contract.py
  uv run --with-requirements scripts/contracts/requirements.txt python -m unittest discover -s scripts/contracts -p 'test_*.py'
else
  # 无uv时由构建环境按带哈希requirements预装依赖，不隐式写全局Python环境。
  python3 scripts/contracts/asyncapi_contract.py
  python3 -m unittest discover -s scripts/contracts -p 'test_*.py'
fi
