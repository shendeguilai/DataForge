const $ = (selector) => document.querySelector(selector);
const state = { jobId: localStorage.getItem('dataforge.activeJob'), poller: null, user: null, progressDismissed: false };

const examples = {
  statement: `# 数列求和\n\n给定一个长度为 n 的非负整数序列，请计算所有元素之和。\n\n## 输入格式\n第一行一个整数 n。\n第二行包含 n 个非负整数。\n\n## 输出格式\n输出序列中所有元素之和。\n\n## 数据范围\n1 ≤ n ≤ 100000，0 ≤ aᵢ ≤ 10⁹。`,
  code: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    int n;\n    if (!(cin >> n)) return 0;\n    long long answer = 0, value;\n    while (n--) {\n        cin >> value;\n        answer += value;\n    }\n    cout << answer << '\\n';\n    return 0;\n}`,
  requirements: `无额外要求，请按照题面约束自动设计完整测试数据。`
};

async function boot() {
  $('#statement').value = examples.statement;
  $('#standardCode').value = examples.code;
  $('#requirements').value = examples.requirements;
  bindEvents();
  try {
    const user = await api('/api/auth/me', {}, {silentAuth: true});
    enterApp(user);
  } catch (_) {
    enterAnonymous();
  }
}

function bindEvents() {
  $('#requestForm').addEventListener('submit', submitRequest);
  $('#minusCount').onclick = () => changeCount(-1);
  $('#plusCount').onclick = () => changeCount(1);
  $('#backToEdit').onclick = () => showView('form');
  $('#closePlanView').onclick = closePlanView;
  $('#closeProgressView').onclick = closeProgressView;
  $('#confirmButton').onclick = confirmJob;
  $('#retryPlanButton').onclick = retryPlan;
  $('#newTaskButton').onclick = resetTask;
  $('#refreshJobs').onclick = loadRecent;
  $('#historyButton').onclick = () => $('.recent-panel').scrollIntoView({behavior: 'smooth'});
  $('#logoutButton').onclick = logout;
  $('#loginPrompt').onclick = () => showAuth('login');
  $('#registerPrompt').onclick = () => showAuth('register');
  $('#closeAuth').onclick = hideAuth;
  $('#loginForm').addEventListener('submit', login);
  $('#registerForm').addEventListener('submit', register);
  document.querySelectorAll('[data-auth-tab]').forEach(button => button.onclick = () => switchAuthTab(button.dataset.authTab));
  document.querySelectorAll('[data-copy-target]').forEach(button => button.onclick = () => copyTargetText(button.dataset.copyTarget));
  $('#standardCode').addEventListener('keydown', (event) => {
    if (event.key === 'Tab') {
      event.preventDefault();
      const el = event.target, start = el.selectionStart;
      el.value = el.value.slice(0, start) + '    ' + el.value.slice(el.selectionEnd);
      el.selectionStart = el.selectionEnd = start + 4;
    }
  });
}

async function login(event) {
  event.preventDefault();
  await submitAuth('/api/auth/login', {username: $('#loginUsername').value, password: $('#loginPassword').value});
}

async function register(event) {
  event.preventDefault();
  await submitAuth('/api/auth/register', {username: $('#registerUsername').value, password: $('#registerPassword').value, inviteCode: $('#inviteCode').value});
}

async function submitAuth(url, payload) {
  $('#authError').classList.add('hidden');
  try {
    const user = await api(url, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    enterApp(user);
  } catch (error) {
    $('#authError').textContent = error.message;
    $('#authError').classList.remove('hidden');
  }
}

function enterApp(user) {
  state.user = user;
  $('#authView').classList.add('hidden');
  $('#accountActions').classList.remove('hidden');
  $('#anonymousActions').classList.add('hidden');
  $('#currentUsername').textContent = user.username;
  $('#adminLink').classList.toggle('hidden', user.role !== 'ADMIN');
  loadRecent();
  if (state.jobId) resumeJob(state.jobId);
}

function enterAnonymous() {
  state.user = null;
  $('#authView').classList.add('hidden');
  $('#accountActions').classList.add('hidden');
  $('#anonymousActions').classList.remove('hidden');
  $('#historyCount').textContent = '0';
  $('#recentJobs').innerHTML = '<p class="empty">登录后查看你的生成记录</p>';
}

function showAuth(tab = 'login', message = '登录或注册后，就可以生成并下载你的测试数据。') {
  switchAuthTab(tab);
  $('#authHint').textContent = message;
  $('#authView').classList.remove('hidden');
  setTimeout(() => (tab === 'register' ? $('#registerUsername') : $('#loginUsername')).focus(), 0);
}

function hideAuth() {
  $('#authView').classList.add('hidden');
  $('#authError').classList.add('hidden');
}

async function logout() {
  await fetch('/api/auth/logout',{method:'POST'});
  state.jobId = null; clearInterval(state.poller); localStorage.removeItem('dataforge.activeJob');
  location.reload();
}

function switchAuthTab(tab) {
  document.querySelectorAll('[data-auth-tab]').forEach(button => button.classList.toggle('active', button.dataset.authTab === tab));
  $('#loginForm').classList.toggle('hidden', tab !== 'login');
  $('#registerForm').classList.toggle('hidden', tab !== 'register');
  $('#authError').classList.add('hidden');
}

function changeCount(delta) {
  const input = $('#caseCount');
  input.value = Math.min(100, Math.max(1, Number(input.value || 1) + delta));
}

async function submitRequest(event) {
  event.preventDefault();
  if (!state.user) {
    showAuth('login', '请先登录或注册后再生成数据。');
    return;
  }
  const button = $('#analyzeButton');
  button.disabled = true;
  button.querySelector('span').textContent = '正在分析…';
  try {
    const job = await api('/api/jobs', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        statement: $('#statement').value,
        standardCode: $('#standardCode').value,
        requirements: $('#requirements').value,
        caseCount: Number($('#caseCount').value),
        cppStandard: $('#cppStandard').value
      })
    });
    state.jobId = job.id;
    state.progressDismissed = false;
    localStorage.setItem('dataforge.activeJob', job.id);
    openProgress(job);
    startPolling();
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = '分析并生成方案';
  }
}

async function confirmJob() {
  if (!state.user) {
    showAuth('login', '请先登录或注册后再开始生成。');
    return;
  }
  const button = $('#confirmButton');
  button.disabled = true;
  try {
    const job = await api(`/api/jobs/${state.jobId}/confirm`, {method: 'POST'});
    state.progressDismissed = false;
    openProgress(job);
    startPolling();
  } catch (error) {
    toast(error.message);
    button.disabled = false;
  }
}

async function retryPlan() {
  if (!state.user) {
    showAuth('login', '请先登录或注册后再重新生成方案。');
    return;
  }
  const button = $('#retryPlanButton');
  button.disabled = true;
  button.querySelector('span').textContent = '正在重新生成…';
  try {
    const job = await api(`/api/jobs/${state.jobId}/retry-plan`, {method: 'POST'});
    state.progressDismissed = false;
    openProgress(job);
    startPolling();
  } catch (error) {
    toast(error.message);
    button.disabled = false;
    button.querySelector('span').textContent = '根据错误重新生成方案';
  }
}

function startPolling() {
  clearInterval(state.poller);
  pollJob();
  state.poller = setInterval(pollJob, 900);
}

async function pollJob() {
  if (!state.jobId) return;
  try {
    const job = await api(`/api/jobs/${state.jobId}`);
    if (job.status === 'WAITING_CONFIRMATION') {
      clearInterval(state.poller);
      renderPlan(job);
    } else {
      openProgress(job);
      if (job.status === 'COMPLETED' || job.status === 'FAILED') {
        clearInterval(state.poller);
        loadRecent();
      }
    }
  } catch (error) {
    clearInterval(state.poller);
    toast(error.message);
  }
}

async function resumeJob(id) {
  try {
    const job = await api(`/api/jobs/${id}`);
    if (job.status === 'WAITING_CONFIRMATION') renderPlan(job);
    else if (!['COMPLETED', 'FAILED'].includes(job.status)) { openProgress(job); startPolling(); }
  } catch (_) {
    localStorage.removeItem('dataforge.activeJob');
  }
}

function renderPlan(job) {
  state.jobId = job.id;
  state.progressDismissed = false;
  $('#planSummary').textContent = job.plan.summary;
  $('#estimatedSize').textContent = job.plan.estimatedSize || '未知';
  $('#plannedCount').textContent = `${job.request.caseCount} 组`;
  $('#planGroups').innerHTML = (job.plan.groups || []).map(group =>
    `<div class="plan-group"><b>${escapeHtml(group.range)}</b><span>${escapeHtml(group.purpose)}</span></div>`).join('');
  $('#demoWarning').classList.toggle('hidden', job.plan.aiGenerated);
  $('#confirmButton').disabled = false;
  renderPlanReview(job);
  showView('plan');
  setStep(2);
}

function openProgress(job) {
  if (!state.progressDismissed) showView('progress');
  setStep(3);
  renderProgressReview(job);
  $('#progressBar').style.width = `${job.progress || 0}%`;
  $('#progressPercent').textContent = `${job.progress || 0}%`;
  $('#progressMessage').textContent = job.message || '正在处理';
  $('#errorBox').classList.add('hidden');
  $('#downloadButton').classList.add('hidden');
  $('#retryPlanButton').classList.add('hidden');
  $('#retryPlanButton').disabled = false;
  $('#retryPlanButton').querySelector('span').textContent = '根据错误重新生成方案';
  $('#newTaskButton').classList.add('hidden');
  const orb = $('#statusOrb');
  orb.className = 'status-orb';
  if (job.status === 'CHECKING_CODE') $('#progressTitle').textContent = '正在检查标准程序';
  else if (job.status === 'ANALYZING') $('#progressTitle').textContent = '正在理解你的题目';
  else $('#progressTitle').textContent = '正在锻造测试数据';
  if (job.status === 'COMPLETED') {
    $('#progressTitle').textContent = '数据包已经准备好';
    $('#progressMessage').textContent = '输入、标准输出、源码和清单已打包完成。';
    orb.classList.add('done');
    orb.querySelector('span').textContent = '✓';
    const download = $('#downloadButton');
    download.href = `/api/jobs/${job.id}/download`;
    download.classList.remove('hidden');
    $('#newTaskButton').classList.remove('hidden');
  } else if (job.status === 'FAILED') {
    $('#progressTitle').textContent = '这次生成没有完成';
    orb.classList.add('failed');
    orb.querySelector('span').textContent = '!';
    $('#errorBox').textContent = job.error || '未知错误';
    $('#errorBox').classList.remove('hidden');
    $('#retryPlanButton').classList.remove('hidden');
    $('#newTaskButton').classList.remove('hidden');
  }
}

function showView(view) {
  $('#planView').classList.toggle('hidden', view !== 'plan');
  $('#progressView').classList.toggle('hidden', view !== 'progress');
}

function closePlanView() {
  $('#planView').classList.add('hidden');
  setStep(1);
  loadRecent();
}

function closeProgressView() {
  state.progressDismissed = true;
  $('#progressView').classList.add('hidden');
  setStep(1);
  loadRecent();
}

function setStep(number) {
  document.querySelectorAll('.step').forEach(step => step.classList.toggle('active', Number(step.dataset.step) <= number));
}

function resetTask() {
  state.jobId = null;
  clearInterval(state.poller);
  localStorage.removeItem('dataforge.activeJob');
  showView('form');
  setStep(1);
  window.scrollTo({top: 0, behavior: 'smooth'});
}

async function loadRecent() {
  try {
    const jobs = await api('/api/jobs');
    $('#historyCount').textContent = jobs.length;
    $('#recentJobs').innerHTML = jobs.length ? jobs.map(job => {
      const title = (job.request.statement.match(/^#\s+(.+)$/m) || [,'未命名任务'])[1];
      return `<div class="recent-item" data-id="${job.id}"><strong>${escapeHtml(title)}</strong><span>${statusText(job.status)} · ${new Date(job.createdAt).toLocaleString()}</span><small>点击查看题面、AC 代码与数据规划</small></div>`;
    }).join('') : '<p class="empty">还没有生成记录</p>';
    document.querySelectorAll('.recent-item').forEach(item => item.onclick = () => openExisting(item.dataset.id));
  } catch (_) {}
}

async function openExisting(id) {
  state.jobId = id;
  state.progressDismissed = false;
  localStorage.setItem('dataforge.activeJob', id);
  const job = await api(`/api/jobs/${id}`);
  if (job.status === 'WAITING_CONFIRMATION') renderPlan(job);
  else { openProgress(job); if (!['COMPLETED','FAILED'].includes(job.status)) startPolling(); }
}

function statusText(status) {
  return ({CHECKING_CODE:'检查代码',ANALYZING:'分析中',WAITING_CONFIRMATION:'待确认',QUEUED:'排队中',COMPILING:'编译中',GENERATING:'生成中',PACKAGING:'打包中',COMPLETED:'已完成',FAILED:'失败'})[status] || status;
}

function renderPlanReview(job) {
  $('#planReviewStatement').textContent = job.request?.statement || '暂无题面';
  $('#planReviewCode').textContent = job.request?.standardCode || '暂无 AC / 标准程序';
  $('#planReviewRequirements').textContent = job.request?.requirements || '暂无数据要求';
}

function renderProgressReview(job) {
  const request = job.request || {};
  const plan = job.plan;
  $('#progressReviewCaseCount').textContent = request.caseCount ? `${request.caseCount} 组` : '—';
  $('#progressReviewCppStandard').textContent = request.cppStandard || '—';
  $('#progressReviewStatement').textContent = request.statement || '暂无题面';
  $('#progressReviewCode').textContent = request.standardCode || '暂无 AC / 标准程序';
  $('#progressReviewRequirements').textContent = request.requirements || '暂无数据要求';
  $('#progressReviewPlanSummary').textContent = plan?.summary || '数据规划还在生成中，稍后会显示。';
  $('#progressReviewPlanGroups').innerHTML = plan?.groups?.length
    ? plan.groups.map(group => `<div class="plan-group"><b>${escapeHtml(group.range)}</b><span>${escapeHtml(group.purpose)}</span></div>`).join('')
    : '<p class="empty">暂无规划分组</p>';
}

async function api(url, options = {}, config = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && !config.silentAuth) {
      showAuth('login', '请先登录或注册后继续操作。');
    }
    throw new Error(body.error || (response.status === 401 ? '请先登录或注册' : `请求失败 (${response.status})`));
  }
  return body;
}

function escapeHtml(value = '') {
  const div = document.createElement('div'); div.textContent = value; return div.innerHTML;
}

async function copyTargetText(targetId) {
  const target = document.getElementById(targetId);
  const text = target?.innerText || target?.textContent || '';
  if (!text.trim()) {
    toast('暂无可复制内容');
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    toast('已复制到剪贴板');
  } catch (_) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    textarea.remove();
    toast('已复制到剪贴板');
  }
}

function toast(message) {
  const el = $('#toast'); el.textContent = message; el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 3500);
}

boot();
