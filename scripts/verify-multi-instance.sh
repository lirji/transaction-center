#!/usr/bin/env bash
# 专属MySQL及本次创建的Java进程；故障只针对测试持有的PID，不操作共享服务。
set -euo pipefail
trade_multi_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$trade_multi_root"
docker info >/dev/null
case "$(uname -s)" in Linux|Darwin) ;; *) echo '需要支持POSIX STOP/CONT的本机环境' >&2; exit 1 ;; esac
mvn --batch-mode -Dtrade.test.mysql-image="${TRADE_TEST_MYSQL_IMAGE:-mysql:8.4.11}" \
  -Dtest=MultiInstanceIT,WorkerRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false verify
