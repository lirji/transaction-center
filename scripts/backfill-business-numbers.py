#!/usr/bin/env python3
"""为指定租户有界补录业务展示号。原生订单标识、外键和资金记录由业务系统持有。"""

import argparse
import re
import secrets
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass(frozen=True)
class Dataset:
    table: str
    primary_key: str
    prefix: str
    numeric_tenant: bool
    timestamp_column: str = "created_at"
    iso_timestamp: bool = False
    partition_column: Optional[str] = None
    numeric_primary_key: Optional[bool] = None
    number_column: str = "business_no"
    condition: Optional[str] = None
    skip_unknown_date: bool = False
    max_key_length: int = 64

    @property
    def key_is_numeric(self):
        return self.numeric_tenant if self.numeric_primary_key is None else self.numeric_primary_key


DATASETS = {
    "trade-quotes": Dataset("tc_quote", "id", "TRD-QTE", True),
    "commerce-quotes": Dataset("trade_quote", "quote_id", "COM-QTE", False),
    "trade-orders": Dataset("tc_order", "id", "TRD-ORD", True),
    "trade-payment-receipts": Dataset("tc_payment_receipt", "id", "TRD-RCT", True),
    "trade-money-ledger": Dataset("tc_money_ledger", "id", "TRD-LED", True),
    "trade-payments": Dataset("tc_payment_intent", "id", "TRD-PAY", True),
    "trade-refunds": Dataset("tc_refund", "id", "TRD-RFD", True),
    "trade-fulfillments": Dataset("tc_order_fulfillment", "id", "TRD-FUL", True),
    "trade-returns": Dataset("tc_return_request", "id", "TRD-RTN", True),
    "trade-return-receipts": Dataset("tc_return_receipt", "id", "TRD-RMA", True),
    "trade-reconciliation-cases": Dataset("tc_reconciliation_case", "id", "TRD-REC", True),
    "trade-reconciliation-operations": Dataset("tc_reconciliation_operation", "id", "TRD-ROP", True),
    "marketing-release-bundles": Dataset("mk_release_bundle", "bundle_id", "MKT-BND", False, iso_timestamp=True),
    "marketing-release-operations": Dataset("mk_bundle_release_operation", "operation_id", "MKT-RLS", False, iso_timestamp=True),
    "marketing-release-schedules": Dataset("mk_bundle_activation_schedule", "schedule_id", "MKT-SCH", False,
                                           iso_timestamp=True, partition_column="campaign_id"),
    "commerce-exports": Dataset("central_scope_export", "id", "COM-EXP", False),
    "commerce-orders": Dataset("order_record", "order_id", "COM-ORD", False),
    "commerce-payments": Dataset("payment_attempt", "payment_id", "COM-PAY", False),
    "commerce-refunds": Dataset("payment_refund", "refund_id", "COM-RFD", False),
    "commerce-aftersales": Dataset("aftersales_case", "case_id", "COM-AFS", False),
    "marketing-campaigns": Dataset("mk_campaign", "campaign_id", "MKT-CMP", False, iso_timestamp=True),
    "marketing-definition-versions": Dataset("mk_definition_version", "version_no", "MKT-DEF", False,
                                             iso_timestamp=True, partition_column="definition_id", numeric_primary_key=True),
    "commerce-benefit-ledger": Dataset("benefit_ledger", "entry_id", "COM-LED", False),
    "marketing-approvals": Dataset("mk_approval_case", "case_id", "MKT-APR", False, iso_timestamp=True),
    "marketing-audience-snapshots": Dataset("mk_audience_snapshot", "snapshot_id", "MKT-SNP", False, iso_timestamp=True),
    "marketing-journey-enrollments": Dataset("mk_enrollment", "enrollment_id", "MKT-JIN", False, iso_timestamp=True),
    "marketing-referral-participants": Dataset("mk_referral_participant", "participant_id", "MKT-RFP", False),
    "marketing-referral-relations": Dataset("mk_referral_relation", "relation_id", "MKT-RLN", False),
    "marketing-referral-rewards": Dataset("mk_referral_reward", "reward_id", "MKT-RWD", False),
    "marketing-funding": Dataset("mk_promotion_application", "application_id", "MKT-FAP", False, iso_timestamp=True),
    "marketing-ledger": Dataset("mk_funding_ledger", "ledger_id", "MKT-LED", False, "occurred_at", True),
    "trade-payment-callbacks": Dataset("tc_payment_callback", "id", "TRD-PCB", True),
    "trade-refund-callbacks": Dataset("tc_refund_callback", "id", "TRD-RCB", True),
    "trade-refund-approvals": Dataset("tc_refund", "id", "TRD-APR", True,
                                     number_column="approval_business_no", condition="approval_ref IS NOT NULL"),
    "commerce-budgets": Dataset("marketing_budget", "budget_id", "COM-BGT", False,
                                """COALESCE(business_created_at,(SELECT c.created_at FROM marketing_campaign c
                                   WHERE c.tenant_id=marketing_budget.tenant_id
                                     AND c.campaign_id=marketing_budget.campaign_id AND c.version=marketing_budget.version))""",
                                skip_unknown_date=True),
    "commerce-segment-runs": Dataset("marketing_segment_run", "run_id", "COM-SRN", False, "started_at"),
    "commerce-journey-instances": Dataset("journey_instance", "instance_id", "COM-JIN", False,
                                """COALESCE(business_created_at,(SELECT e.created_at FROM journey_effect e
                                   WHERE e.tenant_id=journey_instance.tenant_id AND e.kind='ENROLLED'
                                     AND e.effect_id=SHA2(CONCAT('entry/',journey_instance.journey_id,'/',
                                         journey_instance.journey_version,'/',journey_instance.event_key),256)))""",
                                skip_unknown_date=True),
    "marketing-segments": Dataset("mk_segment_definition", "segment_id", "MKT-SEG", False,
                                  iso_timestamp=True, condition="version_no=1", max_key_length=128),
    "marketing-event-receipts": Dataset("mk_event_receipt", "receipt_id", "MKT-EVT", False, "ingested_at", True),
    "marketing-quarantine": Dataset("mk_quarantine", "quarantine_id", "MKT-QTN", False, iso_timestamp=True),
    "marketing-contacts": Dataset("mk_contact_attempt", "contact_id", "MKT-CNT", False,
                                  """COALESCE(created_at,(SELECT MIN(o.created_at) FROM mk_engagement_outbox o
                                     WHERE o.tenant_id=mk_contact_attempt.tenant_id AND o.contact_id=mk_contact_attempt.contact_id
                                       AND o.event_type='ContactRequested'))""", True, skip_unknown_date=True),
    "marketing-release-manifests": Dataset("mk_release_manifest", "manifest_id", "MKT-MNF", False, iso_timestamp=True),
    "marketing-quotes": Dataset("mk_decision_command", "idempotency_key", "MKT-QTE", False,
                               "JSON_UNQUOTE(JSON_EXTRACT(trace_json,'$.createdAt'))", True,
                               condition="quote_ref IS NOT NULL AND expires_at>DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ')",
                               skip_unknown_date=True, max_key_length=128),
}
IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]+")
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


def sql_identifier(value, numeric=False, max_length=64):
    """编号来自已验证的主键和租户，文本输入禁止引号与转义字符。"""
    if numeric:
        if not POSITIVE_ID.fullmatch(value) or int(value) > 2**64 - 1:
            raise ValueError("numeric ID is outside the unsigned database range")
        return value
    if len(value) > max_length or not IDENTIFIER.fullmatch(value):
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


def read_batch(args, dataset, scope, after):
    """按租户和原生主键游标分页，重跑时已编号记录自然退出。"""
    lower = f" AND {dataset.primary_key}>{sql_identifier(after, dataset.key_is_numeric, dataset.max_key_length)}" if after else ""
    timestamp = dataset.timestamp_column
    if dataset.iso_timestamp:
        # 营销表保存 ISO-8601 UTC 字符串，解析秒及之前的信息足以计算业务日。
        timestamp = f"CAST(REPLACE(LEFT({timestamp},19),'T',' ') AS DATETIME)"
    known_date = f" AND {timestamp} IS NOT NULL" if dataset.skip_unknown_date else ""
    query = (f"SELECT {dataset.primary_key}, DATE_FORMAT({timestamp} + INTERVAL 8 HOUR, '%y%m%d') "
             f"FROM {dataset.table} WHERE {scope}{lower} AND {dataset.number_column} IS NULL{known_date} "
             f"ORDER BY {dataset.primary_key} LIMIT {args.batch_size};")
    rows = []
    for line in mysql(args, query):
        identifier, date = line.split("\t")
        sql_identifier(identifier, dataset.key_is_numeric, dataset.max_key_length)
        if not BUSINESS_DAY.fullmatch(date):
            raise ValueError("one stored record has an invalid business day")
        rows.append((identifier, date))
    return rows


def fill_batch(args, dataset, scope, rows):
    """只有一次原子 UPDATE，唯一号碰撞回滚这一批后重新选号。"""
    key = dataset.primary_key
    identifiers = [sql_identifier(identifier, dataset.key_is_numeric, dataset.max_key_length) for identifier, _ in rows]
    for attempt in range(COLLISION_RETRIES):
        mappings = [
            f"WHEN {identifier} THEN '{dataset.prefix}-{date}-{secrets.randbelow(SUFFIX_SPACE):010d}'"
            for identifier, (_, date) in zip(identifiers, rows)
        ]
        query = (f"UPDATE {dataset.table} SET {dataset.number_column}=CASE {key} {' '.join(mappings)} END "
                 f"WHERE {scope} AND {key} IN ({','.join(identifiers)}) AND {dataset.number_column} IS NULL; "
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
    parser.add_argument("--partition", help="raw owner ID for a partitioned dataset")
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
        scope = f"tenant_id={sql_identifier(args.tenant, dataset.numeric_tenant)}"
        if dataset.partition_column:
            if not args.partition:
                parser.error("--partition is required for the selected dataset")
            scope += f" AND {dataset.partition_column}={sql_identifier(args.partition)}"
        elif args.partition:
            parser.error("--partition is only supported by partitioned datasets")
        if dataset.condition:
            scope += f" AND ({dataset.condition})"
        after = args.after
        for _ in range(args.max_batches if args.apply else 1):
            rows = read_batch(args, dataset, scope, after)
            if not rows:
                print(f"complete after={after or '(start)'}", flush=True)
                break
            if not args.apply:
                print(f"dry-run candidates={len(rows)} after={after or '(start)'}", flush=True)
                break
            updated = fill_batch(args, dataset, scope, rows)
            after = rows[-1][0]
            print(f"updated={updated} scanned={len(rows)} after={after}", flush=True)
        else:
            print(f"batch limit reached; resume with --after {after}", flush=True)
    except (ValueError, MysqlError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"{error}; resume from the last successful cursor\n")


if __name__ == "__main__":
    main()
