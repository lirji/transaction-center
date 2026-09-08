"""检查错误码合同能捕获遗漏及重试白名单漂移。"""
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch
import error_contract


class ErrorContractTest(unittest.TestCase):
    def test_current_sources_and_sdk_agree(self):
        self.assertGreater(error_contract.validate()['emitted_codes'], 0)

    def test_missing_sdk_code_and_retry_drift_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ['docs/contracts/error-codes.json',
                             'transaction-client-sdk/src/main/java/com/lrj/trade/client/ErrorCodes.java']:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((error_contract.ROOT / relative).read_text())
            source = root / 'transaction-test/src/main/java/Example.java'
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('throw new TradeException("NEW_CODE", "新错误");')
            with patch.object(error_contract, 'ROOT', root):
                with self.assertRaisesRegex(ValueError, '遗漏'):
                    error_contract.validate()
                source.write_text('')
                catalog = root / 'docs/contracts/error-codes.json'
                data = json.loads(catalog.read_text())
                data['retryableCodes'].append('INTERNAL_ERROR')
                catalog.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, '白名单'):
                    error_contract.validate()
