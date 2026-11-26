#!/usr/bin/env python3
"""为指定租户有界补录业务展示号。原生订单标识、外键和资金记录由业务系统持有。"""

import argparse
import re
import secrets
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Dataset:
    table: str
    primary_key: str
    prefix: str
    numeric_tenant: bool
    timestamp_column: str = "created_at"
    iso_timestamp: bool = False


DATASETS = {
    "trade-orders": Dataset("tc_order", "id", "TRD-ORD", True),
    "trade-payments": Dataset("tc_payment_intent", "id", "TRD-PAY", True),
    "trade-refunds": Dataset("tc_refund", "id", "TRD-RFD", True),
    "trade-fulfillments": Dataset("tc_order_fulfillment", "id", "TRD-FUL", True),
    "trade-returns": Dataset("tc_return_request", "id", "TRD-RTN", True),
    "trade-return-receipts": Dataset("tc_return_receipt", "id", "TRD-RMA", True),
    "commerce-orders": Dataset("order_record", "order_id", "COM-ORD", False),
    "commerce-payments": Dataset("payment_attempt", "payment_id", "COM-PAY", False),
    "commerce-refunds": Dataset("payment_refund", "refund_id", "COM-RFD", False),
    "commerce-aftersales": Dataset("aftersales_case", "case_id", "COM-AFS", False),
    "marketing-campaigns": Dataset("mk_campaign", "campaign_id", "MKT-CMP", False, iso_timestamp=True),
    "marketing-approvals": Dataset("mk_approval_case", "case_id", "MKT-APR", False, iso_timestamp=True),
    "marketing-funding": Dataset("mk_promotion_application", "application_id", "MKT-FAP", False, iso_timestamp=True),
    "marketing-ledger": Dataset("mk_funding_ledger", "ledger_id", "MKT-LED", False, "occurred_at", True),
}
IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,64}")
POSITIVE_ID = re.compile(r"[1-9][0-9]{0,19}")
BUSINESS_DAY = re.compile(r"[0-9]{6}")
SUFFIX_SPACE = 10_000_000_000
COLLISION_RETRIES = 4


class MysqlError(RuntimeError):
    """保留数据库错误编号，避免输出含连接或业务数据的错误正文。"""

    def __init__(self, stderr):
        match = re.search(r"\bERROR (\d+)\b", stderr)
        self.number = int(match.group(1)) if match else None
        super().__init__(f"MySQL operation failed ({self.number or 'connection or client error'})")


def sql_identifier(value, numeric=False):
    """编号来自已验证的主键和租户，文本输入禁止引号与转义字符。"""
    if numeric:
        if not POSITIVE_ID.fullmatch(value) or int(value) > 2**64 - 1:
            raise ValueError("numeric ID is outside the unsigned database range")
        return value
    if not IDENTIFIER.fullmatch(value):
        raise ValueError("text ID has an invalid format")
    return f"'{value}'"


def mysql(args, query):
    """用独立短连接执行一批，标准输出只返回查询结果。"""
    command = [args.mysql, f"--defaults-extra-file={args.defaults_file}", "--batch",
               "--raw", "--skip-column-names", "--init-command=SET SESSION time_zone='+00:00'",
               f"--database={args.database}"]
    result = subprocess.run(command, input=query, text=True, capture_output=True, timeout=args.timeout)
    if result.returncode:
        raise MysqlError(result.stderr)
    return result.stdout.splitlines()


def read_batch(args, dataset, tenant, after):
    """按租户和原生主键游标分页，重跑时已编号记录自然退出。"""
    lower = f" AND {dataset.primary_key}>{sql_identifier(after, dataset.numeric_tenant)}" if after else ""
    timestamp = dataset.timestamp_column
    if dataset.iso_timestamp:
        # 营销表保存 ISO-8601 UTC 字符串，解析秒及之前的信息足以计算业务日。
        timestamp = f"CAST(REPLACE(LEFT({timestamp},19),'T',' ') AS DATETIME)"
    query = (f"SELECT {dataset.primary_key}, DATE_FORMAT({timestamp} + INTERVAL 8 HOUR, '%y%m%d') "
             f"FROM {dataset.table} WHERE tenant_id={tenant}{lower} AND business_no IS NULL "
             f"ORDER BY {dataset.primary_key} LIMIT {args.batch_size};")
    rows = []
    for line in mysql(args, query):
        identifier, date = line.split("\t")
        sql_identifier(identifier, dataset.numeric_tenant)
        if not BUSINESS_DAY.fullmatch(date):
            raise ValueError("one stored order has an invalid business day")
        rows.append((identifier, date))
    return rows


def fill_batch(args, dataset, tenant, rows):
    """只有一次原子 UPDATE，唯一号碰撞回滚这一批后重新选号。"""
    key = dataset.primary_key
    identifiers = [sql_identifier(identifier, dataset.numeric_tenant) for identifier, _ in rows]
    for attempt in range(COLLISION_RETRIES):
        mappings = [
            f"WHEN {identifier} THEN '{dataset.prefix}-{date}-{secrets.randbelow(SUFFIX_SPACE):010d}'"
            for identifier, (_, date) in zip(identifiers, rows)
        ]
        query = (f"UPDATE {dataset.table} SET business_no=CASE {key} {' '.join(mappings)} END "
                 f"WHERE tenant_id={tenant} AND {key} IN ({','.join(identifiers)}) AND business_no IS NULL; "
                 "SELECT ROW_COUNT();")
        try:
            return int(mysql(args, query)[0])
        except MysqlError as error:
            if error.number != 1062 or attempt == COLLISION_RETRIES - 1:
                raise
    raise AssertionError("collision retry loop did not finish")


def main():
    """默认只查看待补记录，应用时可从上一个成功批次的游标恢复。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", choices=DATASETS, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--defaults-file", type=Path, required=True)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--max-batches", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--after", default="")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", args.database):
        parser.error("invalid database name")
    if not args.defaults_file.is_file():
        parser.error("MySQL defaults file does not exist")
    if not 1 <= args.batch_size <= 1000 or not 1 <= args.max_batches <= 1000 or not 1 <= args.timeout <= 60:
        parser.error("batch size, batch limit or timeout is outside the supported range")
    dataset = DATASETS[args.dataset]
    try:
        tenant = sql_identifier(args.tenant, dataset.numeric_tenant)
        after = args.after
        for _ in range(args.max_batches if args.apply else 1):
            rows = read_batch(args, dataset, tenant, after)
            if not rows:
                print(f"complete after={after or '(start)'}", flush=True)
                break
            if not args.apply:
                print(f"dry-run candidates={len(rows)} after={after or '(start)'}", flush=True)
                break
            updated = fill_batch(args, dataset, tenant, rows)
            after = rows[-1][0]
            print(f"updated={updated} scanned={len(rows)} after={after}", flush=True)
        else:
            print(f"batch limit reached; resume with --after {after}", flush=True)
    except (ValueError, MysqlError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"{error}; resume from the last successful cursor\n")


if __name__ == "__main__":
    main()
