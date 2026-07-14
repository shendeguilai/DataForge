const atcoder$ = (selector) => document.querySelector(selector);

const form = atcoder$('#atcoderForm');
const usernameInput = atcoder$('#atcoderUsername');
const queryButton = atcoder$('#queryButton');
const statusPanel = atcoder$('#statusPanel');
const resultPanel = atcoder$('#resultPanel');

form?.addEventListener('submit', async (event) => {
  event.preventDefault();
  await queryAtcoder(usernameInput.value);
});

const initialUser = new URLSearchParams(location.search).get('user') || localStorage.getItem('dataforge.atcoder.user') || '';
if (initialUser) {
  usernameInput.value = initialUser;
  if (new URLSearchParams(location.search).has('user')) {
    queryAtcoder(initialUser);
  }
}

async function queryAtcoder(rawUsername) {
  const username = rawUsername.trim();
  if (!username) {
    showStatus('请输入 AtCoder 用户名。', true);
    return;
  }
  if (!/^[A-Za-z0-9_]{1,32}$/.test(username)) {
    showStatus('AtCoder 用户名只能包含字母、数字和下划线。', true);
    return;
  }

  localStorage.setItem('dataforge.atcoder.user', username);
  history.replaceState(null, '', `?user=${encodeURIComponent(username)}`);
  setLoading(true);
  showStatus(`<strong>正在查询 ${escapeHtml(username)}</strong><br>正在拉取最近参赛记录、题目资源和赛时提交。`, false);
  resultPanel.classList.add('hidden');
  resultPanel.innerHTML = '';

  try {
    const response = await fetch(`/api/tools/atcoder/${encodeURIComponent(username)}`);
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(payload.error || `查询失败：HTTP ${response.status}`);
    }
    renderResults(payload);
    statusPanel.classList.add('hidden');
  } catch (error) {
    showStatus(error.message || '查询失败，请稍后重试。', true);
  } finally {
    setLoading(false);
  }
}

function setLoading(loading) {
  if (!queryButton) return;
  queryButton.disabled = loading;
  queryButton.querySelector('span').textContent = loading ? '查询中' : '查询';
}

function showStatus(message, isError) {
  statusPanel.classList.remove('hidden');
  statusPanel.classList.toggle('error-box', Boolean(isError));
  statusPanel.innerHTML = message;
}

function renderResults(data) {
  const contests = data.contests || [];
  resultPanel.dataset.username = data.username || '';
  const cards = contests.map(renderContest).join('');
  resultPanel.innerHTML = `
    <div class="panel stats-summary">
      <h2>${escapeHtml(data.username)} 的近 ${contests.length} 场比赛</h2>
      <p>生成时间：${escapeHtml(data.generatedAt || '-')}。每场比赛按 AtCoder 排行榜风格展示，题头为题号(过题数/提交数)，格子里是该题提交情况。</p>
    </div>
    ${cards || '<div class="panel atcoder-state">没有可展示的比赛记录。</div>'}
  `;
  resultPanel.classList.remove('hidden');
}

function renderContest(contest) {
  const ratingDelta = contest.ratingDelta;
  const deltaClass = ratingDelta > 0 ? 'up' : ratingDelta < 0 ? 'down' : '';
  const problems = contest.problems || [];
  const contestShort = shortContestName(contest.contestId);
  const problemHeaders = problems.map(problem => renderProblemHeader(contestShort, problem)).join('');
  const problemCells = problems.map(problem => renderProblemCell(problem, contest.submissions || [])).join('');

  return `
    <article class="panel contest-card">
      <header class="contest-head">
        <div class="contest-title">
          <a href="${escapeAttr(contest.contestUrl)}" target="_blank" rel="noreferrer">${escapeHtml(contest.contestName || contest.contestId)}</a>
          <div class="contest-meta">
            <span>${escapeHtml(contest.contestId)}</span>
            <span>${escapeHtml(contest.startTime)} - ${escapeHtml(contest.endTime)}</span>
            <span>${contest.rated ? 'Rated' : 'Unrated'}</span>
            <span>Rank ${valueOrDash(contest.place)}</span>
          </div>
        </div>
        <div class="rating-line ${deltaClass}" aria-label="Rating 变化">
          <span>Rating</span>
          <strong>${valueOrDash(contest.oldRating)} → ${valueOrDash(contest.newRating)}</strong>
          <b>${formatDelta(ratingDelta)}</b>
          <em>Perf ${valueOrDash(contest.performance)}</em>
        </div>
      </header>
      <div class="scoreboard-wrap">
        <table class="scoreboard-table">
          <thead>
          <tr>
            <th class="user-head">${escapeHtml(contestShort)}</th>
            ${problemHeaders}
          </tr>
          </thead>
          <tbody>
          <tr>
            <th class="user-cell">
              <span>${escapeHtml(resultPanel.dataset.username || '')}</span>
              <small>${contest.acCount || 0} AC / ${contest.submissionCount || 0} 提交</small>
            </th>
            ${problemCells}
          </tr>
          </tbody>
        </table>
      </div>
    </article>
  `;
}

function renderProblemHeader(contestShort, problem) {
  const solved = problem.globalStatsAvailable ? (problem.globalAcceptedCount || 0) : '-';
  const submissions = problem.globalStatsAvailable ? (problem.globalSubmissionUserCount || 0) : '-';
  return `
    <th class="problem-head">
      <a href="${escapeAttr(problem.url)}" target="_blank" rel="noreferrer" title="${escapeAttr(problem.title || problem.problemId)}">
        ${escapeHtml(contestShort)} ${escapeHtml(problem.problemIndex || '?')}<span>(${solved}/${submissions})</span>
      </a>
    </th>
  `;
}

function renderProblemCell(problem, submissions) {
  const items = submissions
    .filter(submission => submission.problemId === problem.problemId)
    .sort((a, b) => (a.elapsedSeconds || 0) - (b.elapsedSeconds || 0));
  if (items.length === 0) {
    return '<td class="score-cell empty">-</td>';
  }

  const accepted = items.find(submission => submission.result === 'AC');
  if (accepted) {
    const wrongBeforeAc = items.filter(submission => submission.result !== 'AC' && submission.elapsedSeconds < accepted.elapsedSeconds).length;
    const score = accepted.point && accepted.point > 0 ? formatScore(accepted.point) : 'AC';
    return `
      <td class="score-cell solved">
        <a href="${escapeAttr(accepted.url)}" target="_blank" rel="noreferrer">
          <strong>${escapeHtml(score)}</strong>${wrongBeforeAc ? `<span class="penalty">(${wrongBeforeAc})</span>` : ''}
          <small>${escapeHtml(accepted.elapsedText || problem.firstAcceptedElapsed || '-')}</small>
        </a>
      </td>
    `;
  }

  const last = items[items.length - 1];
  const result = last.result || 'SUB';
  return `
    <td class="score-cell failed">
      <a href="${escapeAttr(last.url)}" target="_blank" rel="noreferrer">
        <strong>${escapeHtml(result)}</strong><span class="penalty">(${items.length})</span>
        <small>${escapeHtml(last.elapsedText || '-')}</small>
      </a>
    </td>
  `;
}

function shortContestName(contestId) {
  return String(contestId || '').toUpperCase();
}

function formatDelta(value) {
  if (value === null || value === undefined) return '-';
  if (value > 0) return `+${value}`;
  return String(value);
}

function formatScore(value) {
  return Number(value).toLocaleString('en-US', {maximumFractionDigits: 2});
}

function valueOrDash(value) {
  return value === null || value === undefined ? '-' : escapeHtml(String(value));
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function escapeAttr(value) {
  return escapeHtml(value || '#');
}
