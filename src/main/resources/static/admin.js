const $a = s => document.querySelector(s);
let adminSettings = {dailyGenerationLimit: 30};
let adminJobs = [];

async function request(url, options = {}) {
  const r = await fetch(url, options);
  const b = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(b.error || `请求失败 (${r.status})`);
  return b;
}

async function init() {
  try {
    const me = await request('/api/auth/me');
    if (me.role !== 'ADMIN') { location.href = '/'; return; }
    $a('#adminName').textContent = me.username;
    bind();
    await loadConfig();
    await Promise.all([loadUsers(), loadJobs()]);
  } catch (e) {
    location.href = '/';
  }
}

function bind() {
  document.querySelectorAll('[data-panel]').forEach(b => b.onclick = () => switchPanel(b.dataset.panel));
  $a('#aiForm').onsubmit = saveConfig;
  $a('#refreshAdminJobs').onclick = loadJobs;
  $a('#closeJobDetail').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  $a('#closeJobDetailX').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  document.querySelectorAll('[data-copy-target]').forEach(b => b.onclick = () => copyTargetText(b.dataset.copyTarget));
  $a('#adminLogout').onclick = async () => { await fetch('/api/auth/logout', {method:'POST'}); location.href = '/'; };
}

function switchPanel(name) {
  document.querySelectorAll('[data-panel]').forEach(b => b.classList.toggle('active', b.dataset.panel === name));
  ['users','ai','jobs'].forEach(n => $a(`#${n}Panel`).classList.toggle('hidden', n !== name));
}

async function loadUsers() {
  const users = await request('/api/admin/users');
  const defaultLimit = adminSettings.dailyGenerationLimit || 30;
  $a('#userTotal').textContent = users.length;
  $a('#usersBody').innerHTML = users.map(u => {
    const limit = u.dailyGenerationLimit || defaultLimit;
    const toggle = u.role === 'ADMIN' ? '—' : `<button class="mini-btn" data-user-id="${u.id}" data-enabled="${!u.enabled}">${u.enabled ? '禁用' : '启用'}</button>`;
    return `<tr><td>${u.id}</td><td><strong>${esc(u.username)}</strong></td><td>${u.role}</td><td>${date(u.createdAt)}</td><td><span class="badge ${u.enabled ? '' : 'off'}">${u.enabled ? '正常' : '已禁用'}</span></td><td><input class="quota-input" data-quota-id="${u.id}" type="number" min="1" max="10000" value="${limit}"></td><td>${toggle}<button class="mini-btn" data-quota-save="${u.id}">保存额度</button></td></tr>`;
  }).join('');
  document.querySelectorAll('[data-user-id]').forEach(b => b.onclick = async () => {
    await request(`/api/admin/users/${b.dataset.userId}`, {method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled:b.dataset.enabled === 'true'})});
    await loadUsers();
  });
  document.querySelectorAll('[data-quota-save]').forEach(b => b.onclick = async () => {
    const input = $a(`[data-quota-id="${b.dataset.quotaSave}"]`);
    await request(`/api/admin/users/${b.dataset.quotaSave}`, {method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({dailyGenerationLimit:Number(input.value)})});
    toast('用户额度已保存');
    await loadUsers();
  });
}

async function loadConfig() {
  const c = await request('/api/admin/ai-config');
  adminSettings = c;
  $a('#aiBaseUrl').value = c.baseUrl || '';
  $a('#aiModel').value = c.model || '';
  $a('#dailyGenerationLimit').value = c.dailyGenerationLimit || 30;
  $a('#keyState').textContent = c.apiKeyConfigured ? 'API Key 已配置' : '尚未配置 API Key';
}

async function saveConfig(e) {
  e.preventDefault();
  try {
    await request('/api/admin/ai-config', {method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({
      baseUrl:$a('#aiBaseUrl').value,
      model:$a('#aiModel').value,
      apiKey:$a('#aiApiKey').value,
      dailyGenerationLimit:Number($a('#dailyGenerationLimit').value)
    })});
    $a('#aiApiKey').value = '';
    await loadConfig();
    await loadUsers();
    toast('配置已保存');
  } catch (err) {
    toast(err.message);
  }
}

async function loadJobs() {
  const jobs = await request('/api/admin/jobs');
  adminJobs = jobs;
  $a('#jobTotal').textContent = jobs.length;
  $a('#successTotal').textContent = jobs.filter(j => j.status === 'COMPLETED').length;
  $a('#jobsBody').innerHTML = jobs.map(j => `<tr><td><button class="job-title-btn" data-job-detail="${j.id}">${esc(j.title)}</button><br><small>${j.id}</small></td><td>${j.userId}</td><td>${j.caseCount}</td><td><span class="badge ${j.status === 'FAILED' ? 'off' : ''}">${j.status}</span></td><td>${date(j.createdAt)}</td><td class="error-cell" title="${esc(j.error || '')}">${esc(j.error || '—')}</td></tr>`).join('');
  document.querySelectorAll('[data-job-detail]').forEach(b => b.onclick = () => showJobDetail(b.dataset.jobDetail));
}

function showJobDetail(id) {
  const job = adminJobs.find(j => String(j.id) === String(id));
  if (!job) { toast('任务不存在或已刷新'); return; }
  $a('#jobDetailTitle').textContent = job.title || '未命名任务';
  $a('#jobDetailId').textContent = job.id || '—';
  $a('#jobDetailUserId').textContent = job.userId || '—';
  $a('#jobDetailStatus').textContent = job.status || '—';
  $a('#jobDetailCaseCount').textContent = job.caseCount ? `${job.caseCount} 组` : '—';
  $a('#jobDetailCppStandard').textContent = job.cppStandard || '—';
  $a('#jobDetailCreatedAt').textContent = date(job.createdAt);
  $a('#jobDetailStatement').textContent = job.statement || '暂无题面';
  $a('#jobDetailStandardCode').textContent = job.standardCode || '暂无 AC / 标准程序';
  $a('#jobDetailRequirements').textContent = job.requirements || '暂无数据要求';
  $a('#jobDetailPlanSummary').textContent = job.planSummary || '暂无数据规划摘要';
  $a('#jobDetailPlanGroups').innerHTML = job.planGroups?.length
    ? job.planGroups.map(group => `<div class="plan-group"><b>${esc(group.range || '')}</b><span>${esc(group.purpose || '')}</span></div>`).join('')
    : '<p class="empty">暂无规划分组</p>';
  $a('#jobDetailError').textContent = job.error || '';
  $a('#jobDetailError').classList.toggle('hidden', !job.error);
  $a('#jobDetailModal').classList.remove('hidden');
}

function esc(v = '') { const d = document.createElement('div'); d.textContent = v; return d.innerHTML; }
function date(v) { return new Date(v).toLocaleString(); }
async function copyTargetText(targetId) {
  const target = document.getElementById(targetId);
  const text = target?.innerText || target?.textContent || '';
  if (!text.trim()) { toast('暂无可复制内容'); return; }
  try {
    await navigator.clipboard.writeText(text);
  } catch (_) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    textarea.remove();
  }
  toast('已复制到剪贴板');
}
function toast(m) { const e = $a('#adminToast'); e.textContent = m; e.classList.add('show'); setTimeout(() => e.classList.remove('show'), 2500); }
init();
