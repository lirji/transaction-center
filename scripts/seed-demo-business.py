#!/usr/bin/env python3
"""为当前本地演示库添加持久业务数据；仅用测试classpath领域工具，不清库、不打印秘密。"""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / 'deploy/.env.demo'
MANIFEST = ROOT / 'deploy/demo-business-manifest.json'


def main():
    """固定开发目标并保留密钥，重复运行保留用户已经操作过的业务状态。"""
    values = dict(line.split('=', 1) for line in ENV_FILE.read_text().splitlines() if line and not line.startswith('#'))
    if not values.get('TRADE_DB_URL', '').startswith('jdbc:mysql://infra-mysql84:3306/transaction_center_dev?'):
        raise ValueError('只支持当前dev_infra内的transaction_center_dev演示库')
    container = json.loads(subprocess.check_output(['docker', 'inspect', 'transaction-center-trade-api-1']))[0]
    running = dict(item.split('=', 1) for item in container['Config']['Env'])
    if running.get('TRADE_DB_URL') != values['TRADE_DB_URL'] or running.get('TRADE_DB_USER') != values['TRADE_DB_USER']:
        raise ValueError('运行实例与演示配置不一致，拒绝写入')
    if running.get('TRADE_WORKER_ENABLED') != 'false' or running.get('TRADE_OUTBOX_ENABLED') != 'false':
        raise ValueError('演示资金夹具要求后台派发关闭')
    # 地址密钥持久保存，不因重复运行轮换；已有运行密钥必须与文件一致。
    if running.get('TRADE_ADDRESS_KEY_BASE64') and running['TRADE_ADDRESS_KEY_BASE64'] != values.get('TRADE_ADDRESS_KEY_BASE64'):
        raise ValueError('运行地址密钥与配置不一致')
    if not values.get('TRADE_ADDRESS_KEY_BASE64'):
        values['TRADE_ADDRESS_KEY_BASE64'] = base64.b64encode(secrets.token_bytes(32)).decode()
        values['TRADE_ADDRESS_KEY_VERSION'] = 'demo-business-v1'
        original = ENV_FILE.read_text()
        original = '\n'.join(line for line in original.splitlines() if not line.startswith(('TRADE_ADDRESS_KEY_BASE64=', 'TRADE_ADDRESS_KEY_VERSION=')))
        ENV_FILE.write_text(original + '\nTRADE_ADDRESS_KEY_BASE64=' + values['TRADE_ADDRESS_KEY_BASE64'] + '\nTRADE_ADDRESS_KEY_VERSION=demo-business-v1\n')
        ENV_FILE.chmod(0o600)
    env = {key: value for key, value in os.environ.items() if not key.startswith(('TRADE_', 'SPRING_'))}
    for key in ['TRADE_DB_USER', 'TRADE_DB_PASSWORD', 'TRADE_ADDRESS_KEY_BASE64', 'TRADE_ADDRESS_KEY_VERSION', 'TRADE_JWT_ISSUER', 'TRADE_JWT_AUDIENCE']:
        if key in values: env[key] = values[key]
    env.update(TRADE_DB_URL=values['TRADE_DB_URL'].replace('infra-mysql84:3306', '127.0.0.1:43306'),
        TRADE_JWT_JWKS='http://localhost:8000/.well-known/jwks', TRADE_DEMO_SEED_DATABASE='transaction_center_dev',
        TRADE_DEMO_SEED_MANIFEST=str(MANIFEST))
    logs = ROOT / 'transaction-server/target/demo-business'
    logs.mkdir(parents=True, exist_ok=True)
    with (logs / 'seed.log').open('w') as output:
        result = subprocess.run(['mvn', '--batch-mode', '-Dtest=DemoBusinessSeedIT', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'],
            cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError('种子执行失败，检查transaction-server/target/demo-business/seed.log；未清理已有业务数据')
    print('演示业务种子已就绪：deploy/demo-business-manifest.json；重复执行保留已操作的数据。')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
