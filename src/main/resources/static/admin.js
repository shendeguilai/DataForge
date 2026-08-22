const $a = s => document.querySelector(s);
let adminSettings = {dailyGenerationLimit: 30};
let currentAdmin = null;
let adminUsers = [];
let adminJobs = [];
let adminArticles = [];
let leaderboardParticipants = [];
let leaderboardTranslations = null;
let translationAdminTimer = null;
let translationEditorTaskId = '';
let translationEditorInitialHtml = '';
const manualTranslationTemplate = `【题目描述】
请在这里粘贴完整的中文题目描述。

【输入格式】
请在这里粘贴输入格式。

【输出格式】
请在这里粘贴输出格式。

【样例输入 1】
1 2

【样例输出 1】
3

【说明】
可选；如果没有说明，请删除本段。`;

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
    currentAdmin = me;
    $a('#adminName').textContent = me.username;
    bind();
    await loadConfig();
    await Promise.all([loadUsers(), loadJobs(), loadArticles(), loadLeaderboardAdmin()]);
  } catch (e) {
    location.href = '/';
  }
}

function bind() {
  document.querySelectorAll('[data-panel]').forEach(b => b.onclick = () => switchPanel(b.dataset.panel));
  $a('#aiForm').onsubmit = saveConfig;
  $a('#refreshAdminJobs').onclick = loadJobs;
  $a('#articleForm').onsubmit = saveArticle;
  $a('#newArticleButton').onclick = () => resetArticleForm(true);
  $a('#cancelArticleEdit').onclick = () => resetArticleForm();
  $a('#articleCategoryFilter').onchange = renderArticles;
  $a('#articleContentInput').oninput = updateArticleContentCount;
  $a('#contestConfigForm').onsubmit = saveLeaderboardConfig;
  $a('#saveAtcoderCookie').onclick = saveAtcoderCookie;
  $a('#clearAtcoderCookie').onclick = clearAtcoderCookie;
  $a('#leaderboardParticipantForm').onsubmit = saveLeaderboardParticipant;
  $a('#cancelLeaderboardParticipant').onclick = resetLeaderboardParticipantForm;
  $a('#fillParticipantBatchExample').onclick = fillParticipantBatchExample;
  $a('#saveParticipantBatch').onclick = saveParticipantBatch;
  $a('#translateAllProblems').onclick = () => startProblemTranslations(false);
  $a('#retranslateAllProblems').onclick = () => startProblemTranslations(true);
  $a('#closeTranslationEditor').onclick = closeTranslationEditor;
  $a('#closeTranslationEditorX').onclick = closeTranslationEditor;
  $a('#resetTranslationEditor').onclick = () => {
    $a('#translationEditable').innerHTML = translationEditorInitialHtml;
  };
  $a('#saveTranslationEditor').onclick = saveTranslationEditor;
  $a('#copyManualTranslationTemplate').onclick = fillManualTranslationTemplate;
  $a('#importManualTranslation').onclick = importManualTranslation;
  $a('#retranslateEditorTask').onclick = () => retryProblemTranslation(translationEditorTaskId, true);
  $a('#closeJobDetail').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  $a('#closeJobDetailX').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  $a('#adminChangePassword').onclick = showAdminPassword;
  $a('#closeAdminPassword').onclick = hideAdminPassword;
  $a('#adminPasswordForm').onsubmit = changeAdminPassword;
  $a('#closeResetPassword').onclick = hideResetPassword;
  $a('#resetPasswordForm').onsubmit = resetUserPassword;
  document.querySelectorAll('[data-copy-target]').forEach(b => b.onclick = () => copyTargetText(b.dataset.copyTarget));
  $a('#adminLogout').onclick = async () => { await fetch('/api/auth/logout', {method:'POST'}); location.href = '/'; };
}

function switchPanel(name) {
  document.querySelectorAll('[data-panel]').forEach(b => b.classList.toggle('active', b.dataset.panel === name));
  ['users','ai','articles','leaderboard','jobs'].forEach(n => $a(`#${n}Panel`).classList.toggle('hidden', n !== name));
}

async function loadUsers() {
  const users = await request('/api/admin/users');
  adminUsers = users;
  const defaultLimit = adminSettings.dailyGenerationLimit || 30;
  $a('#userTotal').textContent = users.length;
  $a('#usersBody').innerHTML = users.map(u => {
    const limit = u.dailyGenerationLimit || defaultLimit;
    const toggle = u.role === 'ADMIN' ? '—' : `<button class="mini-btn" data-user-id="${u.id}" data-enabled="${!u.enabled}">${u.enabled ? '禁用' : '启用'}</button>`;
    const reset = u.username === currentAdmin.username ? '' : `<button class="mini-btn" data-password-reset="${u.id}">重置密码</button>`;
    return `<tr><td>${u.id}</td><td><strong>${esc(u.username)}</strong></td><td>${u.role}</td><td>${date(u.createdAt)}</td><td><span class="badge ${u.enabled ? '' : 'off'}">${u.enabled ? '正常' : '已禁用'}</span></td><td><input class="quota-input" data-quota-id="${u.id}" type="number" min="1" max="10000" value="${limit}"></td><td>${toggle}<button class="mini-btn" data-quota-save="${u.id}">保存额度</button>${reset}</td></tr>`;
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
  document.querySelectorAll('[data-password-reset]').forEach(b => {
    b.onclick = () => showResetPassword(Number(b.dataset.passwordReset));
  });
}

function showAdminPassword() {
  $a('#adminPasswordForm').reset();
  $a('#adminPasswordError').classList.add('hidden');
  $a('#adminPasswordModal').classList.remove('hidden');
  setTimeout(() => $a('#adminCurrentPassword').focus(), 0);
}

function hideAdminPassword() {
  $a('#adminPasswordModal').classList.add('hidden');
  $a('#adminPasswordError').classList.add('hidden');
}

async function changeAdminPassword(event) {
  event.preventDefault();
  const errorBox = $a('#adminPasswordError');
  const newPassword = $a('#adminNewPassword').value;
  if (newPassword !== $a('#adminConfirmPassword').value) {
    errorBox.textContent = '两次输入的新密码不一致';
    errorBox.classList.remove('hidden');
    return;
  }
  const button = $a('#adminPasswordForm button[type="submit"]');
  button.disabled = true;
  errorBox.classList.add('hidden');
  try {
    await request('/api/auth/password', {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({currentPassword: $a('#adminCurrentPassword').value, newPassword})
    });
    hideAdminPassword();
    toast('密码修改成功');
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    button.disabled = false;
  }
}

function showResetPassword(id) {
  const user = adminUsers.find(item => item.id === id);
  if (!user) { toast('用户不存在或列表已刷新'); return; }
  $a('#resetPasswordForm').reset();
  $a('#resetPasswordUserId').value = user.id;
  $a('#resetPasswordUsername').textContent = user.username;
  $a('#resetPasswordError').classList.add('hidden');
  $a('#resetPasswordModal').classList.remove('hidden');
  setTimeout(() => $a('#resetNewPassword').focus(), 0);
}

function hideResetPassword() {
  $a('#resetPasswordModal').classList.add('hidden');
  $a('#resetPasswordError').classList.add('hidden');
}

async function resetUserPassword(event) {
  event.preventDefault();
  const errorBox = $a('#resetPasswordError');
  const newPassword = $a('#resetNewPassword').value;
  if (newPassword !== $a('#resetConfirmPassword').value) {
    errorBox.textContent = '两次输入的新密码不一致';
    errorBox.classList.remove('hidden');
    return;
  }
  const button = $a('#resetPasswordForm button[type="submit"]');
  button.disabled = true;
  errorBox.classList.add('hidden');
  try {
    await request(`/api/admin/users/${$a('#resetPasswordUserId').value}/password`, {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({newPassword})
    });
    hideResetPassword();
    toast('用户密码已重置');
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    button.disabled = false;
  }
}

async function loadConfig() {
  const c = await request('/api/admin/ai-config');
  adminSettings = c;
  $a('#aiBaseUrl').value = c.baseUrl || '';
  $a('#aiModel').value = c.model || '';
  $a('#dailyGenerationLimit').value = c.dailyGenerationLimit || 30;
  $a('#keyState').textContent = c.apiKeyConfigured ? 'API Key 已配置' : '尚未配置 API Key';
  renderTranslationAiState();
  if (leaderboardTranslations) renderLeaderboardTranslations(leaderboardTranslations);
}

function renderTranslationAiState() {
  const configured = Boolean(adminSettings?.apiKeyConfigured && adminSettings?.baseUrl && adminSettings?.model);
  const deepseek = String(adminSettings?.baseUrl || '').toLowerCase().includes('deepseek.com')
    || String(adminSettings?.model || '').toLowerCase().startsWith('deepseek-');
  const state = $a('#translationAiState');
  state.classList.toggle('ready', configured);
  state.textContent = configured
    ? `AI 接口已就绪 · ${adminSettings.model} · ${deepseek ? '深度思考 high' : '标准模式'}`
    : 'AI 接口未配置 · 请先在“AI 接口”标签填写 Base URL、模型和 API Key';
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

async function loadArticles() {
  adminArticles = await request('/api/admin/typing-articles');
  $a('#articleTotal').textContent = adminArticles.length;
  const counts = ['中文', '英文', '代码'].map(category =>
    `${category} ${adminArticles.filter(article => article.category === category).length} 篇`
  );
  $a('#articleLibrarySummary').textContent = counts.join(' · ');
  renderArticles();
}

function renderArticles() {
  const category = $a('#articleCategoryFilter').value;
  const articles = category === '全部' ? adminArticles : adminArticles.filter(article => article.category === category);
  if (!articles.length) {
    $a('#articleList').innerHTML = '<div class="article-list-empty">当前分类还没有文章</div>';
    return;
  }
  $a('#articleList').innerHTML = articles.map(article => `
    <article class="article-row">
      <div class="article-row-heading">
        <div>
          <h4 title="${esc(article.title)}">${esc(article.title)}</h4>
          <div class="article-row-meta"><span class="article-category ${esc(article.category)}">${esc(article.category)}</span><span>${article.length} 字符</span></div>
        </div>
        <div class="article-row-actions">
          <button class="mini-btn" type="button" data-edit-article="${esc(article.id)}">编辑</button>
          <button class="mini-btn delete-article" type="button" data-delete-article="${esc(article.id)}">删除</button>
        </div>
      </div>
      <p class="article-preview">${esc(article.content)}</p>
    </article>
  `).join('');
  document.querySelectorAll('[data-edit-article]').forEach(button => {
    button.onclick = () => editArticle(button.dataset.editArticle);
  });
  document.querySelectorAll('[data-delete-article]').forEach(button => {
    button.onclick = () => deleteArticle(button.dataset.deleteArticle);
  });
}

function editArticle(id) {
  const article = adminArticles.find(item => item.id === id);
  if (!article) { toast('文章不存在或列表已刷新'); return; }
  $a('#articleId').value = article.id;
  $a('#articleTitleInput').value = article.title;
  $a('#articleCategoryInput').value = article.category;
  $a('#articleContentInput').value = article.content;
  $a('#articleFormMode').textContent = 'EDIT ARTICLE';
  $a('#articleFormTitle').textContent = '修改文章';
  $a('#saveArticleButton span').textContent = '保存修改';
  $a('#cancelArticleEdit').classList.remove('hidden');
  updateArticleContentCount();
  $a('#articleTitleInput').focus();
  $a('#articleForm').scrollIntoView({behavior: 'smooth', block: 'start'});
}

function resetArticleForm(focus = false) {
  $a('#articleForm').reset();
  $a('#articleId').value = '';
  $a('#articleCategoryInput').value = '中文';
  $a('#articleFormMode').textContent = 'NEW ARTICLE';
  $a('#articleFormTitle').textContent = '新增文章';
  $a('#saveArticleButton span').textContent = '保存文章';
  $a('#cancelArticleEdit').classList.add('hidden');
  updateArticleContentCount();
  if (focus) {
    $a('#articleTitleInput').focus();
    $a('#articleForm').scrollIntoView({behavior: 'smooth', block: 'start'});
  }
}

function updateArticleContentCount() {
  $a('#articleContentCount').textContent = `${$a('#articleContentInput').value.length} / 12000 字符`;
}

async function saveArticle(event) {
  event.preventDefault();
  const id = $a('#articleId').value;
  const button = $a('#saveArticleButton');
  button.disabled = true;
  try {
    await request(id ? `/api/admin/typing-articles/${encodeURIComponent(id)}` : '/api/admin/typing-articles', {
      method: id ? 'PUT' : 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        title: $a('#articleTitleInput').value,
        category: $a('#articleCategoryInput').value,
        content: $a('#articleContentInput').value
      })
    });
    toast(id ? '文章修改已保存' : '文章已添加');
    resetArticleForm();
    await loadArticles();
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
}

async function deleteArticle(id) {
  const article = adminArticles.find(item => item.id === id);
  if (!article || !confirm(`确定删除文章「${article.title}」吗？`)) return;
  try {
    await request(`/api/admin/typing-articles/${encodeURIComponent(id)}`, {method: 'DELETE'});
    if ($a('#articleId').value === id) resetArticleForm();
    toast('文章已删除');
    await loadArticles();
  } catch (error) {
    toast(error.message);
  }
}

async function loadLeaderboardAdmin() {
  const [config, participants, translations] = await Promise.all([
    request('/api/admin/atcoder-leaderboard/config'),
    request('/api/admin/atcoder-leaderboard/participants'),
    request('/api/admin/atcoder-leaderboard/translations')
  ]);
  renderLeaderboardConfig(config);
  leaderboardParticipants = participants;
  renderLeaderboardParticipants();
  renderLeaderboardTranslations(translations);
}

function renderLeaderboardConfig(config) {
  $a('#contestIdInput').value = config.contestId || '';
  $a('#contestTitleInput').value = config.displayTitle || '';
  const cookieLabels = {AVAILABLE: 'Cookie 可用', MISSING: 'Cookie 未配置', INVALID: 'Cookie 已失效'};
  const cookieState = config.cookieStatus || 'MISSING';
  const cookie = $a('#atcoderCookieState');
  cookie.textContent = cookieLabels[cookieState] || 'Cookie 状态未知';
  cookie.className = `cookie-state ${cookieState.toLowerCase()}`;
  const sourceLabels = {MANAGED: '后台加密保存', ENVIRONMENT: '系统环境变量', NONE: '尚未配置'};
  const source = config.cookieSource || 'NONE';
  $a('#atcoderCookieMeta').textContent = config.cookieUpdatedAt
    ? `${sourceLabels[source] || source} · 更新于 ${date(config.cookieUpdatedAt)}`
    : (sourceLabels[source] || source);
  $a('#clearAtcoderCookie').classList.toggle('hidden', source !== 'MANAGED');

  if (!config.configured) {
    $a('#contestConfigSummary').textContent = '尚未配置比赛。请先填写 Contest ID 并验证保存。';
    $a('#contestTaskList').innerHTML = '';
    return;
  }
  const time = config.startAt && config.endAt
    ? `${date(config.startAt)} - ${date(config.endAt)}`
    : '比赛时间未能自动读取';
  $a('#contestConfigSummary').innerHTML = `<strong>${esc(config.officialTitle || config.contestId)}</strong><span>${esc(time)}</span><small>更新于 ${date(config.updatedAt)}</small>`;
  $a('#contestTaskList').innerHTML = (config.tasks || []).map(task => `<span title="${esc(task.name || task.id)}">${esc(task.label || '?')}</span>`).join('');
}

async function loadLeaderboardTranslations() {
  try {
    renderLeaderboardTranslations(await request('/api/admin/atcoder-leaderboard/translations'));
  } catch (error) {
    $a('#translationStatusText').textContent = error.message;
  }
}

function renderLeaderboardTranslations(data) {
  leaderboardTranslations = data;
  clearTimeout(translationAdminTimer);
  const total = data?.totalCount || 0;
  const ready = data?.readyCount || 0;
  const percent = total ? Math.round(ready * 100 / total) : 0;
  $a('#translationProgressText').textContent = total ? `${ready} / ${total}` : '尚未开始';
  $a('#translationProgressBar').style.width = `${percent}%`;
  $a('.translation-progress').setAttribute('aria-valuenow', String(percent));
  const labels = {
    NOT_CONFIGURED: '请先配置并保存当前比赛。',
    NOT_STARTED: '题目列表已准备，可以开始获取并翻译英文题面。',
    RUNNING: `正在后台处理题面，已完成 ${ready} / ${total} 道。`,
    READY: '全部题面翻译完成，公开阅读页已经可以使用。',
    PARTIAL: `已有 ${ready} 道可阅读，失败题目可以单独重试。`,
    FAILED: '本次没有成功翻译题目，请查看各题错误后重试。'
  };
  $a('#translationStatusText').textContent = labels[data?.status] || '等待翻译状态。';
  const start = $a('#translateAllProblems');
  const retranslate = $a('#retranslateAllProblems');
  const aiConfigured = Boolean(adminSettings?.apiKeyConfigured && adminSettings?.baseUrl && adminSettings?.model);
  start.disabled = !data?.configured || data.running || !aiConfigured;
  start.textContent = data?.running ? '正在翻译…' : (ready ? '继续翻译未完成题目' : '一键获取并翻译');
  retranslate.classList.toggle('hidden', !ready);
  retranslate.disabled = Boolean(data?.running) || !aiConfigured;
  $a('#translationTaskList').innerHTML = (data?.tasks || []).map(task => {
    const status = translationStatus(task.status);
    const running = ['QUEUED', 'FETCHING', 'TRANSLATING'].includes(task.status) || data?.running;
    const edit = task.hasSource || task.hasTranslation
      ? `<button type="button" class="mini-btn" data-edit-translation="${esc(task.id)}">查看/编辑</button>` : '';
    const manual = running ? ''
      : `<button type="button" class="mini-btn" data-manual-translation="${esc(task.id)}">手动导入</button>`;
    const retryLabel = task.status === 'READY' ? '重新翻译' : (task.status === 'NOT_STARTED' ? '翻译' : '重试翻译');
    const retry = running ? ''
      : `<button type="button" class="mini-btn" data-retry-translation="${esc(task.id)}">${retryLabel}</button>`;
    const error = translationError(task.error);
    const title = task.hasSource
      ? `<button type="button" class="translation-task-open" data-edit-translation="${esc(task.id)}">${esc(task.name || task.id)}</button>`
      : `<strong>${esc(task.name || task.id)}</strong>`;
    return `<div class="translation-task-row ${String(task.status || '').toLowerCase()}"><b>${esc(task.label || '?')}</b><div>${title}${error ? `<small>${esc(error)}</small>` : ''}</div><span>${status}</span><div class="translation-row-actions">${edit}${manual}${retry}</div></div>`;
  }).join('') || '<div class="translation-task-empty">尚无题目</div>';
  document.querySelectorAll('[data-edit-translation]').forEach(button => {
    button.onclick = () => openTranslationEditor(button.dataset.editTranslation);
  });
  document.querySelectorAll('[data-retry-translation]').forEach(button => {
    button.onclick = () => retryProblemTranslation(button.dataset.retryTranslation, true);
  });
  document.querySelectorAll('[data-manual-translation]').forEach(button => {
    button.onclick = () => openTranslationEditor(button.dataset.manualTranslation, true);
  });
  if (data?.running) translationAdminTimer = setTimeout(loadLeaderboardTranslations, 3000);
}

function translationError(error) {
  if (!error) return '';
  if (error.includes('AI 改变了题面中的公式、代码或样例结构')) {
    return '旧版结构保护校验失败，当前版本已修复，请重试该题';
  }
  return error;
}

function translationStatus(status) {
  return ({NOT_STARTED:'待开始',QUEUED:'排队中',FETCHING:'获取题面',TRANSLATING:'AI 翻译中',READY:'已完成',FAILED:'失败'})[status] || status || '待开始';
}

async function startProblemTranslations(force) {
  if (force && !confirm('确定重新获取并翻译全部题目吗？这会覆盖当前译文并再次调用 AI。')) return;
  const button = force ? $a('#retranslateAllProblems') : $a('#translateAllProblems');
  button.disabled = true;
  try {
    const path = force ? '/api/admin/atcoder-leaderboard/translations/retranslate' : '/api/admin/atcoder-leaderboard/translations';
    renderLeaderboardTranslations(await request(path, {method: 'POST'}));
    toast(force ? '已开始重新翻译全部题目' : '赛题翻译任务已启动');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
}

async function retryProblemTranslation(taskId, askConfirmation) {
  if (!taskId) return;
  const task = leaderboardTranslations?.tasks?.find(item => item.id === taskId);
  if (askConfirmation && task?.status === 'READY'
      && !confirm(`确定重新翻译 ${task.label || task.name} 吗？成功后会覆盖当前正式译文。`)) return;
  try {
    renderLeaderboardTranslations(await request(`/api/admin/atcoder-leaderboard/translations/${encodeURIComponent(taskId)}`, {method: 'POST'}));
    closeTranslationEditor();
    toast('该题已重新进入翻译队列');
  } catch (error) {
    toast(error.message);
  }
}

async function openTranslationEditor(taskId, showManualImport = false) {
  try {
    const detail = await request(`/api/admin/atcoder-leaderboard/translations/${encodeURIComponent(taskId)}`);
    translationEditorTaskId = taskId;
    const task = detail.task || {};
    $a('#translationEditorLabel').textContent = task.label || '?';
    $a('#translationEditorTitle').textContent = task.name || task.id || '题面译文';
    $a('#translationEditorStatus').textContent = translationStatus(task.status);
    $a('#translationEditorTaskError').textContent = translationError(task.error);
    $a('#translationEditorTaskError').classList.toggle('hidden', !task.error);
    $a('#translationSourcePreview').innerHTML = detail.sourceHtml || '<p>英文题面尚未获取。</p>';
    const hasDraft = Boolean(detail.draftHtml);
    $a('#translationDraftPanel').classList.toggle('empty', !hasDraft);
    $a('#translationDraftPreview').innerHTML = hasDraft
      ? detail.draftHtml
      : '<p>没有可用的 AI 草稿。旧版本产生的失败结果未被保存，需要重新翻译后才能查看。</p>';
    translationEditorInitialHtml = detail.editorHtml || '';
    $a('#translationEditable').innerHTML = translationEditorInitialHtml;
    const editable = !leaderboardTranslations?.running;
    $a('#translationEditable').contentEditable = String(editable);
    $a('#saveTranslationEditor').disabled = !editable;
    $a('#retranslateEditorTask').disabled = Boolean(leaderboardTranslations?.running);
    $a('#translationEditorMeta').textContent = detail.translatedAt
      ? `正式译文更新于 ${date(detail.translatedAt)}`
      : (hasDraft ? '当前显示 AI 草稿，可修改后保存为正式译文' : (detail.sourceHtml ? '可以基于英文原题人工编辑' : '英文题面未获取，可在上方手动导入中文题面'));
    $a('#manualTranslationText').value = '';
    $a('#translationManualImport').open = showManualImport || !detail.sourceHtml;
    $a('#translationEditorError').classList.add('hidden');
    $a('#translationEditorModal').classList.remove('hidden');
  } catch (error) {
    toast(error.message);
  }
}

function fillManualTranslationTemplate() {
  const input = $a('#manualTranslationText');
  if (input.value.trim() && !confirm('确定用标准模板覆盖当前手动导入内容吗？')) return;
  input.value = manualTranslationTemplate;
  input.focus();
  input.setSelectionRange(0, 0);
}

async function importManualTranslation() {
  if (!translationEditorTaskId) return;
  const input = $a('#manualTranslationText');
  const button = $a('#importManualTranslation');
  const errorBox = $a('#translationEditorError');
  if (!input.value.trim()) {
    errorBox.textContent = '请先粘贴手动题面';
    errorBox.classList.remove('hidden');
    return;
  }
  button.disabled = true;
  errorBox.classList.add('hidden');
  try {
    const detail = await request(`/api/admin/atcoder-leaderboard/translations/${encodeURIComponent(translationEditorTaskId)}/manual`, {
      method: 'PUT',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({content: input.value})
    });
    translationEditorInitialHtml = detail.editorHtml || detail.translatedHtml || '';
    $a('#translationEditable').innerHTML = translationEditorInitialHtml;
    $a('#translationEditorStatus').textContent = translationStatus(detail.task?.status);
    $a('#translationEditorMeta').textContent = `手动题面发布于 ${date(detail.translatedAt)}`;
    input.value = '';
    await loadLeaderboardTranslations();
    toast('手动题面已导入并发布');
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    button.disabled = false;
  }
}

function closeTranslationEditor() {
  $a('#translationEditorModal').classList.add('hidden');
  translationEditorTaskId = '';
  translationEditorInitialHtml = '';
}

async function saveTranslationEditor() {
  if (!translationEditorTaskId) return;
  const button = $a('#saveTranslationEditor');
  const errorBox = $a('#translationEditorError');
  button.disabled = true;
  errorBox.classList.add('hidden');
  try {
    const detail = await request(`/api/admin/atcoder-leaderboard/translations/${encodeURIComponent(translationEditorTaskId)}`, {
      method: 'PUT',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({translatedHtml: $a('#translationEditable').innerHTML})
    });
    translationEditorInitialHtml = detail.editorHtml || detail.translatedHtml || '';
    $a('#translationEditable').innerHTML = translationEditorInitialHtml;
    $a('#translationEditorStatus').textContent = translationStatus(detail.task?.status);
    $a('#translationEditorMeta').textContent = `正式译文更新于 ${date(detail.translatedAt)}`;
    await loadLeaderboardTranslations();
    toast('人工译文已保存并发布');
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    button.disabled = false;
  }
}

async function saveAtcoderCookie() {
  const input = $a('#atcoderCookieInput');
  const button = $a('#saveAtcoderCookie');
  if (!input.value.trim()) { toast('请先填写 AtCoder Cookie'); return; }
  button.disabled = true;
  button.textContent = '正在验证…';
  try {
    const config = await request('/api/admin/atcoder-leaderboard/cookie', {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({cookie: input.value})
    });
    input.value = '';
    renderLeaderboardConfig(config);
    toast('Cookie 验证成功并已加密保存');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
    button.textContent = '验证并保存 Cookie';
  }
}

async function clearAtcoderCookie() {
  if (!confirm('确定清除后台保存的 AtCoder Cookie 吗？清除后将改用系统环境变量。')) return;
  const button = $a('#clearAtcoderCookie');
  button.disabled = true;
  try {
    const config = await request('/api/admin/atcoder-leaderboard/cookie', {method: 'DELETE'});
    $a('#atcoderCookieInput').value = '';
    renderLeaderboardConfig(config);
    toast(config.cookieSource === 'ENVIRONMENT' ? '已切换为系统环境变量 Cookie' : '后台 Cookie 已清除');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
}

async function saveLeaderboardConfig(event) {
  event.preventDefault();
  const button = $a('#saveContestButton');
  button.disabled = true;
  button.querySelector('span').textContent = '正在验证';
  try {
    const config = await request('/api/admin/atcoder-leaderboard/config', {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        contestId: $a('#contestIdInput').value,
        displayTitle: $a('#contestTitleInput').value
      })
    });
    renderLeaderboardConfig(config);
    await loadLeaderboardTranslations();
    toast('比赛已验证并切换，公开榜单可以开始刷新');
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = '验证并保存';
  }
}

function renderLeaderboardParticipants() {
  $a('#leaderboardParticipantCount').textContent = `${leaderboardParticipants.length} 人`;
  if (!leaderboardParticipants.length) {
    $a('#leaderboardParticipantList').innerHTML = '<div class="participant-empty">还没有录入选手</div>';
    return;
  }
  $a('#leaderboardParticipantList').innerHTML = leaderboardParticipants.map((participant, index) => `
    <div class="participant-row">
      <span class="participant-order">${String(index + 1).padStart(2, '0')}</span>
      <div><strong>${esc(participant.displayName)}</strong><a href="https://atcoder.jp/users/${encodeURIComponent(participant.atcoderUsername)}" target="_blank" rel="noreferrer">@${esc(participant.atcoderUsername)}</a></div>
      <button class="mini-btn" type="button" data-edit-leaderboard-participant="${participant.id}">编辑</button>
      <button class="mini-btn participant-delete" type="button" data-delete-leaderboard-participant="${participant.id}">删除</button>
    </div>
  `).join('');
  document.querySelectorAll('[data-edit-leaderboard-participant]').forEach(button => {
    button.onclick = () => editLeaderboardParticipant(Number(button.dataset.editLeaderboardParticipant));
  });
  document.querySelectorAll('[data-delete-leaderboard-participant]').forEach(button => {
    button.onclick = () => deleteLeaderboardParticipant(Number(button.dataset.deleteLeaderboardParticipant));
  });
}

function editLeaderboardParticipant(id) {
  const participant = leaderboardParticipants.find(item => item.id === id);
  if (!participant) return;
  $a('#leaderboardParticipantId').value = participant.id;
  $a('#leaderboardDisplayName').value = participant.displayName;
  $a('#leaderboardUsername').value = participant.atcoderUsername;
  $a('#saveLeaderboardParticipant').textContent = '保存修改';
  $a('#cancelLeaderboardParticipant').classList.remove('hidden');
  $a('#leaderboardDisplayName').focus();
}

function resetLeaderboardParticipantForm() {
  $a('#leaderboardParticipantForm').reset();
  $a('#leaderboardParticipantId').value = '';
  $a('#saveLeaderboardParticipant').textContent = '添加选手';
  $a('#cancelLeaderboardParticipant').classList.add('hidden');
}

async function saveLeaderboardParticipant(event) {
  event.preventDefault();
  const id = $a('#leaderboardParticipantId').value;
  const button = $a('#saveLeaderboardParticipant');
  button.disabled = true;
  try {
    await request(id ? `/api/admin/atcoder-leaderboard/participants/${id}` : '/api/admin/atcoder-leaderboard/participants', {
      method: id ? 'PUT' : 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        displayName: $a('#leaderboardDisplayName').value,
        atcoderUsername: $a('#leaderboardUsername').value
      })
    });
    toast(id ? '选手信息已更新' : '选手已加入排行榜');
    resetLeaderboardParticipantForm();
    leaderboardParticipants = await request('/api/admin/atcoder-leaderboard/participants');
    renderLeaderboardParticipants();
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
  }
}

function fillParticipantBatchExample() {
  $a('#leaderboardParticipantBatch').value = JSON.stringify([
    {realName: '张三', atcoderId: 'zhangsan'},
    {realName: '李四', atcoderId: 'lisi_04'}
  ], null, 2);
}

async function saveParticipantBatch() {
  const input = $a('#leaderboardParticipantBatch');
  const button = $a('#saveParticipantBatch');
  let rows;
  try {
    rows = JSON.parse(input.value.trim());
  } catch (_) {
    toast('JSON 格式不正确，请检查括号、逗号和引号');
    return;
  }
  if (!Array.isArray(rows)) { toast('JSON 最外层必须是数组'); return; }
  if (!rows.length) { toast('请至少填写一名选手'); return; }

  button.disabled = true;
  button.textContent = '正在添加';
  try {
    const created = await request('/api/admin/atcoder-leaderboard/participants/batch', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(rows)
    });
    input.value = '';
    leaderboardParticipants = await request('/api/admin/atcoder-leaderboard/participants');
    renderLeaderboardParticipants();
    toast(`已批量添加 ${created.length} 名选手`);
  } catch (error) {
    toast(error.message);
  } finally {
    button.disabled = false;
    button.textContent = '批量添加';
  }
}

async function deleteLeaderboardParticipant(id) {
  const participant = leaderboardParticipants.find(item => item.id === id);
  if (!participant || !confirm(`确定从排行榜移除「${participant.displayName}」吗？`)) return;
  try {
    await request(`/api/admin/atcoder-leaderboard/participants/${id}`, {method: 'DELETE'});
    if (String($a('#leaderboardParticipantId').value) === String(id)) resetLeaderboardParticipantForm();
    leaderboardParticipants = await request('/api/admin/atcoder-leaderboard/participants');
    renderLeaderboardParticipants();
    toast('选手已移除');
  } catch (error) {
    toast(error.message);
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
