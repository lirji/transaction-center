"""验证合同检查能拒绝真实漂移，而不只是成功生成文件。"""
import unittest
import asyncapi_contract as contract


class AsyncApiContractTest(unittest.TestCase):
    def test_published_types_and_existing_examples_validate(self):
        result = contract.validate(contract.generate())
        self.assertEqual(7, result['event_types'])
        self.assertEqual(12, result['positive_examples'])

    def test_official_schema_rejects_invalid_operation(self):
        doc = contract.generate()
        next(iter(doc['operations'].values()))['action'] = 'publish'
        with self.assertRaises(ValueError):
            contract.validate(doc)

    def test_broken_reference_is_not_accepted_as_a_string(self):
        doc = contract.generate()
        next(iter(doc['operations'].values()))['channel']['$ref'] = '#/channels/missing'
        with self.assertRaises(KeyError):
            contract.validate(doc)

    def test_example_type_tampering_is_rejected(self):
        from jsonschema.exceptions import ValidationError
        doc = contract.generate()
        message = next(iter(doc['components']['messages'].values()))
        message['examples'][0]['payload']['tenantId'] = 123
        with self.assertRaises(ValidationError):
            contract.validate(doc)

    def test_new_schema_vocabulary_requires_explicit_conversion_review(self):
        with self.assertRaises(ValueError):
            contract.compatible_schema({'type': 'array', 'prefixItems': []})

    def test_trace_header_must_match_persisted_event(self):
        doc = contract.generate()
        message = next(iter(doc['components']['messages'].values()))
        message['examples'][0]['headers']['traceparent'] = '00-' + '1' * 32 + '-' + '2' * 16 + '-00'
        with self.assertRaises(ValueError):
            contract.validate(doc)

    def test_trace_header_rejects_zero_span_and_missing_header(self):
        from jsonschema.exceptions import ValidationError
        for malformed in [None, '00-' + '1' * 32 + '-' + '0' * 16 + '-00']:
            doc = contract.generate()
            headers = next(iter(doc['components']['messages'].values()))['examples'][0]['headers']
            if malformed is None:
                del headers['traceparent']
            else:
                headers['traceparent'] = malformed
            with self.assertRaises(ValidationError):
                contract.validate(doc)


if __name__ == '__main__':
    unittest.main()
