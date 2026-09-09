#!/usr/bin/env python3
"""仅用合成凭据验证Compose变量透传，禁止读取/输出本机秘密配置。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = {
    'TRADE_ADMIN_EVIDENCE_ENABLED': 'false', 'TRADE_ADMIN_QUERY_CONCURRENCY': '4',
    'TRADE_ADMIN_QUERY_TENANT_CONCURRENCY': '2', 'TRADE_ADMIN_QUERY_TENANT_RATE': '20',
    'TRADE_ORDER_ATTRIBUTION_ENABLED': 'false', 'TRADE_ORDER_ATTRIBUTION_ISSUER': '',
    'TRADE_ORDER_ATTRIBUTION_JWKS': '', 'TRADE_AUTOMATIC_ADMISSION_ENABLED': 'false',
    'TRADE_AUTOMATIC_ADMISSION_DISK_PATH': '', 'TRADE_AUTOMATIC_ADMISSION_DISK_STORE': '',
    'TRADE_AUTOMATIC_ADMISSION_BACKLOG_HIGH': '10000', 'TRADE_AUTOMATIC_ADMISSION_BACKLOG_LOW': '5000',
    'TRADE_AUTOMATIC_ADMISSION_DISK_MIN_BYTES': '5368709120',
    'TRADE_AUTOMATIC_ADMISSION_DISK_RECOVERY_BYTES': '10737418240',
    'TRADE_AUTOMATIC_ADMISSION_INTERVAL_MILLIS': '15000', 'TRADE_AUTOMATIC_ADMISSION_MAX_AGE_MILLIS': '45000',
}

def resolve(overrides):
    """env-file为空且清除宿主TRADE变量，使结果可复现且不携带真实账号。"""
    env = {k: v for k, v in os.environ.items() if not k.startswith(('TRADE_', 'COMPOSE_', 'DEV_INFRA_'))}
    env.update({k: 'configuration-test' for k in ['TRADE_DB_USER', 'TRADE_DB_PASSWORD',
        'TRADE_MIGRATION_USER', 'TRADE_MIGRATION_PASSWORD', 'TRADE_JWT_ISSUER', 'TRADE_JWT_JWKS']})
    env.update(overrides)
    with tempfile.NamedTemporaryFile() as empty:
        return json.loads(subprocess.check_output(['docker', 'compose', '--env-file', empty.name,
            '-f', str(ROOT / 'deploy/compose.yaml'), '--profile', 'worker', 'config', '--format', 'json'], env=env))


def main():
    """检查默认与覆盖，并证明Worker独立关闭入口，解析不会替代应用参数校验。"""
    default = resolve({})['services']
    for key, value in DEFAULTS.items():
        assert default['trade-api']['environment'][key] == value, key
    overrides = {k: ('true' if k.endswith('ENABLED') else '17' if v.isdigit() else 'test-value')
                 for k, v in DEFAULTS.items()}
    custom = resolve(overrides)['services']
    for key, value in overrides.items():
        assert custom['trade-api']['environment'][key] == value, key
    for key in ['TRADE_ADMIN_EVIDENCE_ENABLED', 'TRADE_ORDER_ATTRIBUTION_ENABLED',
                'TRADE_REFUND_APPROVAL_ENABLED', 'TRADE_FULFILLMENT_ENABLED', 'TRADE_EVIDENCE_ENABLED']:
        assert custom['trade-worker']['environment'][key] == 'false', key
    for key in ['TRADE_WORKER_ENABLED', 'TRADE_OUTBOX_ENABLED', 'TRADE_OUTBOX_MAINTENANCE_ENABLED']:
        assert custom['trade-api']['environment'][key] == 'false', key
    assert custom['trade-worker']['environment']['TRADE_BIND'] == '127.0.0.1'
    assert not custom['trade-worker'].get('ports')
    assert resolve({'TRADE_ADMIN_QUERY_CONCURRENCY': '0'})['services']['trade-api']['environment']['TRADE_ADMIN_QUERY_CONCURRENCY'] == '0'
    print(f'PASS {len(DEFAULTS)} configuration defaults/overrides; worker/API role isolation')

if __name__ == '__main__':
    main()
