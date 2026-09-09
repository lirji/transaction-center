#!/usr/bin/env python3
"""复用dev_infra MySQL，创建隔离演示库与最小权限账号；不修改正式库及其凭据。"""
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / 'deploy/.env.demo'


def main():
    """保存后重复使用随机凭据，CREATE USER IF NOT EXISTS不重置已有密码。"""
    if not ENV.exists():
        values = {
            'TRADE_DB_URL': 'jdbc:mysql://infra-mysql84:3306/transaction_center_dev?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
            'TRADE_DB_USER': 'trade_demo_app', 'TRADE_DB_PASSWORD': secrets.token_hex(24),
            'TRADE_MIGRATION_USER': 'trade_demo_migrator', 'TRADE_MIGRATION_PASSWORD': secrets.token_hex(24),
            'TRADE_JWT_ISSUER': 'http://localhost:8000',
            'TRADE_JWT_JWKS': 'http://host.docker.internal:8000/.well-known/jwks',
            'TRADE_JWT_AUDIENCE': 'transaction-center', 'TRADE_SPRING_PROFILES': 'dev-seed',
            'TRADE_HOST_PORT': '8400', 'DEV_INFRA_NETWORK': 'dev-infra',
        }
        fd = os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            stream.write(''.join(f'{key}={value}\n' for key,value in values.items()))
    values = dict(line.split('=',1) for line in ENV.read_text().splitlines() if line and not line.startswith('#'))
    # 固定标识符且密码只允许本脚本的十六进制格式，避免SQL注入及意外扩大授权。
    import re
    for key in ['TRADE_DB_PASSWORD','TRADE_MIGRATION_PASSWORD']:
        if not re.fullmatch('[0-9a-f]{48}', values[key]):
            raise ValueError('演示密码配置格式不匹配，拒绝修改数据库')
    sql = "CREATE DATABASE IF NOT EXISTS transaction_center_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
    for user,key,grants in [('trade_demo_app','TRADE_DB_PASSWORD','SELECT,INSERT,UPDATE'),
                            ('trade_demo_migrator','TRADE_MIGRATION_PASSWORD','SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES')]:
        sql += f"CREATE USER IF NOT EXISTS '{user}'@'%' IDENTIFIED BY '{values[key]}';\n"
        sql += f"GRANT {grants} ON transaction_center_dev.* TO '{user}'@'%';\n"
    subprocess.run(['docker','exec','-i','dev-infra-mysql84-1','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],input=sql,text=True,check=True,
        stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    print('隔离演示库及专用账号已就绪，配置位于deploy/.env.demo（0600）。')


if __name__ == '__main__':
    main()
