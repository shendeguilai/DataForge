const board$ = selector => document.querySelector(selector);
const refreshButton = board$('#refreshButton');
let refreshInFlight = false;
let leaderboardSequence = 0;
let leaderboardAbortController = null;
let countdown = 60;
let countdownTimer = null;
let currentPage = 1;
let currentTasks = [];
let currentEntries = [];
let currentContestId = '';
let canManualRefresh = false;
let problemPollTimer = null;
let problemSequence = 0;
let problemAbortController = null;
const PAGE_SIZE = 50;

refreshButton?.addEventListener('click', () => {
  if (canManualRefresh) loadLeaderboard(true);
});
board$('#problemButton')?.addEventListener('click', event => {
  if (event.currentTarget.classList.contains('disabled')) event.preventDefault();
});
window.addEventListener('dataforge:auth-changed', event => setManualRefreshAccess(event.detail));
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    loadLeaderboard(false);
    loadProblemOverview();
  }
});

loadManualRefreshAccess();
startClock();
loadProblemOverview();
loadLeaderboard(false);

async function loadManualRefreshAccess() {
  try {
    const response = await fetch('/api/auth/me');
    setManualRefreshAccess(response.ok ? await response.json() : null);
  } catch (_) {
    setManualRefreshAccess(null);
  }
}

function setManualRefreshAccess(user) {
  canManualRefresh = user?.role === 'ADMIN';
  refreshButton?.classList.toggle('hidden', !canManualRefresh);
}

async function loadLeaderboard(manual) {
  if (document.hidden || (manual && !canManualRefresh)) return;
  const sequence = ++leaderboardSequence;
  leaderboardAbortController?.abort();
  leaderboardAbortController = typeof AbortController === 'function' ? new AbortController() : null;
  refreshInFlight = true;
  if (window.DataForgeUI?.setBusy) window.DataForgeUI.setBusy(refreshButton, true, '同步中');
  else refreshButton.disabled = true;
  try {
    const response = await fetch(manual ? '/api/tools/atcoder-leaderboard/refresh' : '/api/tools/atcoder-leaderboard', {
      method: manual ? 'POST' : 'GET',
      signal: leaderboardAbortController?.signal
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `排行榜读取失败 (${response.status})`);
    if (sequence !== leaderboardSequence) return;
    renderLeaderboard(payload);
    loadProblemOverview();
    countdown = payload.refreshCooldownSeconds > 0 ? payload.refreshCooldownSeconds : (payload.refreshAfterSeconds || 60);
    if (manual && payload.refreshCooldownSeconds > 0) showToast(`刷新过于频繁，请等待 ${payload.refreshCooldownSeconds} 秒`);
  } catch (error) {
    if (sequence !== leaderboardSequence || error.name === 'AbortError') return;
    showNotice(error.message || '排行榜暂时无法读取，当前仍保留上次成功同步的数据。', true);
    if (!currentEntries.length) showBoardState('排行榜读取失败，请稍后重试。');
  } finally {
    if (sequence !== leaderboardSequence) return;
    refreshInFlight = false;
    if (window.DataForgeUI?.setBusy) window.DataForgeUI.setBusy(refreshButton, false);
    else refreshButton.disabled = false;
  }
}

async function loadProblemOverview() {
  clearTimeout(problemPollTimer);
  const sequence = ++problemSequence;
  problemAbortController?.abort();
  problemAbortController = typeof AbortController === 'function' ? new AbortController() : null;
  try {
    const response = await fetch('/api/tools/atcoder-problems', {signal: problemAbortController?.signal});
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error();
    if (sequence !== problemSequence) return;
    renderProblemButton(data);
    if (data.running) problemPollTimer = setTimeout(loadProblemOverview, 3000);
  } catch (error) {
    if (sequence !== problemSequence || error.name === 'AbortError') return;
    if (!board$('#problemButton')?.classList.contains('running')) showNotice('题面状态暂时无法读取。', true);
  }
}

function renderProblemButton(data) {
  const button = board$('#problemButton');
  if (!button) return;
  const ready = data?.readyCount || 0;
  const total = data?.totalCount || 0;
  const available = ready > 0 || Boolean(data?.running);
  button.classList.toggle('disabled', !available);
  button.classList.toggle('running', Boolean(data?.running));
  button.setAttribute('aria-disabled', String(!available));
  if (data?.running) button.querySelector('b').textContent = `题面翻译中 ${ready}/${total}`;
  else if (ready) button.querySelector('b').textContent = ready === total ? '查看翻译题面' : `查看翻译题面 ${ready}/${total}`;
  else if (data?.status === 'FAILED') button.querySelector('b').textContent = '题面翻译失败';
  else button.querySelector('b').textContent = '题面暂未准备';
}

function startClock() {
  clearInterval(countdownTimer);
  countdownTimer = setInterval(() => {
    if (document.hidden || refreshInFlight) return;
    countdown = Math.max(0, countdown - 1);
    board$('#countdownLabel').textContent = countdown > 0 ? `${countdown} 秒后自动刷新` : '正在自动刷新';
    if (countdown === 0) loadLeaderboard(false);
  }, 1000);
}

function renderLeaderboard(data) {
  board$('#participantTotal').textContent = data.participantCount || 0;
  board$('#rankedTotal').textContent = data.rankedCount || 0;
  board$('#leaderName').textContent = data.entries?.find(entry => entry.classRank === 1)?.displayName || '—';
  if (!data.configured) {
    board$('#contestTitle').textContent = 'AtCoder 实时排行榜';
    setContestState('等待配置', 'unknown');
    board$('#contestMeta').textContent = '管理员尚未配置当前比赛。';
    board$('#syncLabel').textContent = '尚未同步';
    board$('.live-dot').classList.add('off');
    board$('#officialLink').classList.add('hidden');
    hideNotice();
    showBoardState('排行榜尚未配置，请联系管理员在后台添加比赛与选手。');
    return;
  }

  const contest = data.contest || {};
  if (currentContestId !== contest.id) {
    currentContestId = contest.id || '';
    currentPage = 1;
  }
  board$('#contestTitle').textContent = contest.title || contest.id || 'AtCoder 实时排行榜';
  setContestState(statusText(contest.status), String(contest.status || 'unknown').toLowerCase());
  board$('#contestMeta').textContent = contestMeta(contest);
  board$('#syncLabel').textContent = data.lastSyncedAt ? `同步于 ${formatClock(data.lastSyncedAt)}` : '尚无可用数据';
  board$('.live-dot').classList.toggle('off', !data.dataAvailable || data.stale);
  board$('#officialLink').href = contest.url || '#';
  board$('#officialLink').classList.toggle('hidden', !contest.url);

  if (data.stale || data.error) showNotice(data.error || 'AtCoder 暂时不可用，当前展示最近一次成功同步的数据。', !data.dataAvailable);
  else hideNotice();

  if (!data.entries?.length) {
    showBoardState('当前比赛还没有录入选手。管理员可以在后台逐个添加展示昵称和 AtCoder ID。');
    return;
  }
  renderTable(data.tasks || [], data.entries);
}

function renderTable(tasks, entries) {
  currentTasks = tasks;
  currentEntries = entries;
  currentPage = Math.min(currentPage, Math.max(1, Math.ceil(entries.length / PAGE_SIZE)));
  board$('#leaderboardHead').innerHTML = `
    <th class="sticky-rank">班级排名</th>
    <th class="sticky-user">选手</th>
    <th class="sticky-total">总分 / 罚时</th>
    ${tasks.map(task => `<th class="task-head"><a href="${attr(task.url)}" target="_blank" rel="noreferrer" title="${attr(task.name)}"><strong>${html(task.label || '?')}</strong><span>${html(task.name || task.id)}</span></a></th>`).join('')}
    <th>官方排名</th><th>名次变化</th>`;
  renderCurrentPage();
  board$('#boardState').classList.add('hidden');
  board$('#tableWrap').classList.remove('hidden');
}

function renderCurrentPage() {
  const totalPages = Math.max(1, Math.ceil(currentEntries.length / PAGE_SIZE));
  currentPage = Math.min(Math.max(1, currentPage), totalPages);
  const start = (currentPage - 1) * PAGE_SIZE;
  const pageEntries = currentEntries.slice(start, start + PAGE_SIZE);
  board$('#leaderboardBody').innerHTML = pageEntries.map(entry => renderEntry(entry, currentTasks)).join('');
  renderPagination(totalPages, start, pageEntries.length);
}

function renderPagination(totalPages, start, visibleCount) {
  const pagination = board$('#boardPagination');
  const end = start + visibleCount;
  board$('#pageSummary').textContent = currentEntries.length
    ? `${start + 1}–${end} / ${currentEntries.length} 人`
    : '每页 50 人';
  if (totalPages <= 1) {
    pagination.classList.add('hidden');
    pagination.innerHTML = '';
    return;
  }

  const pages = pageItems(currentPage, totalPages);
  pagination.innerHTML = `
    <button type="button" data-page="${currentPage - 1}" ${currentPage === 1 ? 'disabled' : ''} aria-label="上一页">←</button>
    <div>${pages.map(page => page === '…'
      ? '<span class="page-gap">…</span>'
      : `<button type="button" data-page="${page}" class="${page === currentPage ? 'active' : ''}" ${page === currentPage ? 'aria-current="page"' : ''}>${page}</button>`).join('')}</div>
    <button type="button" data-page="${currentPage + 1}" ${currentPage === totalPages ? 'disabled' : ''} aria-label="下一页">→</button>
  `;
  pagination.querySelectorAll('[data-page]').forEach(button => {
    button.onclick = () => {
      if (button.disabled) return;
      currentPage = Number(button.dataset.page);
      renderCurrentPage();
    };
  });
  pagination.classList.remove('hidden');
}

function pageItems(page, total) {
  if (total <= 7) return Array.from({length: total}, (_, index) => index + 1);
  const values = new Set([1, total, page - 1, page, page + 1]);
  const sorted = [...values].filter(value => value > 0 && value <= total).sort((a, b) => a - b);
  const result = [];
  sorted.forEach((value, index) => {
    if (index && value - sorted[index - 1] > 1) result.push('…');
    result.push(value);
  });
  return result;
}

function renderEntry(entry, tasks) {
  const rank = entry.classRank;
  const rankClass = rank === 1 ? 'first' : rank === 2 ? 'second' : rank === 3 ? 'third' : '';
  const results = new Map((entry.taskResults || []).map(result => [result.taskId, result]));
  const notStarted = entry.status === 'NOT_STARTED';
  return `<tr class="${notStarted ? 'not-started' : ''}">
    <td class="sticky-rank">${rank ? `<span class="rank-value ${rankClass}">${rank}</span>` : '<span class="rank-empty">—</span>'}</td>
    <td class="sticky-user player-cell"><strong title="${attr(entry.displayName)}">${html(entry.displayName)}</strong><a href="https://atcoder.jp/users/${attr(entry.atcoderUsername)}" target="_blank" rel="noreferrer">@${html(entry.atcoderUsername)}</a></td>
    <td class="sticky-total total-cell">${notStarted ? '<span class="not-started-label">未参赛或尚无提交</span>' : `<strong>${score(entry.totalScore)}</strong><small>${html(entry.penaltyText)}${entry.wrongAttempts ? ` (+${entry.wrongAttempts})` : ''}</small>`}</td>
    ${tasks.map(task => renderTaskCell(results.get(task.id))).join('')}
    <td class="official-rank">${entry.officialRank ?? '—'}</td>
    <td>${movement(entry.movement)}</td>
  </tr>`;
}

function renderTaskCell(result) {
  if (!result || result.status === 'EMPTY') return '<td class="task-cell">—</td>';
  if (result.status === 'PENDING') return '<td class="task-cell pending"><strong>判定中</strong><small>Pending</small></td>';
  if (result.status === 'FROZEN') return '<td class="task-cell frozen"><strong>封榜</strong><small>Frozen</small></td>';
  if (result.status === 'AC') return `<td class="task-cell ac"><strong>${score(result.score)}${result.failure ? `<span class="wrong-count">(${result.failure})</span>` : ''}</strong><small>${shortElapsed(result.elapsedText)}</small></td>`;
  return `<td class="task-cell failed"><strong>-${result.failure || result.penalty || 1}</strong><small>${shortElapsed(result.elapsedText)}</small></td>`;
}

function movement(value = {}) {
  const type = String(value.type || 'NONE').toLowerCase();
  if (type === 'up') return `<span class="movement up">↑ ${value.places}</span>`;
  if (type === 'down') return `<span class="movement down">↓ ${value.places}</span>`;
  if (type === 'new') return '<span class="movement new">新</span>';
  if (type === 'same') return '<span class="movement same">—</span>';
  return '<span class="movement none">—</span>';
}

function showBoardState(message) {
  board$('#boardState').textContent = message;
  board$('#boardState').classList.remove('hidden');
  board$('#tableWrap').classList.add('hidden');
  board$('#boardPagination').classList.add('hidden');
  board$('#pageSummary').textContent = '每页 50 人';
}

function showNotice(message, error) {
  const notice = board$('#noticeStrip');
  notice.textContent = message;
  notice.classList.toggle('error', Boolean(error));
  notice.classList.remove('hidden');
}

function hideNotice() { board$('#noticeStrip').classList.add('hidden'); }
function setContestState(text, state) { const el = board$('#contestState'); el.textContent = text; el.className = `contest-state ${state}`; }
function statusText(status) { return ({UPCOMING:'未开始',RUNNING:'比赛中',FINISHED:'已结束',UNKNOWN:'时间待确认'})[status] || '时间待确认'; }
function contestMeta(contest) {
  const range = contest.startAt && contest.endAt ? `${formatDate(contest.startAt)} - ${formatDate(contest.endAt)}` : '比赛时间未读取';
  return `${String(contest.id || '').toUpperCase()} · ${range}`;
}
function formatDate(value) { return new Date(value).toLocaleString('zh-CN', {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}); }
function formatClock(value) { return new Date(value).toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}); }
function shortElapsed(value) { return String(value || '--:--').replace(/^00:/, ''); }
function score(value) { return Number(value || 0).toLocaleString('zh-CN', {maximumFractionDigits:2}); }
function html(value) { return String(value ?? '').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&#039;'); }
function attr(value) { return html(value || '#'); }
function showToast(message) {
  if (window.DataForgeUI?.toast) window.DataForgeUI.toast(message, {duration: 2500});
  else { const toast = board$('#toast'); toast.textContent = message; toast.classList.add('show'); setTimeout(() => toast.classList.remove('show'), 2500); }
}
