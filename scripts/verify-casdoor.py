#!/usr/bin/env python3
"""使用Casdoor真实PKCE授权码验证交易API；不使用client secret、自签JWT或租户请求头。"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request

IAM = os.environ.get('CASDOOR_URL', 'http://localhost:8000').rstrip('/')
API = os.environ.get('TRADE_API_URL', 'http://127.0.0.1:8400').rstrip('/')
CLIENT = 'transaction-center'
REDIRECT = 'http://127.0.0.1:4180/auth/callback'


def call(url, payload=None, token=None, form=False):
    """仅返回内存中的响应；日志不包含口令、授权码或JWT。"""
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if payload is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        payload = (urllib.parse.urlencode(payload) if form else json.dumps(payload)).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, payload, headers), timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


def issue(identity, requested_scope='openid profile', wrong_verifier=False):
    """模拟浏览器采用的S256授权码协议，换令牌时不传应用密钥。"""
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    query = urllib.parse.urlencode({'clientId': CLIENT, 'responseType': 'code', 'redirectUri': REDIRECT,
        'scope': requested_scope, 'state': secrets.token_hex(16), 'nonce': secrets.token_hex(16),
        'code_challenge_method': 'S256', 'code_challenge': challenge})
    status, login = call(IAM + '/api/login?' + query, {'type': 'code', 'signinMethod': 'Password',
        'organization': CLIENT, 'application': 'transaction-center-console',
        'username': identity['username'], 'password': identity['password'], 'autoSignin': False})
    assert status == 200 and login.get('status') == 'ok', 'Casdoor授权码登录失败'
    code = login['data']
    assert isinstance(code, str), 'Casdoor登录需要额外流程'
    status, token = call(IAM + '/api/login/oauth/access_token', {'grant_type': 'authorization_code',
        'client_id': CLIENT, 'code': code, 'redirect_uri': REDIRECT,
        'code_verifier': secrets.token_urlsafe(48) if wrong_verifier else verifier}, form=True)
    if wrong_verifier:
        assert status != 200 or not (token or {}).get('access_token'), '错误PKCE verifier未被拒绝'
        return None
    assert status == 200 and token.get('access_token'), 'PKCE无密钥换令牌失败'
    return token['access_token']


def claims(token):
    """只用于验收断言，API仍独立执行真实验签。"""
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))


def main():
    """验证成功读取、scope不可自授、租户隔离以及认证失败边界。"""
    path = Path(os.environ.get('TRADE_IAM_CREDENTIALS', str(Path(__file__).resolve().parents[1] / 'deploy/.env.casdoor.json')))
    credentials = json.loads(path.read_text())
    admin = issue(credentials)
    identity = claims(admin)
    assert identity['aud'] == [CLIENT] and identity['tenant_id'] == '900001' and 'trade.admin' in identity['scope']
    print('PASS Casdoor PKCE S256授权码，无client secret；aud/scope/tenant_id契约匹配')
    for resource, marker in [('products', '演示商品'), ('members', '演示会员'), ('warehouses', '演示仓库')]:
        status, body = call(API + '/api/v1/trade/admin/' + resource, token=admin)
        assert status == 200 and marker in json.dumps(body, ensure_ascii=False), resource + '缺少演示种子'
        print('PASS admin ' + resource + ': HTTP 200，读取数据库种子')
    status, inventory = call(API + '/api/v1/trade/admin/inventories', token=admin)
    assert status == 200 and len(inventory) == 1 and inventory[0]['available'] == 100, '库存种子不等于100件'
    print('PASS admin inventories: HTTP 200')
    reader = issue(credentials['fixtures']['reader'], 'openid profile trade.admin')
    assert 'trade.admin' not in claims(reader).get('scope', []), '用户可通过请求参数自授scope'
    assert call(API + '/api/v1/trade/admin/products', token=reader)[0] == 403
    print('PASS reader即使请求trade.admin也不能自授：HTTP 403')
    other = issue(credentials['fixtures']['other-admin'])
    assert claims(other)['tenant_id'] == '900002'
    status, body = call(API + '/api/v1/trade/admin/products', token=other)
    assert status == 200 and body == [], '其他租户可见演示商品或返回结构已改变'
    print('PASS tenant 900002: HTTP 200，商品列表为空')
    user_id = CLIENT + '/' + credentials['fixtures']['other-admin']['username']
    _, current = call(IAM + '/api/get-user?' + urllib.parse.urlencode({'id': user_id}), token=other)
    assert current and current.get('data'), '无法读取验收用户属性'
    candidate = current['data']
    candidate['properties'] = dict(candidate.get('properties') or {}, tenant_id='900001')
    call(IAM + '/api/update-user?' + urllib.parse.urlencode({'id': user_id, 'columns': 'properties'}), candidate, token=other)
    assert claims(issue(credentials['fixtures']['other-admin']))['tenant_id'] == '900002', '用户能够自行修改租户'
    print('PASS Casdoor禁止业务管理员自行修改tenant_id')
    invalid = issue(credentials['fixtures']['invalid-tenant'])
    assert call(API + '/api/v1/trade/admin/products', token=invalid)[0] == 401
    assert call(API + '/api/v1/trade/admin/products')[0] == 401
    # 改签名首字符确保改变有效位，排除Base64尾部填充位不影响签名字节的情况。
    parts = admin.split('.')
    parts[2] = ('A' if parts[2][0] != 'A' else 'B') + parts[2][1:]
    assert call(API + '/api/v1/trade/admin/products', token='.'.join(parts))[0] == 401
    print('PASS 非法租户、匿名、篡改签名：HTTP 401')
    issue(credentials, wrong_verifier=True)
    print('PASS 错误PKCE verifier被拒绝')


if __name__ == '__main__':
    main()
