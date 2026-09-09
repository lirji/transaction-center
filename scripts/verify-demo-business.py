#!/usr/bin/env python3
"""用现有Casdoor管理员及控制台同源代理核对持久演示数据，不打印登录凭据或JWT。"""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    """只读取业务记录，保留用户已经修改的状态，不调用审批或渠道写入口。"""
    spec = importlib.util.spec_from_file_location('casdoor_verifier', ROOT / 'scripts/verify-casdoor.py')
    iam = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(iam)
    credentials = json.loads((ROOT / 'deploy/.env.casdoor.json').read_text())
    token = iam.issue(credentials)
    identity = iam.claims(token)
    assert identity['tenant_id'] == '900001' and 'trade.admin' in identity['scope']
    data = json.loads((ROOT / 'deploy/demo-business-manifest.json').read_text())
    prefix = 'http://127.0.0.1:4180/api/v1/trade/admin/'
    for key, resource in [('paidOrder', 'orders'), ('pendingOrder', 'orders'), ('cancelledOrder', 'orders'),
                          ('paymentNo', 'payments'), ('pendingPayment', 'payments'), ('receiptNo', 'payment-receipts'),
                          ('abnormalReceiptNo', 'payment-receipts'), ('quoteId', 'quotes'), ('refundNo', 'refunds'),
                          ('successfulRefund', 'refunds'), ('submittedRefund', 'refunds'),
                          ('openReturn', 'returns'), ('inspectedReturn', 'returns')]:
        status, body = iam.call(prefix + resource + '/' + data[key], token=token)
        assert status == 200 and body, key + ': 管理查询失败'
        print('PASS', key, body.get('status', 'HTTP 200'))
    status, refunds = iam.call(prefix + 'refunds?status=ALL&orderNo=' + data['paidOrder'], token=token)
    assert status == 200 and len(refunds) == 4
    print('PASS 4笔退款；现有Casdoor PKCE + 4180代理 + 真实数据库')


if __name__ == '__main__':
    main()
