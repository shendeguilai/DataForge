#!/usr/bin/env python3
"""Configure the token through the app container's loopback; never print or pass secrets as arguments."""
import argparse
import getpass
import http.cookies
import json
import re
import subprocess
import sys

BASE = "http://127.0.0.1:8080"
PATHS = {"/api/auth/login", "/api/auth/csrf", "/api/auth/logout", "/api/admin/yuanma-heartbeat"}


class ConfigurationError(Exception):
    pass


def quote(value):
    # Curl config syntax, not a shell; prevent injected config lines.
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\r', '\\r').replace('\n', '\\n') + '"'


def call(container, path, method="GET", body=None, headers=(), cookie=""):
    if path not in PATHS or method not in {"GET", "POST", "PUT"}:
        raise ConfigurationError("请求参数无效")
    config = ['url = ' + quote(BASE + path), 'request = ' + quote(method)]
    if cookie:
        config.append('header = ' + quote('Cookie: ' + cookie))
    for name, value in headers:
        if not re.fullmatch(r"[A-Za-z0-9-]+", name) or '\r' in value or '\n' in value:
            raise ConfigurationError("认证信息格式异常")
        config.append('header = ' + quote(name + ': ' + value))
    if body is not None:
        config.append('header = "Content-Type: application/json"')
        config.append('data = ' + quote(json.dumps(body, ensure_ascii=True, separators=(',', ':'))))
    # --disable must be the first curl option. Never send local credentials through a proxy.
    args = ['docker', 'exec', '-i', container, 'curl', '--disable', '--noproxy', '*',
            '--silent', '--show-error', '--include', '--connect-timeout', '5', '--max-time', '30', '--config', '-']
    try:
        result = subprocess.run(args, input=('\n'.join(config) + '\n').encode(), capture_output=True, timeout=40)
    except (OSError, subprocess.TimeoutExpired):
        raise ConfigurationError("无法连接应用容器，请确认 Docker 和 DataForge 正在运行") from None
    if result.returncode or len(result.stdout) > 1024 * 1024:
        raise ConfigurationError("应用容器连接失败，请检查容器状态")
    head, separator, data = result.stdout.partition(b'\r\n\r\n')
    if not separator:
        raise ConfigurationError("应用返回了不完整的响应")
    lines = head.decode('iso-8859-1').split('\r\n')
    match = re.fullmatch(r'HTTP/\S+ (\d{3})(?: .*)?', lines[0])
    if not match:
        raise ConfigurationError("应用返回了无法识别的响应")
    cookies = http.cookies.SimpleCookie()
    for line in lines[1:]:
        if line.lower().startswith('set-cookie:'):
            cookies.load(line.split(':', 1)[1].strip())
    session = cookies['DATAFORGE_SESSION'].value if 'DATAFORGE_SESSION' in cookies else None
    try:
        payload = json.loads(data) if data else {}
    except (UnicodeError, ValueError):
        payload = {}
    return int(match.group(1)), payload if isinstance(payload, dict) else {}, session


def configure(container, username, password, credential):
    session = ''
    csrf = None
    try:
        status, user, session = call(container, '/api/auth/login', 'POST',
                                     {'username': username, 'password': password})
        if status != 200 or not session:
            raise ConfigurationError("DataForge 登录失败，请核对管理员账号和密码")
        status, csrf, updated = call(container, '/api/auth/csrf', cookie='DATAFORGE_SESSION=' + session)
        session = updated or session
        if status != 200 or not isinstance(csrf.get('headerName'), str) or not isinstance(csrf.get('token'), str):
            raise ConfigurationError("无法获取安全验证信息")
        if user.get('role') != 'ADMIN':
            raise ConfigurationError("需要 DataForge 管理员账号")
        headers = [(csrf['headerName'], csrf['token'])]
        status, result, updated = call(container, '/api/admin/yuanma-heartbeat', 'PUT',
                                       {'credential': credential, 'enabled': True}, headers,
                                       'DATAFORGE_SESSION=' + session)
        session = updated or session
        if status != 200 or not result.get('configured'):
            code = result.get('code')
            safe = {'AUTH_REQUIRED': '源码系统未接受该 Token，请检查并重新登录后再录入',
                    'PERMISSION_DENIED': '源码系统拒绝访问，请检查账号权限',
                    'RATE_LIMITED': '源码系统暂时限流，请稍后再试',
                    'NETWORK_ERROR': '服务器连接源码系统失败，请检查网络',
                    'INVALID_RESPONSE': '源码系统响应异常，无法确认身份',
                    'UPSTREAM_ERROR': '源码系统暂时无法完成验证'}
            raise ConfigurationError(safe.get(code, "保存失败，请检查管理员权限、Token 格式和服务状态"))
        return result
    finally:
        if session and csrf and isinstance(csrf.get('headerName'), str) and isinstance(csrf.get('token'), str):
            try:
                call(container, '/api/auth/logout', 'POST', headers=[(csrf['headerName'], csrf['token'])],
                     cookie='DATAFORGE_SESSION=' + session)
            except ConfigurationError:
                pass


def main():
    parser = argparse.ArgumentParser(description="安全配置源码系统心跳（不打印密码或 Token）")
    parser.add_argument('--container', default='dataforge-app-1', help='DataForge 应用容器名')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', args.container):
        raise ConfigurationError("容器名称格式无效")
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ConfigurationError("请在交互式服务器终端运行，避免输入被显示或记录")
    username = input('DataForge 管理员用户名：').strip()
    password = getpass.getpass('DataForge 管理员密码（输入不显示）：')
    credential = getpass.getpass('源码系统 Admin-Token 纯值（输入不显示）：')
    if not username or not password or not credential:
        raise ConfigurationError("输入不能为空，未保存设置")
    configure(args.container, username, password, credential)
    print('验证并保存成功，源码系统自动心跳已启用。心跳成功不保证延长上游有效期。')


if __name__ == '__main__':
    try:
        main()
    except (ConfigurationError, KeyboardInterrupt, EOFError) as error:
        print('未完成配置：' + (str(error) if isinstance(error, ConfigurationError) else '已取消'), file=sys.stderr)
        sys.exit(1)
