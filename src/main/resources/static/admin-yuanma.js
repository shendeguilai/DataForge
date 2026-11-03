(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const base = '/api/admin/yuanma-heartbeat';
  const secure = location.protocol === 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
  let current = null;
  const date = value => value ? new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short'}).format(new Date(value)) : '尚无';
  async function api(path = '', options = {}) {
    const response = await fetch(base + path, {cache: 'no-store', ...options});
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `请求失败（${response.status}）`);
    return body;
  }
  function render(s) {
    current = s;
    const state = !s.configured ? '尚未配置' : !s.enabled ? '已暂停' : s.connectionState === 'CONNECTED' ? '身份已验证' : s.connectionState === 'AUTH_REQUIRED' ? '身份失效，已暂停' : '需要检查';
    $('#yuanmaState').textContent = state;
    $('#yuanmaEnabled').value = String(s.configured ? s.enabled : true);
    const account = s.userId ? `账号：${s.nickName || s.userName || '教师'}（ID ${s.userId}） · ` : '';
    $('#yuanmaStatus').textContent = account + `${s.message || '尚未录入 Token'} · 最近检查：${date(s.checkedAt)} · 最近成功：${date(s.lastSucceededAt)} · 间隔：${s.intervalSeconds / 60} 分钟`;
    $('#yuanmaVerify').disabled = !s.configured;
    $('#yuanmaClear').disabled = !s.configured;
  }
  async function load() {
    try { render(await api()); }
    catch (e) { $('#yuanmaMessage').textContent = e.message; }
  }
  $('#yuanmaToken').disabled = !secure;
  $('#yuanmaInsecureNotice').classList.toggle('hidden', secure);
  document.querySelector('[data-panel="yuanma"]').addEventListener('click', load);
  $('#yuanmaForm').addEventListener('submit', async event => {
    event.preventDefault();
    const input = $('#yuanmaToken'), button = event.submitter || $('#yuanmaForm button[type="submit"]');
    const credential = input.value;
    if (credential && !secure) { $('#yuanmaMessage').textContent = '请使用 HTTPS 或服务器终端安全录入 Token'; return; }
    button.disabled = true; $('#yuanmaMessage').textContent = '';
    try {
      render(await api('', {method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({credential, enabled: $('#yuanmaEnabled').value === 'true'})}));
      $('#yuanmaMessage').textContent = '设置已保存。心跳仅验证当前身份，能否续期仍取决于源码系统。';
    } catch (e) { $('#yuanmaMessage').textContent = e.message; }
    finally { input.value = ''; button.disabled = false; }
  });
  $('#yuanmaVerify').addEventListener('click', async event => {
    event.currentTarget.disabled = true; $('#yuanmaMessage').textContent = '';
    try { render(await api('/verify', {method: 'POST'})); $('#yuanmaMessage').textContent = '身份验证成功。'; }
    catch (e) { const message = e.message; await load(); $('#yuanmaMessage').textContent = message; }
    finally { $('#yuanmaVerify').disabled = !current?.configured; }
  });
  $('#yuanmaClear').addEventListener('click', async event => {
    if (!confirm('清除源码系统 Token 并停止心跳？不会影响 OJ SID。')) return;
    event.currentTarget.disabled = true; $('#yuanmaMessage').textContent = '';
    try { render(await api('/credential', {method: 'DELETE'})); $('#yuanmaMessage').textContent = '已清除 Token，心跳已停止。'; }
    catch (e) { $('#yuanmaMessage').textContent = e.message; }
    finally { $('#yuanmaClear').disabled = !current?.configured; }
  });
})();
