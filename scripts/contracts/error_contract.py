#!/usr/bin/env python3
"""离线核对业务拒绝码、SDK常量和机器清单；新增码必须显式归类重试策略。"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]


def validate():
    catalog = json.loads((ROOT / 'docs/contracts/error-codes.json').read_text())
    codes = set(catalog['codes'])
    sdk = (ROOT / 'transaction-client-sdk/src/main/java/com/lrj/trade/client/ErrorCodes.java').read_text()
    constants = dict(re.findall(r'public static final String ([A-Z_0-9]+) = "([A-Z_0-9]+)";', sdk))
    if set(constants) != codes or any(k != v for k, v in constants.items()):
        raise ValueError('SDK常量与机器错误码清单不一致')
    emitted = set()
    for source in ROOT.glob('transaction-*/src/main/java/**/*.java'):
        text = source.read_text()
        emitted.update(re.findall(r'new TradeException\(\s*"([A-Z][A-Z_0-9]+)"', text))
        if source.name == 'Errors.java':
            emitted.update(re.findall(r'(?:response\(\d+,|new Failure\()\s*"([A-Z][A-Z_0-9]+)"', text))
    if emitted - codes:
        raise ValueError('SDK遗漏生产错误码: ' + ','.join(sorted(emitted - codes)))
    match = re.search(r'RETRYABLE = java.util.Set.of\(([^)]*)\)', sdk)
    if set(match.group(1).split(',')) != set(catalog['retryableCodes']):
        raise ValueError('SDK重试白名单与机器清单不一致')
    if not set(catalog['retryableCodes']) <= codes:
        raise ValueError('重试白名单含未定义错误码')
    return {'codes': len(codes), 'emitted_codes': len(emitted), 'unknown_code_policy': 'preserve-and-stop'}


if __name__ == '__main__':
    print(json.dumps(validate(), ensure_ascii=False))
