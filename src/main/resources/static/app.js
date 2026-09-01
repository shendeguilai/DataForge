const $ = (selector) => document.querySelector(selector);
const UI = window.DataForgeUI || {
  openDialog: (dialog) => dialog?.classList.remove('hidden'),
  closeDialog: (dialog) => dialog?.classList.add('hidden'),
  toast: (message) => window.alert(message),
  setBusy: (button, busy, label) => { if (button) { button.disabled = busy; if (label) button.textContent = label; } }
};
const state = {
  jobId: localStorage.getItem('dataforge.activeJob'),
  pollTimer: null,
  pollGeneration: 0,
  requestToken: 0,
  recentToken: 0,
  user: null,
  progressDismissed: false
};

const examples = {
  statement: `# 数列求和\n\n给定一个长度为 n 的非负整数序列，请计算所有元素之和。\n\n## 输入格式\n第一行一个整数 n。\n第二行包含 n 个非负整数。\n\n## 输出格式\n输出序列中所有元素之和。\n\n## 数据范围\n1 ≤ n ≤ 100000，0 ≤ aᵢ ≤ 10⁹。`,
  code: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    int n;\n    if (!(cin >> n)) return 0;\n    long long answer = 0, value;\n    while (n--) {\n        cin >> value;\n        answer += value;\n    }\n    cout << answer << '\\n';\n    return 0;\n}`,
  requirements: `无额外要求，请按照题面约束自动设计完整测试数据。`
};

async function boot() {
  if ($('#statement') && !$('#statement').value) $('#statement').value = examples.statement;
  if ($('#standardCode') && !$('#standardCode').value) $('#standardCode').value = examples.code;
  if ($('#requirements') && !$('#requirements').value) $('#requirements').value = examples.requirements;
  bindEvents();
  try {
    const user = await api('/api/auth/me', {}, {silentAuth: true});
    enterApp(user);
  } catch (_) {
    enterAnonymous();
  }
}

function bindEvents() {
  $('#requestForm')?.addEventListener('submit', submitRequest);
  $('#minusCount')?.addEventListener('click', () => changeCount(-1));
  $('#plusCount')?.addEventListener('click', () => changeCount(1));
  $('#backToEdit')?.addEventListener('click', () => showView('form'));
  $('#closePlanView')?.addEventListener('click', closePlanView);
  $('#closeProgressView')?.addEventListener('click', closeProgressView);
  $('#confirmButton')?.addEventListener('click', confirmJob);
  $('#retryPlanButton')?.addEventListener('click', retryPlan);
  $('#newTaskButton')?.addEventListener('click', resetTask);
  $('#refreshJobs')?.addEventListener('click', loadRecent);
  $('#historyButton')?.addEventListener('click', () => $('.recent-panel')?.scrollIntoView({behavior: 'smooth'}));
  $('#changePasswordButton')?.addEventListener('click', showPassword);
  $('#logoutButton')?.addEventListener('click', logout);
  $('#loginPrompt')?.addEventListener('click', (event) => showAuth('login', undefined, event.currentTarget));
  $('#registerPrompt')?.addEventListener('click', (event) => showAuth('register', undefined, event.currentTarget));
  $('#closeAuth')?.addEventListener('click', hideAuth);
  $('#loginForm')?.addEventListener('submit', login);
  $('#registerForm')?.addEventListener('submit', register);
  $('#closePassword')?.addEventListener('click', hidePassword);
  $('#passwordForm')?.addEventListener('submit', changePassword);
  document.querySelectorAll('[data-auth-tab]').forEach((button) => {
    button.addEventListener('click', () => switchAuthTab(button.dataset.authTab));
  });
  document.querySelectorAll('[data-copy-target]').forEach((button) => {
    button.addEventListener('click', () => copyTargetText(button.dataset.copyTarget));
  });
  $('#standardCode')?.addEventListener('keydown', (event) => {
    if (event.key !== 'Tab') return;
    event.preventDefault();
    const element = event.target;
    const start = element.selectionStart;
    element.value = element.value.slice(0, start) + '    ' + element.value.slice(element.selectionEnd);
    element.selectionStart = element.selectionEnd = start + 4;
  });
}

async function login(event) {
  event.preventDefault();
  await submitAuth('/api/auth/login', {
    username: $('#loginUsername').value,
    password: $('#loginPassword').value
  }, event.submitter);
}

async function register(event) {
  event.preventDefault();
  await submitAuth('/api/auth/register', {
    username: $('#registerUsername').value,
    password: $('#registerPassword').value,
    inviteCode: $('#inviteCode').value
  }, event.submitter);
}

async function submitAuth(url, payload, submitButton) {
  const errorBox = $('#authError');
  errorBox.classList.add('hidden');
  UI.setBusy(submitButton || document.querySelector('#loginForm:not(.hidden) button[type="submit"], #registerForm:not(.hidden) button[type="submit"]'), true, '正在处理…');
  try {
    const user = await api(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
    enterApp(user);
    hideAuth();
    if ($('#loginPassword')) $('#loginPassword').value = '';
    if ($('#registerPassword')) $('#registerPassword').value = '';
    UI.toast(url.endsWith('/register') ? '注册成功，已登录' : '登录成功', {variant: 'success'});
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    UI.setBusy(submitButton || document.querySelector('#loginForm:not(.hidden) button[type="submit"], #registerForm:not(.hidden) button[type="submit"]'), false);
  }
}

function enterApp(user) {
  state.user = user;
  hideAuth();
  $('#accountActions')?.classList.remove('hidden');
  $('#anonymousActions')?.classList.add('hidden');
  if ($('#currentUsername')) $('#currentUsername').textContent = user.username;
  $('#adminLink')?.classList.toggle('hidden', user.role !== 'ADMIN');
  loadRecent();
  if (state.jobId) resumeJob(state.jobId);
}

function enterAnonymous() {
  state.user = null;
  hideAuth();
  $('#accountActions')?.classList.add('hidden');
  $('#anonymousActions')?.classList.remove('hidden');
  if ($('#historyCount')) $('#historyCount').textContent = '0';
  if ($('#recentJobs')) $('#recentJobs').replaceChildren(Object.assign(document.createElement('p'), {className: 'empty', textContent: '登录后查看你的生成记录'}));
}

function showAuth(tab = 'login', message, trigger) {
  switchAuthTab(tab);
  if (message) $('#authHint').textContent = message;
  UI.openDialog($('#authView'), trigger);
  window.setTimeout(() => (tab === 'register' ? $('#registerUsername') : $('#loginUsername'))?.focus(), 0);
}

function hideAuth() {
  UI.closeDialog($('#authView'));
  $('#authError')?.classList.add('hidden');
}

function showPassword(event) {
  $('#passwordForm')?.reset();
  $('#passwordError')?.classList.add('hidden');
  UI.openDialog($('#passwordView'), event?.currentTarget);
  window.setTimeout(() => $('#currentPassword')?.focus(), 0);
}

function hidePassword() {
  UI.closeDialog($('#passwordView'));
  $('#passwordError')?.classList.add('hidden');
}

async function changePassword(event) {
  event.preventDefault();
  const errorBox = $('#passwordError');
  const newPassword = $('#newPassword').value;
  if (newPassword !== $('#confirmNewPassword').value) {
    errorBox.textContent = '两次输入的新密码不一致';
    errorBox.classList.remove('hidden');
    return;
  }
  const button = $('#passwordForm button[type="submit"]');
  UI.setBusy(button, true, '正在保存…');
  errorBox.classList.add('hidden');
  try {
    await api('/api/auth/password', {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({currentPassword: $('#currentPassword').value, newPassword})
    });
    hidePassword();
    UI.toast('密码修改成功', {variant: 'success'});
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    UI.setBusy(button, false);
  }
}

async function logout() {
  try {
    const response = await fetch('/api/auth/logout', {method: 'POST'});
    if (!response.ok) throw new Error('退出登录失败，请稍后重试');
    invalidateAsync();
    state.jobId = null;
    localStorage.removeItem('dataforge.activeJob');
    location.reload();
  } catch (error) {
    UI.toast(error.message, {variant: 'error'});
  }
}

function switchAuthTab(tab) {
  document.querySelectorAll('[data-auth-tab]').forEach((button) => {
    button.classList.toggle('active', button.dataset.authTab === tab);
  });
  $('#loginForm')?.classList.toggle('hidden', tab !== 'login');
  $('#registerForm')?.classList.toggle('hidden', tab !== 'register');
  $('#authError')?.classList.add('hidden');
}

function changeCount(delta) {
  const input = $('#caseCount');
  if (!input) return;
  input.value = Math.min(100, Math.max(1, Number(input.value || 1) + delta));
}

async function submitRequest(event) {
  event.preventDefault();
  if (!state.user) {
    showAuth('login', '请先登录或注册后再生成数据。', event.submitter);
    return;
  }
  invalidatePolling();
  const token = ++state.requestToken;
  const button = $('#analyzeButton');
  UI.setBusy(button, true, '正在分析…');
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
    if (token !== state.requestToken) return;
    state.jobId = job.id;
    state.progressDismissed = false;
    localStorage.setItem('dataforge.activeJob', job.id);
    openProgress(job);
    startPolling();
  } catch (error) {
    if (token === state.requestToken) UI.toast(error.message, {variant: 'error'});
  } finally {
    if (token === state.requestToken) UI.setBusy(button, false);
  }
}

async function confirmJob(event) {
  if (!state.user) {
    showAuth('login', '请先登录或注册后再开始生成。', event?.currentTarget);
    return;
  }
  const id = state.jobId;
  if (!id) return;
  invalidatePolling();
  const token = ++state.requestToken;
  const button = $('#confirmButton');
  UI.setBusy(button, true, '正在启动…');
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}/confirm`, {method: 'POST'});
    if (token !== state.requestToken || id !== state.jobId) return;
    state.progressDismissed = false;
    openProgress(job);
    startPolling();
  } catch (error) {
    if (token === state.requestToken) UI.toast(error.message, {variant: 'error'});
  } finally {
    if (token === state.requestToken) UI.setBusy(button, false);
  }
}

async function retryPlan(event) {
  if (!state.user) {
    showAuth('login', '请先登录或注册后再重新生成方案。', event?.currentTarget);
    return;
  }
  const id = state.jobId;
  if (!id) return;
  invalidatePolling();
  const token = ++state.requestToken;
  const button = $('#retryPlanButton');
  UI.setBusy(button, true, '正在重新生成…');
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}/retry-plan`, {method: 'POST'});
    if (token !== state.requestToken || id !== state.jobId) return;
    state.progressDismissed = false;
    openProgress(job);
    startPolling();
  } catch (error) {
    if (token === state.requestToken) UI.toast(error.message, {variant: 'error'});
  } finally {
    if (token === state.requestToken) UI.setBusy(button, false);
  }
}

function invalidatePolling() {
  state.pollGeneration += 1;
  if (state.pollTimer) window.clearTimeout(state.pollTimer);
  state.pollTimer = null;
}

function invalidateAsync() {
  invalidatePolling();
  state.requestToken += 1;
  state.recentToken += 1;
}

function startPolling() {
  invalidatePolling();
  const generation = state.pollGeneration;
  schedulePoll(generation, 0);
}

function schedulePoll(generation, delay) {
  if (generation !== state.pollGeneration || !state.jobId) return;
  if (state.pollTimer) window.clearTimeout(state.pollTimer);
  state.pollTimer = window.setTimeout(() => pollJob(generation), delay);
}

async function pollJob(generation) {
  state.pollTimer = null;
  const id = state.jobId;
  if (!id || generation !== state.pollGeneration) return;
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}`);
    if (generation !== state.pollGeneration || id !== state.jobId) return;
    if (job.status === 'WAITING_CONFIRMATION') {
      invalidatePolling();
      renderPlan(job);
      return;
    }
    openProgress(job);
    if (isTerminal(job.status)) {
      invalidatePolling();
      loadRecent();
    } else {
      schedulePoll(generation, 900);
    }
  } catch (error) {
    if (generation !== state.pollGeneration || id !== state.jobId) return;
    invalidatePolling();
    UI.toast(`任务状态暂时无法读取：${error.message}`, {variant: 'error'});
  }
}

async function resumeJob(id) {
  const token = ++state.requestToken;
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}`);
    if (token !== state.requestToken || state.jobId !== id) return;
    if (job.status === 'WAITING_CONFIRMATION') renderPlan(job);
    else {
      openProgress(job);
      if (!isTerminal(job.status)) startPolling();
    }
  } catch (error) {
    if (token !== state.requestToken || state.jobId !== id) return;
    localStorage.removeItem('dataforge.activeJob');
    state.jobId = null;
    UI.toast(`最近任务暂时无法打开：${error.message}`, {variant: 'error'});
  }
}

function renderPlan(job) {
  state.jobId = job.id;
  state.progressDismissed = false;
  $('#planSummary').textContent = job.plan?.summary || '方案摘要暂未生成';
  $('#estimatedSize').textContent = job.plan?.estimatedSize || '未知';
  $('#plannedCount').textContent = `${job.request?.caseCount || 0} 组`;
  renderGroups($('#planGroups'), job.plan?.groups);
  $('#demoWarning')?.classList.toggle('hidden', Boolean(job.plan?.aiGenerated));
  $('#confirmButton').disabled = false;
  renderPlanReview(job);
  showView('plan');
  setStep(2);
}

function renderGroups(container, groups) {
  if (!container) return;
  container.replaceChildren();
  if (!Array.isArray(groups) || !groups.length) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = '暂无规划分组';
    container.appendChild(empty);
    return;
  }
  groups.forEach((group) => {
    const row = document.createElement('div');
    row.className = 'plan-group';
    const range = document.createElement('b');
    range.textContent = group?.range || '—';
    const purpose = document.createElement('span');
    purpose.textContent = group?.purpose || '—';
    row.append(range, purpose);
    container.appendChild(row);
  });
}

function openProgress(job) {
  if (!job) return;
  if (!state.progressDismissed) showView('progress');
  setStep(3);
  renderProgressReview(job);
  $('#progressBar').style.width = `${Math.max(0, Math.min(100, Number(job.progress) || 0))}%`;
  $('#progressPercent').textContent = `${Math.max(0, Math.min(100, Number(job.progress) || 0))}%`;
  $('#progressMessage').textContent = job.message || '正在处理';
  $('#errorBox')?.classList.add('hidden');
  $('#downloadButton')?.classList.add('hidden');
  $('#retryPlanButton')?.classList.add('hidden');
  $('#retryPlanButton').disabled = false;
  $('#retryPlanButton').querySelector('span').textContent = '根据错误重新生成方案';
  $('#newTaskButton')?.classList.add('hidden');
  const orb = $('#statusOrb');
  orb.className = 'status-orb';
  if (job.status === 'CHECKING_CODE') $('#progressTitle').textContent = '正在检查标准程序';
  else if (job.status === 'ANALYZING') $('#progressTitle').textContent = '正在理解你的题目';
  else $('#progressTitle').textContent = '正在锻造测试数据';
  if (job.status === 'COMPLETED') {
    setStep(4);
    $('#progressTitle').textContent = '数据包已经准备好';
    $('#progressMessage').textContent = '输入、标准输出、源码和清单已打包完成。';
    orb.classList.add('done');
    orb.querySelector('span').textContent = '✓';
    const download = $('#downloadButton');
    download.href = `/api/jobs/${encodeURIComponent(job.id)}/download`;
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
  const plan = $('#planView');
  const progress = $('#progressView');
  const active = document.activeElement;
  const transitionTrigger = active && !plan?.contains(active) && !progress?.contains(active)
    ? active
    : $('#analyzeButton');
  if (view === 'plan') {
    UI.closeDialog(progress);
    UI.openDialog(plan, transitionTrigger);
  } else if (view === 'progress') {
    UI.closeDialog(plan);
    UI.openDialog(progress, transitionTrigger);
  } else {
    UI.closeDialog(plan);
    UI.closeDialog(progress);
  }
}

function closePlanView() {
  UI.closeDialog($('#planView'));
  setStep(1);
  loadRecent();
}

function closeProgressView() {
  state.progressDismissed = true;
  UI.closeDialog($('#progressView'));
  setStep(1);
  loadRecent();
}

function setStep(number) {
  document.querySelectorAll('.step').forEach((step) => step.classList.toggle('active', Number(step.dataset.step) <= number));
}

function resetTask() {
  invalidateAsync();
  state.jobId = null;
  localStorage.removeItem('dataforge.activeJob');
  state.progressDismissed = false;
  showView('form');
  setStep(1);
  window.scrollTo({top: 0, behavior: 'smooth'});
}

async function loadRecent() {
  if (!state.user) return;
  const token = ++state.recentToken;
  try {
    const jobs = await api('/api/jobs');
    if (token !== state.recentToken || !state.user) return;
    $('#historyCount').textContent = String(jobs.length);
    const container = $('#recentJobs');
    container.replaceChildren();
    if (!jobs.length) {
      const empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = '还没有生成记录';
      container.appendChild(empty);
      return;
    }
    jobs.forEach((job) => {
      const item = document.createElement('div');
      item.className = 'recent-item';
      item.dataset.id = job.id;
      item.tabIndex = 0;
      const title = (job.request?.statement?.match(/^#\s+(.+)$/m) || [, '未命名任务'])[1];
      const heading = document.createElement('strong');
      heading.textContent = title;
      const details = document.createElement('span');
      details.textContent = `${statusText(job.status)} · ${new Date(job.createdAt).toLocaleString()}`;
      const hint = document.createElement('small');
      hint.textContent = '点击查看题面、AC 代码与数据规划';
      item.append(heading, details, hint);
      item.addEventListener('click', () => openExisting(job.id));
      item.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openExisting(job.id); } });
      container.appendChild(item);
    });
  } catch (error) {
    if (token === state.recentToken && state.user) UI.toast(`最近任务暂时无法读取：${error.message}`, {variant: 'error'});
  }
}

async function openExisting(id) {
  if (!id || !state.user) return;
  const previousId = state.jobId;
  const token = ++state.requestToken;
  invalidatePolling();
  state.jobId = id;
  state.progressDismissed = false;
  localStorage.setItem('dataforge.activeJob', id);
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}`);
    if (token !== state.requestToken || state.jobId !== id) return;
    if (job.status === 'WAITING_CONFIRMATION') renderPlan(job);
    else {
      openProgress(job);
      if (!isTerminal(job.status)) startPolling();
    }
  } catch (error) {
    if (token !== state.requestToken || state.jobId !== id) return;
    state.jobId = previousId;
    if (previousId) localStorage.setItem('dataforge.activeJob', previousId);
    else localStorage.removeItem('dataforge.activeJob');
    UI.toast(`任务详情暂时无法打开：${error.message}`, {variant: 'error'});
  }
}

function isTerminal(status) { return status === 'COMPLETED' || status === 'FAILED'; }

function statusText(status) {
  return ({CHECKING_CODE: '检查代码', ANALYZING: '分析中', WAITING_CONFIRMATION: '待确认', QUEUED: '排队中', COMPILING: '编译中', GENERATING: '生成中', PACKAGING: '打包中', COMPLETED: '已完成', FAILED: '失败'})[status] || status;
}

function renderPlanReview(job) {
  $('#planReviewStatement').textContent = job.request?.statement || '暂无题面';
  $('#planReviewCode').textContent = job.request?.standardCode || '暂无 AC / 标准程序';
  $('#planReviewRequirements').textContent = job.request?.requirements || '暂无数据要求';
}

function renderProgressReview(job) {
  const request = job.request || {};
  $('#progressReviewCaseCount').textContent = request.caseCount ? `${request.caseCount} 组` : '—';
  $('#progressReviewCppStandard').textContent = request.cppStandard || '—';
  $('#progressReviewStatement').textContent = request.statement || '暂无题面';
  $('#progressReviewCode').textContent = request.standardCode || '暂无 AC / 标准程序';
  $('#progressReviewRequirements').textContent = request.requirements || '暂无数据要求';
  $('#progressReviewPlanSummary').textContent = job.plan?.summary || '数据规划还在生成中，稍后会显示。';
  renderGroups($('#progressReviewPlanGroups'), job.plan?.groups);
}

async function api(url, options = {}, config = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && !config.silentAuth) showAuth('login', '请先登录或注册后继续操作。');
    throw new Error(body.error || (response.status === 401 ? '请先登录或注册' : `请求失败 (${response.status})`));
  }
  return body;
}

async function copyTargetText(targetId) {
  const target = document.getElementById(targetId);
  const text = target?.innerText || target?.textContent || '';
  if (!text.trim()) {
    UI.toast('暂无可复制内容');
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
  } catch (_) {
    const fallback = document.createElement('textarea');
    fallback.value = text;
    fallback.style.position = 'fixed';
    fallback.style.left = '-9999px';
    document.body.appendChild(fallback);
    fallback.select();
    document.execCommand('copy');
    fallback.remove();
  }
  UI.toast('已复制到剪贴板', {variant: 'success'});
}

boot();
