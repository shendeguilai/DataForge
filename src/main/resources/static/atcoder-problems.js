const problem$ = selector => document.querySelector(selector);
let problemOverview = null;
let selectedTaskId = '';
let selectedDetail = null;
let statementLanguage = 'zh';
let problemPollTimer = null;

problem$('#showChinese').onclick = () => setStatementLanguage('zh');
problem$('#showEnglish').onclick = () => setStatementLanguage('en');
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) loadProblemOverview(true);
});
window.addEventListener('popstate', () => loadProblemOverview(true));

loadProblemOverview(true);

async function problemRequest(url) {
  const response = await fetch(url);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `题面读取失败 (${response.status})`);
  return body;
}

async function loadProblemOverview(loadTask) {
  clearTimeout(problemPollTimer);
  try {
    const data = await problemRequest('/api/tools/atcoder-problems');
    const previousStatus = problemOverview?.tasks?.find(task => task.id === selectedTaskId)?.status;
    problemOverview = data;
    renderOverview();
    if (!data.configured || !data.tasks?.length) {
      showStatementState(data.configured ? '当前比赛还没有可阅读的题目。' : '排行榜尚未配置比赛。');
      return;
    }

    const requested = new URLSearchParams(location.search).get('task');
    const preferred = data.tasks.find(task => task.id === (loadTask ? requested : selectedTaskId))
      || data.tasks.find(task => task.status === 'READY')
      || data.tasks[0];
    const status = data.tasks.find(task => task.id === preferred.id)?.status;
    const statusChanged = previousStatus && previousStatus !== status;
    if (loadTask || selectedTaskId !== preferred.id || statusChanged) await selectTask(preferred.id, false);
    else renderTaskTabs();
    if (data.running) problemPollTimer = setTimeout(() => loadProblemOverview(false), 3000);
  } catch (error) {
    showStatementState(error.message || '题面暂时无法读取，请稍后重试。');
  }
}

function renderOverview() {
  const contest = problemOverview?.contest;
  problem$('#problemContestTitle').textContent = contest?.title || 'AtCoder 翻译题面';
  problem$('#problemContestMeta').textContent = contest
    ? `${String(contest.id || '').toUpperCase()} · 英文原题 AI 翻译版`
    : '管理员尚未配置当前比赛。';
  problem$('#officialContestLink').href = contest?.url || '#';
  problem$('#officialContestLink').classList.toggle('hidden', !contest?.url);
  problem$('#taskProgress').textContent = `${problemOverview?.readyCount || 0} / ${problemOverview?.totalCount || 0} 已翻译`;
  renderTaskTabs();
}

function renderTaskTabs() {
  const tabs = problem$('#taskTabs');
  tabs.innerHTML = (problemOverview?.tasks || []).map(task => `
    <button type="button" class="task-tab ${String(task.status || '').toLowerCase()} ${task.id === selectedTaskId ? 'active' : ''}"
      data-task-id="${escapeHtml(task.id)}" title="${escapeHtml(task.name)} · ${statusText(task.status)}" aria-label="${escapeHtml(task.label)} ${escapeHtml(task.name)}，${statusText(task.status)}">
      ${escapeHtml(task.label || '?')}
    </button>`).join('');
  tabs.querySelectorAll('[data-task-id]').forEach(button => {
    button.onclick = () => selectTask(button.dataset.taskId, true);
  });
}

async function selectTask(taskId, pushHistory) {
  selectedTaskId = taskId;
  selectedDetail = null;
  renderTaskTabs();
  const task = problemOverview.tasks.find(item => item.id === taskId);
  renderTaskHeader(task);
  showStatementState(statusMessage(task?.status));
  if (pushHistory) history.pushState(null, '', `?task=${encodeURIComponent(taskId)}`);
  else history.replaceState(null, '', `?task=${encodeURIComponent(taskId)}`);
  try {
    selectedDetail = await problemRequest(`/api/tools/atcoder-problems/${encodeURIComponent(taskId)}`);
    if (selectedTaskId !== taskId) return;
    renderTaskHeader(selectedDetail.task);
    renderStatement();
  } catch (error) {
    if (selectedTaskId === taskId) showStatementState(error.message || '这道题暂时无法读取。');
  }
}

function renderTaskHeader(task) {
  const contest = problemOverview?.contest;
  problem$('#taskLetter').textContent = task?.label || '?';
  problem$('#taskContestId').textContent = String(contest?.id || 'ATCODER').toUpperCase();
  problem$('#taskTitle').textContent = task?.name || task?.id || '选择一道题目';
  problem$('#officialTaskLink').href = task?.officialUrl || '#';
  problem$('#officialTaskLink').classList.toggle('hidden', !task?.officialUrl);
}

function setStatementLanguage(language) {
  statementLanguage = language;
  problem$('#showChinese').classList.toggle('active', language === 'zh');
  problem$('#showEnglish').classList.toggle('active', language === 'en');
  renderStatement();
}

function renderStatement() {
  if (!selectedDetail) return;
  const task = selectedDetail.task || {};
  const html = statementLanguage === 'zh' ? selectedDetail.translatedHtml : selectedDetail.sourceHtml;
  problem$('#showEnglish').disabled = !selectedDetail.sourceHtml;
  problem$('#translatedTime').textContent = selectedDetail.translatedAt
    ? `更新于 ${formatDate(selectedDetail.translatedAt)}` : '';
  if (!html) {
    showStatementState(statementLanguage === 'zh' ? statusMessage(task.status) : '英文原题尚未获取。');
    return;
  }
  const content = problem$('#statementContent');
  content.innerHTML = html;
  content.classList.remove('hidden');
  problem$('#statementState').classList.add('hidden');
  enhanceCodeBlocks(content);
  renderMath(content);
}

function enhanceCodeBlocks(content) {
  content.querySelectorAll('pre').forEach(pre => {
    const wrapper = document.createElement('div');
    wrapper.className = 'code-sample';
    pre.parentNode.insertBefore(wrapper, pre);
    wrapper.appendChild(pre);
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-sample';
    button.textContent = copyLabel(pre);
    button.onclick = async () => {
      try {
        await copyText(pre.textContent.replace(/\n$/, ''));
        const original = button.textContent;
        button.textContent = '已复制 ✓';
        setTimeout(() => button.textContent = original, 1400);
      } catch (_) {
        showProblemToast('复制失败，请手动选择文本');
      }
    };
    wrapper.appendChild(button);
  });
}

function copyLabel(pre) {
  const heading = pre.closest('section')?.querySelector('h2,h3,h4')?.textContent || '';
  if (/input|输入/i.test(heading)) return '复制输入';
  if (/output|输出/i.test(heading)) return '复制输出';
  return '复制';
}

async function copyText(value) {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(value);
  const textarea = document.createElement('textarea');
  textarea.value = value;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('copy failed');
}

function renderMath(content) {
  if (typeof katex === 'object') {
    content.querySelectorAll('var').forEach(variable => {
      try { katex.render(variable.textContent, variable, {throwOnError: false, strict: false}); } catch (_) {}
    });
  }
  if (typeof renderMathInElement === 'function') {
    renderMathInElement(content, {
      throwOnError: false,
      delimiters: [
        {left: '$$', right: '$$', display: true},
        {left: '\\[', right: '\\]', display: true},
        {left: '\\(', right: '\\)', display: false}
      ]
    });
  }
}

function showStatementState(message) {
  problem$('#statementState').textContent = message;
  problem$('#statementState').classList.remove('hidden');
  problem$('#statementContent').classList.add('hidden');
  problem$('#statementContent').innerHTML = '';
}

function statusMessage(status) {
  return ({
    NOT_STARTED: '管理员尚未开始翻译这道题。',
    QUEUED: '这道题已经进入翻译队列，请稍候。',
    FETCHING: '正在从 AtCoder 获取英文题面…',
    TRANSLATING: 'AI 正在翻译这道题…',
    READY: '正在载入译文…',
    FAILED: '这道题翻译失败，管理员可以在后台重试。'
  })[status] || '这道题暂时无法阅读。';
}

function statusText(status) {
  return ({NOT_STARTED:'待翻译',QUEUED:'排队中',FETCHING:'获取中',TRANSLATING:'翻译中',READY:'已完成',FAILED:'失败'})[status] || '待翻译';
}

function formatDate(value) {
  return new Date(value).toLocaleString('zh-CN', {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
}

function escapeHtml(value) {
  return String(value ?? '').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&#039;');
}

function showProblemToast(message) {
  const toast = problem$('#problemToast');
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2200);
}
