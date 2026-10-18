(() => {
  'use strict';
  const base = '/api/tools/kedaoj-homework';
  const $ = s => document.querySelector(s);
  const ui = window.DataForgeUI;
  const state = {classes: [], assignments: [], settings: null, classId: '', assignmentId: '', selected: null, selectedProblem: null, verified: null, task: null, assignmentMode: 'create', editingId: null, editingRevision: 0};
  const statusNames = {AC: 'AC', LATE: 'AC · 补交', ATTEMPTED: '尝试中', PENDING: '评测中', EMPTY: '未见提交', UNKNOWN: '待核验'};
  const verdicts = {0: '排队中', 1: 'Accepted', 2: '答案错误', 3: '超时', 4: '内存超限', 5: '输出超限', 6: '运行错误', 7: '编译错误', 8: '系统错误', 9: '已取消', 10: '其他错误', 11: '被 Hack', 20: '评测中', 21: '编译中', 22: '已分配评测', 30: '已忽略', 31: '格式错误', 32: 'Hack 成功', 33: 'Hack 失败'};
  let pollTimer = null;
  const h = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
  const date = value => value ? new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(new Date(value)) : '尚未更新';
  const key = (s, p) => `${s.uid}:${p.docId}`;
  const current = () => state.assignments.find(a => a.id === state.assignmentId);
  const chosenClass = () => state.classes.find(c => c.id === state.classId);
  function error(message, selector = '#pageError') { const el = $(selector); el.textContent = message; el.classList.remove('hidden'); }
  async function request(path, options = {}) {
    const response = await fetch(base + path, {cache: 'no-store', ...options, headers: {'Content-Type': 'application/json', ...options.headers}});
    if (response.status === 204) return null;
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(response.status === 401 ? '登录已过期，请重新登录' : body.error || `请求失败（${response.status}）`);
    return body;
  }
  function action(fn, errorSelector = '#pageError') {
    return async event => {
      event?.preventDefault(); $(errorSelector).classList.add('hidden');
      const button = event?.submitter || (event?.currentTarget?.tagName === 'BUTTON' ? event.currentTarget : null);
      if (button?.disabled) return;
      ui?.setBusy(button, true, '处理中…');
      try { await fn(event); } catch (e) { error(e.message, errorSelector); } finally {
        ui?.setBusy(button, false);
        if (['refreshAssignment', 'archiveAssignment', 'retryFailed', 'fullRefresh'].includes(button?.id)) renderBoard();
        if (button?.id === 'clearCredential' || button?.id === 'verifyConnection') {
          $('#clearCredential').disabled = !state.settings?.configured;
          $('#verifyConnection').disabled = !state.settings?.configured;
        }
      }
    };
  }
  function tab(name) {
    $('#pageError').classList.add('hidden');
    document.querySelectorAll('[data-tab]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
    ['overview', 'students', 'connection'].forEach(t => $('#' + t).hidden = t !== name);
  }
  function options(select, entries, selected, placeholder) {
    select.innerHTML = entries.length ? entries.map(e => `<option value="${h(e.id)}">${h(e.name)}</option>`).join('') : `<option value="">${h(placeholder)}</option>`;
    if (entries.some(e => e.id === selected)) select.value = selected;
  }
  function renderClasses() {
    if (!state.classes.some(c => c.id === state.classId)) state.classId = state.classes[0]?.id || '';
    for (const id of ['#classFilter', '#rosterClass', '#newClass']) options($(id), state.classes, state.classId, '请先创建班级');
    renderRoster(); renderAssignments();
  }
  function renderAssignments() {
    const list = state.assignments.filter(a => a.classId === state.classId).sort((a, b) => Number(a.archived) - Number(b.archived));
    if (!list.some(a => a.id === state.assignmentId)) state.assignmentId = list[0]?.id || '';
    options($('#assignmentFilter'), list.map(a => ({id: a.id, name: a.title + (a.archived ? ' · 已归档' : '')})), state.assignmentId, '暂无周作业');
    renderBoard();
  }
  function renderBoard() {
    const a = current(); $('#assignmentEmpty').classList.toggle('hidden', !!a); $('#assignmentBoard').classList.toggle('hidden', !a);
    $('#taskStatus').classList.toggle('hidden', !state.task && !state.lastTask?.assignmentIds?.includes(a?.id));
    if (!a) return;
    $('#assignmentTitle').textContent = a.title;
    $('#assignmentPeriod').textContent = `${date(a.startAt)} → ${date(a.endAt)} · 北京时间`;
    $('#assignmentInfo').textContent = `${a.problems.length} 道题 · ${a.students.length} 人 · ${a.domain} 域 · AC 即完成`;
    $('#assignmentPhase').textContent = a.archived ? '已归档' : new Date(a.startAt) > new Date() ? '未开始' : new Date(a.endAt) <= new Date() ? '已截止 · 跟踪补交' : '进行中';
    $('#archiveAssignment').textContent = a.archived ? '恢复跟踪' : '归档';
    $('#refreshAssignment').disabled = a.archived || !!state.task;
    $('#editAssignment').disabled = a.archived || !!state.task;
    $('#fullRefresh').disabled = a.archived || !!state.task;
    $('#retryFailed').disabled = a.archived || !!state.task;
    $('#assignmentIssue').classList.toggle('hidden', !a.error && !a.failedCells);
    $('#assignmentIssueText').textContent = a.error || '存在查询失败项，保留上次成功结果。';
    $('#retryFailed').classList.toggle('hidden', !a.failedCells);
    $('#exportExcel').href = base + '/assignments/' + encodeURIComponent(a.id) + '/export.xlsx';
    renderChanges(a);
    $('#completedStudents').textContent = `${a.completedStudents} / ${a.students.length}`;
    $('#completedCells').textContent = `${a.completedCells} / ${a.students.length * a.problems.length}`;
    $('#unknownCells').textContent = a.unknownCells;
    $('#lastSync').textContent = `最近成功更新：${date(a.syncedAt)}${a.attemptedAt && date(a.attemptedAt) !== date(a.syncedAt) ? ' · 最近尝试：' + date(a.attemptedAt) : ''}`;
    $('#scheduleSummary').textContent = a.archived ? '已归档，停止更新' : state.settings?.autoUpdate ? '每天北京时间 00:00 自动更新，也可手动更新' : '仅手动更新';
    $('#completionTable').style.setProperty('--hw-problem-count', a.problems.length);
    $('#completionTable colgroup').innerHTML = '<col class="hw-student-col"><col class="hw-progress-col">' + a.problems.map(() => '<col>').join('');
    $('#completionTable thead').innerHTML = `<tr><th scope="col">学生<span class="hw-head-secondary">可达 OJ UID</span></th><th scope="col">完成进度</th>${a.problems.map(p => { const stats = statsFor(a, p); return `<th scope="col" class="hw-problem-th"><button type="button" class="hw-problem-head" data-problem-doc="${h(p.docId)}" aria-label="${h(p.pid)} ${h(p.title)}，查看每题统计"><span class="hw-problem-code">${h(p.pid)}</span><span class="hw-problem-name" title="${h(p.title)}">${h(p.title)}</span><span class="hw-problem-stat">已确认 AC <strong>${stats.completed} / ${stats.total}</strong>${stats.unknown ? ' · 待核验 ' + stats.unknown : ''}</span></button></th>`; }).join('')}</tr>`;
    const filter = $('#studentFilter').value;
    $('#completionTable tbody').innerHTML = a.students.filter(s => filter === 'all' || filter === 'incomplete' && a.problems.some(p => !a.cells[key(s, p)]?.completed) || filter === 'unknown' && a.problems.some(p => !a.cells[key(s, p)] || a.cells[key(s, p)].status === 'UNKNOWN')).map(s => {
      const complete = a.problems.filter(p => a.cells[key(s, p)]?.completed).length;
      return `<tr><td><button type="button" class="hw-student-name hw-name-button" data-student-uid="${h(s.uid)}" aria-label="查看${h(s.name)}的作业总览">${h(s.name)}</button><span class="hw-uid">${h(s.uid)}</span></td><td class="hw-row-progress"><div class="hw-student-progress"><span><strong>${complete}</strong><span class="hw-progress-total"> / ${a.problems.length}</span></span><span class="hw-progress-track" aria-hidden="true"><span style="width:${complete / a.problems.length * 100}%"></span></span></div></td>${a.problems.map(p => {
        const cell = a.cells[key(s, p)], status = cell?.status || 'UNKNOWN';
        const mark = status === 'AC' || status === 'LATE' ? '✓' : status === 'EMPTY' ? '—' : '·';
        return `<td><button type="button" class="hw-cell ${h(status)}" data-uid="${h(s.uid)}" data-doc="${h(p.docId)}" aria-pressed="false" aria-label="${h(s.name)} ${h(p.pid)} ${h(statusNames[status])}，查看提交依据"><span class="hw-cell-mark" aria-hidden="true">${mark}</span>${h(statusNames[status])}${status === 'UNKNOWN' && cell?.completed ? '（上次 AC）' : ''}</button></td>`;
      }).join('')}</tr>`;
    }).join('') || `<tr><td colspan="${a.problems.length + 2}" class="hw-table-empty">没有符合条件的学生</td></tr>`;
    if (state.selectedProblem?.assignmentId === a.id) showProblem(state.selectedProblem.docId); else $('#problemDetail').classList.add('hidden');
    if (state.selected && state.selected.assignmentId === a.id) showDetail(state.selected.uid, state.selected.docId); else $('#cellDetail').classList.add('hidden');
  }
  function showDetail(uid, docId) {
    const a = current(), s = a.students.find(s => s.uid === Number(uid)), p = a.problems.find(p => p.docId === Number(docId));
    if (!s || !p) return;
    state.selected = {assignmentId: a.id, uid: s.uid, docId: p.docId};
    $('#completionTable').querySelectorAll('[data-uid]').forEach(button => {
      const selected = Number(button.dataset.uid) === s.uid && Number(button.dataset.doc) === p.docId;
      button.classList.toggle('hw-current-selection', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    const c = a.cells[key(s, p)] || {status: 'UNKNOWN', submissions: 0, bestScore: 0};
    const result = r => r ? `${verdicts[r.status] || '未知状态'} · ${r.score} 分` : '—';
    const record = r => r ? `<a href="https://kedaoi.cn${a.domain === 'system' ? '' : '/d/' + encodeURIComponent(a.domain)}/record/${encodeURIComponent(r.rid)}" target="_blank" rel="noopener noreferrer">查看 OJ 记录</a>` : '—';
    $('#cellDetail').innerHTML = `<div class="hw-board-title" style="padding:0"><h3>${h(s.name)} · ${h(p.pid)} · ${h(p.title)}</h3><span class="hw-cell ${h(c.status)}">${h(statusNames[c.status])}</span></div>${c.error ? `<div class="error-box">${h(c.error)}${c.previousStatus ? '；上次结果：' + h(statusNames[c.previousStatus]) : ''}。上次成功更新：${h(date(c.syncedAt))}</div>` : ''}<dl class="hw-detail-fields"><div><dt>提交次数 / 最高分</dt><dd>${h(c.submissions)} 次 / ${h(c.bestScore)} 分</dd></div><div><dt>最近提交时间</dt><dd>${h(c.latest ? date(c.latest.submittedAt) : '—')}</dd></div><div><dt>最近评测结果</dt><dd>${h(result(c.latest))}</dd></div><div><dt>首次 AC 提交时间</dt><dd>${h(c.accepted ? date(c.accepted.submittedAt) : '—')}</dd></div><div><dt>AC 记录来源</dt><dd>${c.accepted ? c.accepted.contest ? '比赛 / 作业内提交' : '平时练习' : '—'}${c.accepted ? ' · ' + record(c.accepted) : ''}</dd></div><div><dt>截止前完成</dt><dd>${c.completed ? c.beforeDeadline ? '是' : '否，已补交' : '尚未完成'} · ${record(c.latest)}</dd></div></dl>`;
    $('#cellDetail').classList.remove('hidden');
  }
  function statsFor(a, p) {
    const stored = a.problemStats?.find(x => x.docId === p.docId); if (stored) return stored;
    const cells = a.students.map(s => a.cells[key(s, p)]);
    return {total: cells.length, completed: cells.filter(c => c && c.status !== 'UNKNOWN' && c.completed).length, unknown: cells.filter(c => !c || c.status === 'UNKNOWN').length};
  }
  function renderChanges(a) {
    const change = a.lastChange; $('#changesPanel').classList.toggle('hidden', !change);
    if (!change) return;
    const completed = change.newlyCompleted || [], late = change.newlyLate || [], altered = change.changedResults || [];
    const headline = change.initial ? `首次核验，确认完成 ${completed.length} 题次` : `本次新增确认 ${completed.length} 题次完成${late.length ? '，其中 ' + late.length + ' 题次补交' : ''}`;
    const list = [...completed.map(c => `${c.name} · ${c.pid}：${c.to === 'LATE' ? 'AC · 补交' : 'AC'}`), ...altered.map(c => `${c.name} · ${c.pid}：此前 AC，目前${statusNames[c.to] || c.to}`)];
    $('#changesPanel').innerHTML = `<div><strong>${h(headline)}</strong><span class="hw-muted">${h(date(change.at))} · 核验 ${change.checked} 项${change.failed ? ' · ' + change.failed + ' 项待核验' : ''}</span></div>${altered.length ? '<p class="hw-muted">有 ' + altered.length + ' 项历史完成结果发生变化，建议查看提交详情。</p>' : ''}${list.length ? '<details><summary>查看变化明细</summary><ul>' + list.map(t => '<li>' + h(t) + '</li>').join('') + '</ul></details>' : ''}`;
  }
  function showProblem(docId) {
    const a = current(), p = a?.problems.find(p => p.docId === Number(docId)); if (!p) return;
    state.selectedProblem = {assignmentId: a.id, docId: p.docId};
    const stats = statsFor(a, p);
    const groups = [['已完成', c => c && c.status !== 'UNKNOWN' && c.completed], ['已确认待完成', c => c && c.status !== 'UNKNOWN' && !c.completed], ['待核验', c => !c || c.status === 'UNKNOWN']];
    $('#problemDetail').innerHTML = `<div class="hw-board-title" style="padding:0"><h3>${h(p.pid)} · ${h(p.title)}</h3><button type="button" class="hw-link" data-close-problem>收起</button></div><p class="hw-muted">已确认 AC ${stats.completed} / ${stats.total} 人${stats.unknown ? ' · 待核验 ' + stats.unknown + ' 人' : ''}</p>` + groups.map(([title, matches]) => {
      const members = a.students.filter(s => matches(a.cells[key(s, p)]));
      return `<div class="hw-problem-group"><strong>${h(title)} · ${members.length} 人</strong><div class="hw-student-chips">${members.map(s => `<button type="button" class="ghost-btn" data-student-uid="${h(s.uid)}">${h(s.name)} <span>${h(statusNames[a.cells[key(s, p)]?.status || 'UNKNOWN'])}</span></button>`).join('') || '<span class="hw-muted">无</span>'}</div></div>`;
    }).join('');
    $('#problemDetail').classList.remove('hidden');
  }
  async function showStudent(uid) {
    const profile = await request('/students/' + encodeURIComponent(uid) + '/overview');
    $('#studentOverviewTitle').textContent = profile.name + ' · 作业总览';
    $('#studentOverviewBody').innerHTML = `<p class="hw-muted">OJ UID ${h(profile.uid)} · ${h(profile.uname)} · 包含各班级及归档作业</p><div class="hw-profile-summary"><div><strong>${profile.assignmentCount}</strong><span>份作业</span></div><div><strong>${profile.onTimeAssignments}</strong><span>份截止前全部完成</span></div><div><strong>${profile.outstandingCells}</strong><span>题次待完成</span></div><div><strong>${profile.unknownCells}</strong><span>题次待核验</span></div></div><p class="hw-muted">待完成、待核验合计仅统计已开始且未归档的作业；历史补交 ${profile.lateCells} 题次。</p><div class="hw-table-wrap"><table class="hw-profile-table"><thead><tr><th>作业 / 班级</th><th>完成</th><th>待完成题目</th><th>待核验</th><th>操作</th></tr></thead><tbody>${profile.assignments.map(a => `<tr><td><strong>${h(a.title)}</strong><span class="hw-uid">${h(a.className)} · ${a.archived ? '已归档' : new Date(a.startAt) > new Date() ? '未开始' : '跟踪中'}</span><span class="hw-uid">截止 ${h(date(a.endAt))}</span></td><td>${a.completed} / ${a.total}${a.late ? '<span class="hw-uid">补交 ' + a.late + ' 题</span>' : ''}</td><td>${h(a.remaining.map(p => p.pid).join('、')) || '—'}</td><td>${h(a.unknown.map(p => p.pid).join('、')) || '—'}</td><td><button type="button" class="hw-link" data-jump-assignment="${h(a.id)}" data-jump-class="${h(a.classId)}">查看作业</button></td></tr>`).join('') || '<tr><td colspan="5">还没有作业记录</td></tr>'}</tbody></table></div>`;
    ui.openDialog($('#studentOverviewDialog'));
  }
  function renderRoster() {
    const c = chosenClass();
    $('#renameClass').disabled = !c;
    $('#rosterTable tbody').innerHTML = c?.students.length ? c.students.map(s => `<tr><td><button type="button" class="hw-name-button" data-student-uid="${h(s.uid)}">${h(s.name)}</button></td><td>${h(s.uid)}</td><td>${h(s.uname)}</td><td><div class="hw-row-actions"><button type="button" class="hw-link" data-edit-student="${h(s.id)}">修改</button><button type="button" class="hw-link" data-remove-student="${h(s.id)}">移出班级</button></div></td></tr>`).join('') : '<tr><td colspan="4" class="hw-table-empty">暂无学生，请先创建班级并添加名单</td></tr>';
  }
  function renderSettings() {
    const s = state.settings; if (!s) return;
    $('#defaultDomain').value = s.domain; $('#autoUpdate').value = String(s.autoUpdate);
    $('#connectionState').textContent = s.connectionState === 'CONNECTED' ? '已连接' : s.configured ? '需要验证' : '尚未配置';
    $('#connectionInfo').textContent = `${s.ojUsername ? `OJ 账号：${s.ojUsername}（UID ${s.ojUid}） · ` : ''}${s.message || '请录入登录凭据'}${s.checkedAt ? ' · ' + date(s.checkedAt) : ''}`;
    $('#verifyConnection').disabled = !s.configured; $('#clearCredential').disabled = !s.configured;
  }
  function rosterInputs() {
    const rows = $('#rosterInput').value.split(/\r?\n/).map(s => s.trim()).filter(Boolean).map(line => {
      const parts = line.split(/[,，\t ]+/);
      if (parts.length !== 2 || !/^\d+$/.test(parts[1]) || !Number.isSafeInteger(Number(parts[1])) || Number(parts[1]) <= 0) throw new Error('每行请填写“姓名, OJ UID”，UID 必须是大于 0 的整数');
      return {name: parts[0], uid: Number(parts[1])};
    });
    if (!rows.length || rows.length > 500 || new Set(rows.map(s => s.uid)).size !== rows.length) throw new Error('每次填写 1～500 位学生，UID 不能重复');
    return rows;
  }
  function resetPreview() { state.verified = null; $('#confirmStudents').classList.add('hidden'); $('#rosterPreview').replaceChildren(); }
  async function reloadAssignments() { state.assignments = await request('/assignments'); renderAssignments(); }
  async function load() {
    const [settings, classes, assignments, task] = await Promise.all([request('/settings'), request('/classes'), request('/assignments'), request('/tasks/active')]);
    Object.assign(state, {settings, classes, assignments}); renderSettings(); renderClasses(); if (task) watch(task);
  }
  function watch(task) {
    clearTimeout(pollTimer); state.lastTask = task; state.task = ['QUEUED', 'RUNNING'].includes(task.state) ? task : null;
    $('#taskStatus').classList.remove('hidden'); $('#taskMessage').textContent = task.message;
    $('#taskProgress').max = Math.max(1, task.total); $('#taskProgress').value = task.completed;
    renderBoard();
    if (!state.task) return;
    pollTimer = setTimeout(async () => {
      try { const next = await request('/tasks/' + task.id); await reloadAssignments(); watch(next); }
      catch (e) { state.task = null; $('#taskMessage').textContent = '获取更新进度失败：' + e.message; renderBoard(); }
    }, 2000);
  }
  document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => tab(b.dataset.tab));
  document.querySelectorAll('[data-open-tab]').forEach(b => b.onclick = () => tab(b.dataset.openTab));
  document.querySelectorAll('[data-close-dialog]').forEach(b => b.onclick = () => ui.closeDialog($('#' + b.dataset.closeDialog)));
  $('#manageStudents').onclick = () => tab('students');
  for (const id of ['#classFilter', '#rosterClass']) $(id).onchange = e => { state.classId = e.target.value; state.selected = null; resetPreview(); renderClasses(); };
  $('#assignmentFilter').onchange = e => { state.assignmentId = e.target.value; state.selected = null; $('#pageError').classList.add('hidden'); renderBoard(); };
  $('#studentFilter').onchange = renderBoard;
  $('#completionTable').onclick = action(async e => {
    const student = e.target.closest('[data-student-uid]'), problem = e.target.closest('[data-problem-doc]'), cell = e.target.closest('[data-uid]');
    if (student) await showStudent(student.dataset.studentUid);
    else if (problem) showProblem(problem.dataset.problemDoc);
    else if (cell) showDetail(cell.dataset.uid, cell.dataset.doc);
  });
  $('#classForm').onsubmit = action(async () => { const c = await request('/classes', {method: 'POST', body: JSON.stringify({name: $('#className').value.trim()})}); state.classes.unshift(c); state.classId = c.id; $('#className').value = ''; renderClasses(); });
  $('#renameClass').onclick = action(async () => { const c = chosenClass(), name = window.prompt('班级名称', c.name); if (name === null) return; const next = await request('/classes/' + c.id, {method: 'PUT', body: JSON.stringify({name})}); state.classes = state.classes.map(x => x.id === c.id ? next : x); renderClasses(); });
  $('#rosterInput').oninput = resetPreview;
  $('#rosterForm').onsubmit = action(async () => {
    if (!state.classId) throw new Error('请先创建班级');
    const classId = state.classId, originalInput = $('#rosterInput').value;
    const inputs = rosterInputs(); const preview = await request('/students/preview', {method: 'POST', body: JSON.stringify(inputs)});
    if (state.classId !== classId || $('#rosterInput').value !== originalInput) throw new Error('班级或名单已改变，请重新核对');
    state.verified = {classId, inputs};
    $('#rosterPreview').innerHTML = '<div class="hw-table-wrap"><table><thead><tr><th>学生姓名</th><th>OJ UID</th><th>OJ 用户名</th></tr></thead><tbody>' + preview.map(s => `<tr><td>${h(s.name)}</td><td>${h(s.uid)}</td><td>${h(s.uname)}</td></tr>`).join('') + '</tbody></table></div>';
    $('#confirmStudents').classList.remove('hidden');
  });
  $('#confirmStudents').onclick = action(async () => { if (!state.verified || state.verified.classId !== state.classId) throw new Error('请先核对学生用户名'); const c = await request('/classes/' + state.classId + '/students', {method: 'POST', body: JSON.stringify(state.verified.inputs)}); state.classes = state.classes.map(x => x.id === c.id ? c : x); $('#rosterInput').value = ''; resetPreview(); renderRoster(); ui.toast('学生已添加'); });
  $('#rosterTable').onclick = action(async e => {
    const student = e.target.closest('[data-student-uid]'); if (student) { await showStudent(student.dataset.studentUid); return; }
    const edit = e.target.closest('[data-edit-student]'), remove = e.target.closest('[data-remove-student]'), c = chosenClass();
    if (edit) { const s = c.students.find(x => x.id === edit.dataset.editStudent); $('#editingStudentId').value = s.id; $('#editingStudentName').value = s.name; $('#editingStudentUid').value = s.uid; $('#editStudentError').classList.add('hidden'); ui.openDialog($('#editStudentDialog'), edit); }
    if (remove) { const s = c.students.find(x => x.id === remove.dataset.removeStudent); if (!window.confirm(`将“${s.name}”移出班级？旧作业的名单和记录会保留。`)) return; const next = await request('/classes/' + c.id + '/students/' + s.id, {method: 'DELETE'}); state.classes = state.classes.map(x => x.id === c.id ? next : x); renderRoster(); }
  });
  $('#editStudentForm').onsubmit = action(async () => { const uid = Number($('#editingStudentUid').value); if (!Number.isSafeInteger(uid) || uid <= 0) throw new Error('请填写有效的 OJ UID'); const c = await request('/classes/' + state.classId + '/students/' + $('#editingStudentId').value, {method: 'PUT', body: JSON.stringify({name: $('#editingStudentName').value.trim(), uid})}); state.classes = state.classes.map(x => x.id === c.id ? c : x); ui.closeDialog($('#editStudentDialog')); renderRoster(); }, '#editStudentError');
  $('#settingsForm').onsubmit = action(async () => { const credential = $('#credential').value; try { state.settings = await request('/settings', {method: 'PUT', body: JSON.stringify({credential, domain: $('#defaultDomain').value.trim(), autoUpdate: $('#autoUpdate').value === 'true'})}); renderSettings(); renderBoard(); ui.toast('连接设置已保存'); } finally { $('#credential').value = ''; } });
  $('#verifyConnection').onclick = action(async () => { try { state.settings = await request('/settings/verify', {method: 'POST'}); ui.toast('OJ 身份验证成功'); } finally { state.settings = await request('/settings'); renderSettings(); } });
  $('#clearCredential').onclick = action(async () => { if (!window.confirm('清除 OJ 登录凭据？作业记录会保留，自动更新将无法查询 OJ。')) return; state.settings = await request('/settings/credential', {method: 'DELETE'}); renderSettings(); });
  function localBeijing(value) { const parts = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).format(value); return parts.replace(' ', 'T'); }
  function openAssignment(mode) {
    if (!state.classes.length) { tab('students'); $('#className').focus(); ui.toast('请先创建班级并添加学生'); return; }
    const source = mode === 'create' ? null : current();
    if (mode !== 'create' && !source) return;
    state.assignmentMode = mode; state.editingId = source?.id || null; state.editingRevision = source?.revision || 0;
    $('#createTitle').textContent = mode === 'edit' ? '编辑周作业' : mode === 'copy' ? '复制作业' : '新建周作业';
    $('#assignmentForm button[type="submit"]').textContent = mode === 'edit' ? '保存修改' : mode === 'copy' ? '创建副本' : '创建作业';
    options($('#newClass'), state.classes, source?.classId || state.classId, '请选择班级');
    $('#newClass').disabled = mode === 'edit';
    $('#newTitle').value = source ? source.title + (mode === 'copy' ? '（副本）' : '') : '';
    $('#newDomain').value = source?.domain || state.settings?.domain || 'system';
    $('#newProblems').value = source ? source.problems.map(p => p.pid).join(' ') : '';
    const offset = mode === 'copy' ? 7 * 86400000 : 0;
    const start = source ? new Date(Date.parse(source.startAt) + offset) : new Date();
    $('#newStart').value = localBeijing(start);
    $('#newEnd').value = source ? localBeijing(new Date(Date.parse(source.endAt) + offset)) : localBeijing(new Date(start.getTime() + 7 * 86400000));
    // Keep original seconds/milliseconds when a teacher only edits the title or problem list.
    state.assignmentTimes = source ? {startValue: $('#newStart').value, endValue: $('#newEnd').value,
      startAt: new Date(Date.parse(source.startAt) + offset).toISOString(), endAt: new Date(Date.parse(source.endAt) + offset).toISOString()} : null;
    $('#assignmentForm > p').textContent = mode === 'edit' ? '保留本次原学生名单。更换题目或题库域后，新增结果需重新更新。' : 'AC 即完成。使用目标班级当前名单创建独立作业，已有作业保持原样。';
    $('#createError').classList.add('hidden'); ui.openDialog($('#createDialog'));
  }
  $('#newAssignment').onclick = () => openAssignment('create');
  $('#editAssignment').onclick = () => openAssignment('edit');
  $('#copyAssignment').onclick = () => openAssignment('copy');
  $('#newStart').onchange = () => { if ($('#newStart').value && state.assignmentMode !== 'edit') $('#newEnd').value = localBeijing(new Date(Date.parse($('#newStart').value + '+08:00') + 7 * 86400000)); };
  $('#assignmentForm').onsubmit = action(async () => {
    const problemIds = [...new Set($('#newProblems').value.trim().split(/[\s,，;；]+/).filter(Boolean))];
    if (!problemIds.length || problemIds.length > 100) throw new Error('请填写 1～100 个题号');
    const times = state.assignmentTimes;
    const input = {classId: $('#newClass').value, title: $('#newTitle').value.trim(), domain: $('#newDomain').value.trim(),
      startAt: times && times.startValue === $('#newStart').value ? times.startAt : $('#newStart').value + ':00+08:00',
      endAt: times && times.endValue === $('#newEnd').value ? times.endAt : $('#newEnd').value + ':00+08:00', problemIds};
    const mode = state.assignmentMode;
    const path = mode === 'edit' ? '/assignments/' + state.editingId + '?revision=' + state.editingRevision : mode === 'copy' ? '/assignments/' + state.editingId + '/copy' : '/assignments';
    const a = await request(path, {method: mode === 'edit' ? 'PUT' : 'POST', body: JSON.stringify(input)});
    state.assignments = [a, ...state.assignments.filter(x => x.id !== a.id)]; state.classId = a.classId; state.assignmentId = a.id; state.selected = null; state.selectedProblem = null;
    ui.closeDialog($('#createDialog')); renderClasses(); tab('overview'); ui.toast(mode === 'edit' ? '作业已修改' : '作业已创建，点击手动更新读取完成情况');
  }, '#createError');
  $('#incompleteReport').onclick = action(async () => {
    const report = await request('/assignments/' + state.assignmentId + '/incomplete');
    $('#reportText').value = report.text; $('#copyReportMessage').textContent = `${report.incompleteStudents} 人有已确认待完成题目，${report.unknownStudents} 人数据待核验。`;
    ui.openDialog($('#reportDialog'), $('#incompleteReport'));
  });
  $('#copyReportText').onclick = action(async () => {
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText($('#reportText').value);
      else { $('#reportText').focus(); $('#reportText').select(); if (!document.execCommand('copy')) throw new Error('copy unavailable'); }
      $('#copyReportMessage').textContent = '清单已复制，可自行粘贴发送。';
    } catch (_) { $('#reportText').focus(); $('#reportText').select(); $('#copyReportMessage').textContent = '浏览器未允许自动复制，已选中文字，请按 Ctrl+C 或 ⌘C。'; }
  });
  $('#problemDetail').onclick = action(async e => {
    const student = e.target.closest('[data-student-uid]');
    if (student) await showStudent(student.dataset.studentUid);
    if (e.target.closest('[data-close-problem]')) { state.selectedProblem = null; $('#problemDetail').classList.add('hidden'); }
  });
  $('#studentOverviewBody').onclick = e => {
    const button = e.target.closest('[data-jump-assignment]'); if (!button) return;
    state.classId = button.dataset.jumpClass; state.assignmentId = button.dataset.jumpAssignment; state.selected = null; state.selectedProblem = null;
    ui.closeDialog($('#studentOverviewDialog')); renderClasses(); tab('overview');
  };
  $('#retryFailed').onclick = action(async () => watch(await request('/assignments/' + state.assignmentId + '/retry-failed', {method: 'POST'})));
  $('#fullRefresh').onclick = action(async () => watch(await request('/assignments/' + state.assignmentId + '/refresh?full=true', {method: 'POST'})));
  $('#refreshAssignment').onclick = action(async () => { const task = await request('/assignments/' + state.assignmentId + '/refresh', {method: 'POST'}); watch(task); });
  $('#archiveAssignment').onclick = action(async () => { const a = current(); if (!a.archived && !window.confirm('归档后保留当前结果，并停止更新这份作业。')) return; await request('/assignments/' + a.id + (a.archived ? '/restore' : '/archive'), {method: 'POST'}); await reloadAssignments(); });
  window.addEventListener('dataforge:auth-changed', () => load().catch(e => error(e.message)));
  load().catch(e => error(e.message));
})();
