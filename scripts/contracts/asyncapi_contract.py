#!/usr/bin/env python3
"""从既有事件Schema生成发布方AsyncAPI，并离线验证结构、路由、引用与正例。"""
from pathlib import Path
import argparse, copy, hashlib, json, re

ROOT = Path(__file__).resolve().parents[2]
DRAFT7 = 'http://json-schema.org/draft-07/schema#'
FORMATS = 'application/schema+json;version=draft-07'
EVENTS = {
    'trade.member-registered.v1': 'member-registered-v1',
    'trade.order-paid.v1': 'order-paid-v1',
    'trade.order-completed.v1': 'order-completed-v1',
    'trade.order-cancelled.v1': 'order-cancelled-v1',
    'trade.order-evidence-changed.v1': 'refund-evidence-v1',
    'trade.refund-succeeded.v1': 'refund-evidence-v1',
    'trade.inventory-changed.v1': 'inventory-changed-v1',
}


def read(path):
    return json.loads(path.read_text())


def compatible_schema(schema):
    """当前Schema仅用两个方言共同的关键词；新增不兼容关键词必须显式评审。"""
    allowed = {'$schema', 'title', 'description', 'type', 'required', 'additionalProperties',
               'properties', 'items', 'minimum', 'maximum', 'minLength', 'maxLength',
               'minItems', 'maxItems', 'pattern', 'format', 'const', 'enum', 'allOf',
               'anyOf', 'oneOf', 'if', 'then', 'else', 'not', 'examples', 'default'}
    def inspect(node):
        if isinstance(node, bool):
            return
        if set(node) - allowed:
            raise ValueError('需要审查的Schema关键词: ' + ','.join(set(node) - allowed))
        for value in node.get('properties', {}).values():
            inspect(value)
        for key in ['items', 'additionalProperties', 'if', 'then', 'else', 'not']:
            if isinstance(node.get(key), (dict, bool)):
                inspect(node[key])
        for key in ['allOf', 'anyOf', 'oneOf']:
            for value in node.get(key, []):
                inspect(value)
    inspect(schema)
    converted = copy.deepcopy(schema)
    converted['$schema'] = DRAFT7
    return converted


def generate():
    doc = {
        'asyncapi': '3.0.0', 'id': 'urn:lrj:transaction-center:outbox',
        'info': {'title': '交易中心V1事件发布合同', 'version': '1.0.0',
                 'description': '事务Outbox至少一次发布。消费者须在业务事务内用eventId去重；本文件不代表外部消费者已验收。'},
        'defaultContentType': 'application/json',
        'servers': {'development': {'host': 'localhost:49092', 'protocol': 'kafka',
                                   'description': 'dev_infra本机映射，仅开发环境；生产认证与复制策略另行配置。'}},
        'channels': {}, 'operations': {}, 'components': {'messages': {}},
        'x-delivery-guarantee': 'at-least-once',
        'x-production-validation': 'pending',
    }
    for event, directory in EVENTS.items():
        name = event.removeprefix('trade.').replace('-', '_').replace('.', '_')
        root = ROOT / 'docs/contracts' / directory
        schema = compatible_schema(read(root / 'schema.json'))
        schema['properties']['eventType'] = {'const': event}
        examples = []
        for kind in ['minimal', 'complete']:
            payload = read(root / (kind + '.json'))
            if payload['eventType'] == event:
                examples.append({'name': kind, 'headers': {'eventId': payload['eventId'],
                    'traceparent': '00-' + payload['traceId'].replace('-', '') + '-0000000000000001-00'}, 'payload': payload})
        if not examples:
            raise ValueError('缺少现有合法事件正例: ' + event)
        aggregate_type = schema['properties']['aggregateType']['const']
        doc['components']['messages'][name] = {
            'name': event, 'title': schema['title'], 'contentType': 'application/json',
            'headers': {'type': 'object', 'required': ['eventId', 'traceparent'],
                        'properties': {'eventId': {'type': 'string', 'format': 'uuid'},
                            'traceparent': {'type': 'string', 'minLength': 55, 'maxLength': 55,
                                'pattern': '^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-0[01]$'}}},
            'payload': {'schemaFormat': FORMATS, 'schema': schema},
            'correlationId': {'location': '$message.payload#/traceId',
                              'description': '持久事件UUID与traceparent共享128位trace ID；历史全零UUID出站新根。HTTP与Job传播已实现，外部消费者与生产采集验收待完成。'},
            'bindings': {'kafka': {'bindingVersion': '0.5.0', 'key': {
                'type': 'string', 'pattern': '^[1-9][0-9]*:' + aggregate_type + ':.+$', 'maxLength': 128}}},
            'x-kafka-key-expression': 'tenantId + ":" + aggregateType + ":" + aggregateId',
            'x-source-schema': 'contracts/' + directory + '/schema.json',
            'x-semantic-validation': '对应Java EventCodec验证跨字段版本、归属和金额关系；JSON Schema只验证结构。',
            'examples': examples,
        }
        doc['channels'][name] = {
            'address': '{environmentPrefix}' + event,
            'parameters': {'environmentPrefix': {'description': '部署配置的环境主题前缀，和TRADE_OUTBOX_TOPIC_PREFIX完全一致。'}},
            'servers': [{'$ref': '#/servers/development'}],
            'messages': {'event': {'$ref': '#/components/messages/' + name}},
        }
        doc['operations']['send_' + name] = {
            'action': 'send', 'channel': {'$ref': '#/channels/' + name},
            'messages': [{'$ref': '#/channels/' + name + '/messages/event'}],
        }
    return doc


def validate(doc):
    """官方Schema本地注册，禁止验证时访问远程引用；业务负例仍由Java合同门禁裁决。"""
    from jsonschema import Draft7Validator, Draft202012Validator, FormatChecker
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT7 as SPEC7
    vendor = ROOT / 'scripts/contracts/vendor'
    for entry in read(vendor / 'sources.json'):
        actual = hashlib.sha256((vendor / entry['file']).read_bytes()).hexdigest()
        if actual != entry['sha256']:
            raise ValueError('官方Schema或许可文件校验和改变')
    official = read(vendor / 'asyncapi-3.0.0.schema.json')
    resources = {official['$id']: official, **official['definitions']}
    registry = Registry().with_resources((uri, Resource.from_contents(value, default_specification=SPEC7)) for uri, value in resources.items())
    errors = list(Draft7Validator(official, registry=registry, format_checker=FormatChecker()).iter_errors(doc))
    if errors:
        raise ValueError('AsyncAPI结构无效: ' + '; '.join(e.message for e in errors[:3]))
    source = (ROOT / 'transaction-core/src/main/java/com/lrj/trade/core/OutboxEventCatalog.java').read_text()
    supported = set(re.findall(r'"(trade\.[a-z-]+\.v1)"', source))
    if supported != set(EVENTS):
        raise ValueError('发布白名单与AsyncAPI不一致')
    def references(value):
        if isinstance(value, dict):
            if '$ref' in value:
                target = doc
                if not value['$ref'].startswith('#/'):
                    raise ValueError('生成文档仅允许本地引用')
                for part in value['$ref'][2:].split('/'):
                    target = target[part.replace('~1', '/').replace('~0', '~')]
            for child in value.values():
                references(child)
        elif isinstance(value, list):
            for child in value:
                references(child)
    references(doc)
    examples = 0
    for message in doc['components']['messages'].values():
        converted = message['payload']['schema']
        original = read(ROOT / 'docs' / message['x-source-schema'])
        for example in message['examples']:
            payload = example['payload']
            Draft202012Validator(original, format_checker=FormatChecker()).validate(payload)
            Draft7Validator(converted, format_checker=FormatChecker()).validate(payload)
            Draft7Validator(message['headers'], format_checker=FormatChecker()).validate(example['headers'])
            if example['headers']['eventId'] != payload['eventId']:
                raise ValueError('消息头eventId与正文不一致')
            trace_id = payload['traceId'].replace('-', '')
            if trace_id != '0' * 32 and example['headers']['traceparent'][3:35] != trace_id:
                raise ValueError('消息追踪头与持久事件traceId不一致')
            key = ':'.join(payload[x] for x in ['tenantId', 'aggregateType', 'aggregateId'])
            Draft7Validator(message['bindings']['kafka']['key']).validate(key)
            examples += 1
    return {'event_types': len(EVENTS), 'positive_examples': examples, 'official_schema': 'AsyncAPI 3.0.0',
            'semantic_negative_fixtures': 'validated by existing Maven EventContractTest codecs, not JSON Schema'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='更新生成的AsyncAPI；缺省仅校验无漂移')
    args = parser.parse_args()
    doc = generate()
    target = ROOT / 'docs/asyncapi.json'
    encoded = json.dumps(doc, ensure_ascii=False, indent=2) + '\n'
    if args.write:
        target.write_text(encoded)
    elif not target.exists() or target.read_text() != encoded:
        raise SystemExit('AsyncAPI尚未生成或已漂移，请执行--write并审查')
    print(json.dumps(validate(doc), ensure_ascii=False))


if __name__ == '__main__':
    main()
