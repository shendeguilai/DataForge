const $a = s => document.querySelector(s);
let adminSettings = {dailyGenerationLimit: 30};
let adminJobs = [];
let adminArticles = [];

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
    await Promise.all([loadUsers(), loadJobs(), loadArticles()]);
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
  $a('#closeJobDetail').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  $a('#closeJobDetailX').onclick = () => $a('#jobDetailModal').classList.add('hidden');
  document.querySelectorAll('[data-copy-target]').forEach(b => b.onclick = () => copyTargetText(b.dataset.copyTarget));
  $a('#adminLogout').onclick = async () => { await fetch('/api/auth/logout', {method:'POST'}); location.href = '/'; };
}

function switchPanel(name) {
  document.querySelectorAll('[data-panel]').forEach(b => b.classList.toggle('active', b.dataset.panel === name));
  ['users','ai','articles','jobs'].forEach(n => $a(`#${n}Panel`).classList.toggle('hidden', n !== name));
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
