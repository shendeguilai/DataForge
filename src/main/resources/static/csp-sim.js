(() => {
  'use strict';
  const API = '/api/tools/csp-sim';
  const teacher = document.body.dataset.cspRole === 'teacher';
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const stateLabel = state => ({DRAFT:'等待开考',OPEN:'进行中',CLOSED:'已收卷',QUEUED:'排队中',RUNNING:'评测中',DONE:'已完成',ERROR:'服务故障，待重试'}[state] || state);
  const verdictLabel = verdict => ({AC:'通过',PARTIAL:'部分通过',WA:'答案错误',CE:'编译错误',TLE:'时间超限',MLE:'内存超限',RE:'运行错误',OLE:'输出超限',SYSTEM_ERROR:'评测服务故障',DIRECTORY_ERROR:'目录不符合要求',SOURCE_TOO_LARGE:'源码超过100KiB'}[verdict] || verdict || '等待评测');
  const time = value => value ? new Date(value).toLocaleString('zh-CN', {hour12:false,timeZone:'Asia/Shanghai'}) : '—';
  const bytes = value => value < 1024 ? `${value} B` : value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1048576).toFixed(1)} MB`;
  const displayPath = value => { const windows=workspace?.environment==='WINDOWS'||currentExam?.exam.environment==='WINDOWS'; if(!windows)return value; if(value==='/')return '此电脑'; const display=String(value).replace(/^\/([A-Z])(?=\/|$)/,'$1:').replaceAll('/','\\'); return /^[A-Z]:$/.test(display)?display+'\\':display; };
  const parent = path => path.slice(0, path.lastIndexOf('/')) || '/';
  const base = path => path.slice(path.lastIndexOf('/') + 1);
  let students = [], examList = [], currentExam = null, activeView = 'roster', activeBatch = null;
  const MOVE_TYPE = 'application/x-dataforge-csp-path';
  let draggedEntry = null;
  let workspace = null, session = null, folder = '/', selected = '', previewText = '', previewPath = '', serverOffset = 0;
  let working = false, polling = false;
  const dialog = $('#cspDialog');

  async function request(path, {method = 'GET', body, token, binary = false} = {}) {
    const headers = {};
    if (token) headers['X-CSP-Token'] = token;
    if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
    const response = await fetch(API + path, {method, headers, body: body instanceof FormData ? body : body !== undefined ? JSON.stringify(body) : undefined});
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      if (teacher && response.status === 401) location.href = '/tools.html?auth=login&next=' + encodeURIComponent('/csp-sim.html');
      throw new Error(error.error || error.message || `请求失败（${response.status}）`);
    }
    return binary ? response.blob() : response.json().catch(() => ({}));
  }
  function toast(message) {
    $('#cspToast').textContent = message; $('#cspToast').classList.add('show');
    clearTimeout(toast.timer); toast.timer = setTimeout(() => $('#cspToast').classList.remove('show'), 3600);
  }
  function badge(label, color = '') { return `<span class="badge ${color}">${esc(label)}</span>`; }
  function openDialog(title, content, footer = '') {
    dialog.innerHTML = `<div class="dialog-head"><h2>${esc(title)}</h2><button class="dialog-close" data-action="close-dialog" aria-label="关闭">×</button></div><div class="dialog-body">${content}</div>${footer ? `<div class="dialog-footer">${footer}</div>` : ''}`;
    if (!dialog.open) dialog.showModal();
  }
  function saveBlob(blob, filename) {
    const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function chooseFile(accept, callback, multiple = false, directory = false) {
    const input = document.createElement('input'); input.type = 'file'; input.accept = accept; input.multiple = multiple;
    if (directory) input.setAttribute('webkitdirectory', '');
    input.addEventListener('change', () => guarded(() => callback([...input.files]))); input.click();
  }
  async function guarded(action) {
    if (working) return;
    working = true;document.body.setAttribute('aria-busy','true');const saveState=$('#workspaceSaveState');if(saveState)saveState.textContent='正在处理，请等待…';
    try { await action(); } catch (error) { toast(error.message); const formError = $('.form-error', dialog); if (dialog.open && formError) formError.textContent = error.message; }
    finally { working = false;document.body.removeAttribute('aria-busy');const saved=$('#workspaceSaveState');if(saved&&workspace)saved.textContent=Object.values(workspace.entries).filter(e=>!e.directory).length+' 个文件 · 已保存至服务器'; }
  }
  async function loadTeacher() {
    [students, examList] = await Promise.all([request('/students'), request('/exams')]); renderSidebar();
    if (activeView === 'roster') renderRoster();
  }
  function renderSidebar() {
    $('#examList').innerHTML = examList.map(e => `<button class="nav-button ${currentExam?.id === e.id && activeView === 'exam' ? 'active' : ''}" data-action="open-exam" data-id="${e.id}">◫　${esc(e.name)}</button>`).join('');
    $('[data-action="roster"]').classList.toggle('active', activeView === 'roster');
  }
  function renderRoster() {
    $('#teacherMain').innerHTML = `<div class="page-heading"><div><span class="eyebrow">CLASSROOM ROSTER</span><h1>把学生准备好。</h1><p>维护长期名单，学生使用姓名和学号进入。每场考试与历次成绩都归属同一位学生。</p></div><div class="actions"><button class="button" data-action="import-roster">导入 CSV</button><button class="button primary" data-action="add-student">＋ 添加学生</button></div></div>
      <div class="metrics"><div class="metric"><span>学生档案</span><strong>${students.length}</strong></div><div class="metric"><span>班级</span><strong>${new Set(students.map(s=>s.className).filter(Boolean)).size}</strong></div><div class="metric"><span>我的考场</span><strong>${examList.length}</strong></div><div class="metric"><span>学生进入方式</span><strong style="font-size:16px">姓名 ＋ 学号</strong></div></div>
      <div class="card"><div class="card-head"><h2>学生名单</h2><div class="actions"><button class="text-button" data-action="roster-template">下载导入模板</button><a class="text-button" href="${API}/students/export">导出学生名单 ↗</a></div></div><div class="table-scroll"><table><thead><tr><th>姓名</th><th>班级</th><th>学号</th><th>状态</th><th>管理</th></tr></thead><tbody>${students.map(s=>`<tr><td><strong>${esc(s.name)}</strong></td><td>${esc(s.className)||'—'}</td><td>${esc(s.studentNumber)}</td><td>${badge(s.enabled?'可进入':'已停用',s.enabled?'green':'')}</td><td><button class="text-button" data-action="edit-student" data-id="${s.id}">编辑</button><button class="text-button" data-action="revoke-sessions" data-id="${s.id}">退出所有设备</button></td></tr>`).join('')}</tbody></table>${students.length?'': '<div class="empty">先添加学生或导入名单，再创建第一场模拟考。</div>'}</div></div>
      <div class="notice">CSV 使用 UTF-8 编码，包含“姓名、学号”，可选“班级”。学号用于区分重名学生。学号按文本保存，前面的 0 会保留。</div>`;
  }
  function studentForm(s = null) {
    openDialog(s ? '编辑学生档案' : '添加学生', `<form id="studentForm" data-id="${s?.id || ''}"><div class="form-grid"><label>姓名<input name="name" required maxlength="50" value="${esc(s?.name)}"></label><label>班级<input name="className" maxlength="80" value="${esc(s?.className)}"></label><label>学号<input name="studentNumber" required maxlength="50" value="${esc(s?.studentNumber)}"></label>${s?`<label>档案状态<select name="enabled"><option value="true" ${s.enabled?'selected':''}>可进入</option><option value="false" ${!s.enabled?'selected':''}>停用</option></select></label>`:''}</div><p class="form-error" role="alert"></p></form>`, '<button class="button" data-action="close-dialog">取消</button><button class="button primary" type="submit" form="studentForm">保存学生</button>');
  }
  function taskForm(index) {
    const directory = 'task' + index;
    return `<section class="task-form" data-task-row><div class="actions"><h3>题目 ${index}</h3><button class="text-button" type="button" data-action="remove-problem" style="margin-left:auto">移除</button></div><div class="form-grid"><label>题目名称<input name="taskName" required value="题目 ${index}" maxlength="80"></label><label>题目目录<input name="directory" required value="${directory}" maxlength="150"></label><label>源码文件名<input name="sourceName" required value="${directory}.cpp" maxlength="150"></label><label>满分<input name="maxScore" type="number" min="1" max="1000" value="100" required></label><label>时间限制（毫秒）<input name="timeLimitMs" type="number" min="100" max="30000" value="1000" required></label><label>内存限制（MB）<input name="memoryMb" type="number" min="16" max="1024" value="256" required></label><label>输入输出方式<select name="ioMode"><option value="FILE">指定文件读写</option><option value="STDIO">标准输入输出</option></select></label><label>输入文件名<input name="inputName" value="${directory}.in"></label><label>输出文件名<input name="outputName" value="${directory}.out"></label></div><label>题面 / 操作要求<textarea name="statement" maxlength="100000" placeholder="粘贴题目说明，或写明学生应遵守的操作要求。"></textarea></label></section>`;
  }
  function createExamDialog() {
    const eligible = students.filter(s=>s.enabled);
    if (!eligible.length) { toast('请先添加学生'); return; }
    openDialog('新建 CSP 模拟考场', `<form id="examForm"><div class="form-grid"><label class="span-2">考场名称<input name="name" value="CSP 复赛目录训练" maxlength="80" required></label><label>文件环境<select name="environment"><option value="LINUX">Linux 文件管理</option><option value="WINDOWS">Windows 文件管理</option></select></label><label>训练模式<select name="mode"><option value="TEACHING">教学练习 · 可检查目录</option><option value="EXAM">模拟考试 · 考后检查目录</option></select></label><label>组别<select name="group"><option value="J">CSP-J</option><option value="S">CSP-S</option></select></label><label>时长（分钟）<input name="durationMinutes" type="number" min="1" max="600" value="210" required></label><label>地区代码<input name="regionCode" value="GD" required minlength="2" maxlength="10" pattern="[A-Za-z]{2,10}" title="广东为 GD，使用英文字母地区代码"></label><label>指定答案根目录<input name="rootPath" value="/home/noi/Desktop" required></label><label>文件夹规则预设<select name="folderPreset"><option value="{examNumber}">地区-组别学号 · GD-J10002</option><option value="{region}-{studentNumber}">地区代码-名单学号 · GD-001</option><option value="custom">自定义规则</option></select></label><label>考生文件夹命名规则<input name="folderPattern" value="{examNumber}" required></label></div><small>默认在 Windows 的 D 盘或 Linux 桌面创建考生文件夹，再建立题目目录。模拟准考证号按“地区代码-组别学号”生成：学号 10002 在 J 组为 GD-J10002，在 S 组为 GD-S10002，学号前导零保留。规则支持 {examNumber}、{region}、{name}、{studentNumber}，也可逐人自定义。</small><h3 class="form-section">选择学生 <small>最多 60 人</small></h3><div class="student-picks">${eligible.map((s,i)=>`<div class="student-pick" data-student-pick data-id="${s.id}"><input type="checkbox" name="selected" ${i<60?'checked':''} aria-label="选择${esc(s.name)}"><span>${esc(s.name)} <small>${esc(s.className)} · ${esc(s.studentNumber)}</small></span><input type="text" name="examNumber" data-student-number="${esc(s.studentNumber)}" data-generated="GD-J${esc(s.studentNumber)}" value="GD-J${esc(s.studentNumber)}" aria-label="模拟准考证号" title="模拟准考证号"><input type="text" name="folderName" placeholder="按规则生成" aria-label="自定义答案文件夹名" title="留空时按上方规则生成"></div>`).join('')}</div><h3 class="form-section">题目与提交规则</h3><div id="taskForms">${[1,2,3,4].map(taskForm).join('')}</div><button class="button" type="button" data-action="add-problem">＋ 添加题目</button><p class="form-error" role="alert"></p></form>`, '<button class="button" data-action="close-dialog">取消</button><button class="button primary" type="submit" form="examForm">创建考场</button>');
  }
  async function openExam(id) {
    currentExam = await request('/exams/' + id); activeView = 'exam'; activeBatch = null; renderSidebar(); renderExam();
  }
  function renderExam() {
    const {exam:e,students:members,state,id,batches} = currentExam;
    const joined = members.filter(s=>s.joined).length, submitted = members.filter(s=>s.latestSubmission).length;
    const joinUrl = location.origin + '/csp-sim-student.html?exam=' + encodeURIComponent(e.joinCode);
    $('#teacherMain').innerHTML = `<div class="page-heading"><div><span class="eyebrow">CSP ${esc(e.group)} · ${e.mode==='EXAM'?'MOCK EXAM':'TRAINING'}</span><h1>${esc(e.name)} ${badge(stateLabel(state),state==='OPEN'?'green':'blue')}</h1><p>${e.environment==='LINUX'?'Linux':'Windows'} 文件管理 · ${e.durationMinutes} 分钟 · ${e.problems.length} 题</p></div><div class="actions"><button class="button" data-action="copy-join">复制学生加入链接</button>${state==='DRAFT'?'<button class="button" data-action="update-admission">按学号更新考号和目录</button><button class="button primary" data-action="start-exam">开始考场</button>':state==='OPEN'?'<button class="button danger" data-action="close-exam">结束并统一收卷</button>':'<button class="button primary" data-action="grade">批量评测 / 重测</button>'}</div></div>
      <div class="metrics"><div class="metric"><span>考场学生</span><strong>${members.length}</strong></div><div class="metric"><span>已进入</span><strong>${joined}</strong></div><div class="metric"><span>已交卷</span><strong>${submitted}</strong></div><div class="metric"><span>截止时间</span><strong style="font-size:15px">${e.deadline?esc(time(e.deadline)):'开考后开始计时'}</strong></div></div>
      <div class="exam-rule"><span>考场编号 <code>${esc(e.joinCode)}</code></span><span>答案根目录 <code>${esc(displayPath(e.rootPath))}</code></span><span>文件夹规则 <code>${esc(e.folderPattern)}</code></span><span>同分并列 · 教师统一发布成绩</span></div>
      ${state==='DRAFT'?'<div class="notice">开考前学生可进入查看规则。学生需自己创建考生目录和题目目录，系统不会自动整理答案。</div>':state==='CLOSED'?'<div class="notice">答案已锁定。正式评测采用每位学生的最后一次交卷；未主动交卷者已自动收取文件。先确认所有题目的数据分值，再发起评测。</div>':''}
      <div class="card"><div class="card-head"><h2>题目与评测数据</h2><span class="muted" style="font-size:12px">隐藏数据仅教师可见</span></div><div class="card-body problem-grid">${e.problems.map((p,i)=>`<article class="problem-card"><h3>${i+1}. ${esc(p.name)} ${badge(p.cases.length?`${p.cases.length} 组 · ${p.dataConfirmed?'已确认':'待确认'}`:'未上传数据',p.dataConfirmed?'green':'orange')}</h3><code>${esc(p.directory)}/${esc(p.sourceName)}</code><p>${p.maxScore} 分 · ${p.timeLimitMs} ms · ${p.memoryMb} MB<br>${p.ioMode==='FILE'?`${esc(p.inputName)} → ${esc(p.outputName)}`:'标准输入输出'} · ${p.scoring==='SUBTASKS'?'子任务计分':'测试点计分'} · ${p.samples.length} 组公开样例</p><div class="actions"><button class="button small" data-action="upload-data" data-id="${p.id}">上传数据 ZIP</button><button class="button small" data-action="generated-data" data-id="${p.id}">本站数据</button><button class="button small" data-action="data-config" data-id="${p.id}" ${p.cases.length?'':'disabled'}>确认分值</button><button class="text-button" data-action="upload-samples" data-id="${p.id}">上传样例</button></div></article>`).join('')}</div></div>
      <div class="card"><div class="card-head"><h2>学生状态与交卷</h2><a class="text-button" href="${API}/exams/${id}/sources">导出源码包 ↗</a></div><div class="table-scroll"><table><thead><tr><th>学生</th><th>模拟准考证号</th><th>答案文件夹名</th><th>进入状态</th><th>文件</th><th>交卷</th><th>查看</th></tr></thead><tbody>${members.map(s=>`<tr><td><strong>${esc(s.name)}</strong><br><small>${esc(s.className)} · ${esc(s.studentNumber)}</small></td><td><code>${esc(s.examNumber)}</code></td><td>${esc(s.folderName)}</td><td>${badge(s.joined?'已进入':'未进入',s.joined?'green':'')}</td><td>${s.fileCount}</td><td>${badge(s.latestSubmission?'已保存交卷':'未交卷',s.latestSubmission?'blue':'')}</td><td><button class="text-button" data-action="submissions" data-id="${s.participationId}">文件与记录</button></td></tr>`).join('')}</tbody></table></div></div>
      <div class="card"><div class="card-head"><h2>评测批次</h2><small>重测保留旧结果，发布后学生才能看到成绩。</small></div><div class="table-scroll"><table><thead><tr><th>创建时间</th><th>类型</th><th>完成进度</th><th>成绩状态</th><th>操作</th></tr></thead><tbody>${batches.map(b=>`<tr><td>${esc(time(b.createdAt))}</td><td>${badge(b.review?'复盘':'正式',b.review?'orange':'blue')}</td><td>${b.done} / ${b.total}</td><td>${e.publishedBatch===b.id?badge('已发布','green'):'未发布'}</td><td><button class="text-button" data-action="view-batch" data-id="${b.id}">查看成绩</button></td></tr>`).join('')}</tbody></table>${batches.length?'':'<div class="empty">收卷后上传并确认数据，即可进行第一批评测。</div>'}</div></div>
      <small>学生加入地址：${esc(joinUrl)}</small>`;
  }
  function dataConfigDialog(id) {
    const p = currentExam.exam.problems.find(p=>p.id===id);
    if (!p.cases.length) return;
    openDialog(p.name + ' · 确认测试点和分值', `<form id="dataConfigForm" data-id="${id}" data-version="${esc(p.dataVersion)}"><div class="notice">逐点计分时每行填写该测试点分值；子任务计分时，同组每行填写相同的整组分值，全部通过才获得该组分数。</div><div class="form-grid"><label>计分方式<select name="scoring"><option value="POINTS" ${p.scoring==='POINTS'?'selected':''}>每个测试点独立得分</option><option value="SUBTASKS" ${p.scoring==='SUBTASKS'?'selected':''}>子任务全部通过得分</option></select></label><label>题目满分<input value="${p.maxScore}" disabled></label></div><div class="table-scroll" style="max-height:360px"><table><thead><tr><th>数据</th><th>分值</th><th>子任务名称（可选）</th></tr></thead><tbody>${p.cases.map(c=>`<tr data-case-row data-id="${esc(c.id)}"><td>${esc(c.id)}</td><td><input class="cell-input" name="score" type="number" min="0" step="0.0001" required value="${c.score}"></td><td><input class="cell-input" name="subtask" maxlength="50" value="${esc(c.subtask)}" placeholder="例如 subtask1"></td></tr>`).join('')}</tbody></table></div><p class="form-error" role="alert"></p></form>`, '<button class="button" data-action="close-dialog">取消</button><button class="button primary" form="dataConfigForm" type="submit">确认并保存计分规则</button>');
  }
  async function uploadData(id, samples) {
    chooseFile('.zip', async uploads => {
      if (!uploads.length) return;
      const form = new FormData(); form.append('file',uploads[0]);
      currentExam = await request(`/exams/${currentExam.id}/problems/${id}/data?samples=${samples}`,{method:'POST',body:form}); renderExam();
      toast(samples?'样例已公开给学生':'数据已导入，请确认分值'); if (!samples) dataConfigDialog(id);
    });
  }
  async function generatedDataDialog(problemId) {
    const jobs = (await fetch('/api/jobs').then(r=>r.json())).filter(j=>j.status==='COMPLETED');
    openDialog('选用本站生成的数据', `<form id="generatedForm" data-id="${problemId}"><label>已完成的数据任务<select name="jobId" required>${jobs.map(j=>`<option value="${j.id}">${esc(time(j.createdAt))} · ${j.request.caseCount} 组 · ${esc(j.request.statement.slice(0,50))}</option>`).join('')}</select></label>${jobs.length?'':'<p class="notice">暂无已完成的数据任务，请先到数据工坊生成数据，或上传外部ZIP。</p>'}<p class="form-error" role="alert"></p></form>`, `<button class="button" data-action="close-dialog">取消</button><button class="button primary" type="submit" form="generatedForm" ${jobs.length?'':'disabled'}>导入并配置分值</button>`);
  }
  async function batchDialog(id) {
    activeBatch = await request(`/exams/${currentExam.id}/batches/${id}`);
    renderBatchDialog();
  }
  function resultsTable(result, detail = false) {
    return `<div class="table-scroll"><table><thead><tr>${detail?'<th>学生</th>':''}<th>题目</th><th>分数</th><th>结果</th><th>目录规范</th><th>详情</th></tr></thead><tbody>${result.tasks.map(t=>`<tr>${detail?`<td>${esc(currentExam.students.find(s=>s.id===t.studentId)?.name||'学生')}</td>`:''}<td>${esc(t.problemName)}</td><td><strong>${t.state==='DONE'?t.result?.score??0:'—'}</strong></td><td>${badge(t.state==='DONE'?verdictLabel(t.result?.verdict):stateLabel(t.state),t.result?.verdict==='AC'?'green':t.state==='ERROR'?'red':'')}</td><td>${badge(t.directoryIssues.length?'存在问题':'符合要求',t.directoryIssues.length?'orange':'green')}</td><td><button class="text-button" data-action="result-detail" data-id="${t.id}">查看</button></td></tr>`).join('')}</tbody></table></div>`;
  }
  function leaderboardTable(result) {
    return `<div class="table-scroll"><table><thead><tr><th>排名</th><th>学生</th><th>班级</th><th>总分</th></tr></thead><tbody>${result.leaderboard.map(s=>`<tr><td>${s.rank??'—'}</td><td>${esc(s.name)}</td><td>${esc(s.className)||'—'}</td><td><strong>${s.score??'评测未完成'}</strong></td></tr>`).join('')}</tbody></table></div>`;
  }
  function renderBatchDialog() {
    const b=activeBatch;
    openDialog(b.review?'复盘评测结果':'正式评测结果', `<div class="notice">已完成 ${b.done} / ${b.total} 项。${b.review?'本次只用于复盘，不会改变正式成绩。':'系统故障可重试；全部完成后才能发布。'}</div>${resultsTable(b,true)}<h3 class="form-section">${b.review?'本次复盘得分':'班级排名'}</h3>${leaderboardTable(b)}`,
      `<button class="button" data-action="view-batch" data-id="${b.id}">刷新结果</button><button class="button" data-action="retry-batch" data-id="${b.id}">重试故障任务</button><a class="button" href="${API}/exams/${currentExam.id}/batches/${b.id}/export">导出成绩</a>${b.review?'':`<button class="button primary" data-action="publish-batch" data-id="${b.id}" ${b.done===b.total?'':'disabled'}>发布本批成绩</button>`}`);
  }
  async function submissionsDialog(participationId) {
    const s=currentExam.students.find(s=>s.participationId===participationId);
    const info=await request(`/exams/${currentExam.id}/students/${participationId}/submissions`);
    const latest=info.submissions.at(-1);
    const entries=latest?.submission.entries || info.entries;
    openDialog(s.name+' · 文件与交卷记录', `<div class="notice">${latest?'下方为最近一次交卷的文件。':'尚未交卷，下方为当前工作区。'}点击文件可只读查看。正式评测不会自动寻找错误位置的代码。</div><div class="table-scroll"><table><thead><tr><th>交卷时间</th><th>方式</th><th>文件数</th></tr></thead><tbody>${info.submissions.map(x=>`<tr><td>${esc(time(x.submission.submittedAt))}</td><td>${x.submission.automatic?'到时自动收卷':'学生主动交卷'}</td><td>${Object.values(x.submission.entries).filter(e=>!e.directory).length}</td></tr>`).join('')}</tbody></table></div><h3 class="form-section">文件清单</h3><div class="file-path-list">${Object.entries(entries).filter(([,e])=>!e.directory).map(([p,e])=>`<button data-action="teacher-file" data-id="${participationId}" data-submission="${latest?.id || ''}" data-path="${esc(p)}">${esc(displayPath(p))} <small>· ${bytes(e.bytes)}</small></button>`).join('') || '<p class="empty">暂无文件</p>'}</div>${currentExam.state==='CLOSED' && latest?`<form id="reviewForm" data-id="${participationId}"><h3 class="form-section">选择错误位置的代码进行复盘</h3><div class="form-grid"><label>对应题目<select name="problemId">${currentExam.exam.problems.map(p=>`<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></label><label>交卷中的代码<select name="sourcePath" required>${Object.entries(entries).filter(([path,e])=>!e.directory && path.endsWith('.cpp')).map(([path])=>`<option value="${esc(path)}">${esc(displayPath(path))}</option>`).join('')}</select></label></div><p class="form-error"></p><button class="button" type="submit">只评测所选代码用于复盘</button></form>`:''}`);
  }

  function sessionKey(exam) { return 'dataforge-csp-session:' + exam; }
  async function studentRefresh(render = true) {
    if (!session) return;
    const firstLoad = workspace === null;
    workspace=await request('/student/'+session.participationId,{token:session.token});
    serverOffset=new Date(workspace.serverTime).getTime()-Date.now();
    if (firstLoad || !workspace.entries[folder]?.directory) folder=workspace.rootPath;
    if (selected && !workspace.entries[selected]) { selected=''; previewText=''; previewPath=''; }
    if (render) renderDesktop();
  }
  function renderDesktop() {
    $('#studentLogout').hidden=false;
    const w=workspace, editable=w.state==='OPEN', canUpload=editable&&!(w.environment==='WINDOWS'&&folder==='/');
    const dirs=Object.keys(w.entries).filter(p=>w.entries[p].directory).sort((a,b)=>a.localeCompare(b));
    const children=Object.entries(w.entries).filter(([path])=>path!=='/' && parent(path)===folder).sort(([a,x],[b,y])=>Number(y.directory)-Number(x.directory)||a.localeCompare(b));
    const count=Object.values(w.entries).filter(e=>!e.directory).length;
    $('#studentMain').innerHTML=`<div class="desktop-main"><div class="desktop-heading"><div><h1>${esc(w.name)} ${badge(stateLabel(w.state),editable?'green':'blue')}</h1><p>${esc(w.student.name)} · 准考证号 ${esc(w.examNumber)} · ${w.environment==='LINUX'?'Linux':'Windows'} · ${w.mode==='TEACHING'?'教学练习':'模拟考试'}</p></div><div><div class="clock-label">${w.state==='OPEN'?'距离截止':'考场状态'}</div><div class="clock" id="examClock"></div></div></div>
      ${w.state==='DRAFT'?'<div class="notice">考场尚未开始。先阅读题目要求；老师开考后可以创建文件夹和上传代码。</div>':w.state==='CLOSED'?'<div class="notice">考试已结束，文件已锁定。老师发布后，下方将显示分数和排名。</div>':''}
      <div class="desktop-window"><div class="window-title"><span>▣　CSP 文件管理器</span><span>${badge('文件内容只读')}　${badge(w.environment==='LINUX'?'Linux':'Windows','blue')}</span></div><div class="toolbar"><button class="button" data-action="mkdir" ${canUpload?'':'disabled'}>📁 新建文件夹</button><button class="button" data-action="upload-files" ${canUpload?'':'disabled'}>↑ 上传文件</button><button class="button" data-action="upload-folder" ${canUpload?'':'disabled'}>↑ 上传文件夹</button><button class="button" data-action="rename" ${editable&&selected?'':'disabled'}>重命名</button><button class="button" data-action="move" ${editable&&selected?'':'disabled'}>移动</button><button class="button" data-action="delete" ${editable&&selected?'':'disabled'}>删除</button><button class="button toolbar-end" data-action="refresh-student">↻ 刷新</button></div><div class="file-layout"><aside class="file-tree"><div class="tree-label">我的文件工作区</div>${dirs.map(p=>`<button class="tree-row ${folder===p?'active':''}" data-action="folder" data-path="${esc(p)}" data-drop-directory="${esc(p)}" draggable="${movable(p)}" style="padding-left:${10+Math.min(5,p.split('/').length-2)*10}px"><span class="folder-icon">📁</span>${esc(p==='/'?(w.environment==='WINDOWS'?'此电脑':'文件系统'):p.endsWith('/Desktop')?'桌面':/^\/[A-Z]$/.test(p)?base(p)+':':base(p))}</button>`).join('')}</aside><section class="file-panel" data-drop-directory="${esc(folder)}"><div class="path-bar"><button class="text-button" data-action="parent-folder" data-drop-directory="${esc(parent(folder))}" ${folder==='/'?'disabled':''}>↑</button><span>${esc(displayPath(folder))}</span></div><div class="drop-zone" id="dropZone" data-drop-directory="${esc(folder)}">${editable?'拖入本机文件上传 · 拖动已有文件到目标文件夹可移动':'文件已锁定，可以查看内容'}<small>当前目录：${esc(displayPath(folder))} · 上传成功后显示“已保存”</small></div><div class="table-scroll"><table class="files-table"><thead><tr><th>名称</th><th>大小</th><th>修改时间</th></tr></thead><tbody>${children.map(([path,e])=>`<tr tabindex="0" data-file-path="${esc(path)}" draggable="${movable(path)}" ${e.directory?`data-drop-directory="${esc(path)}"`:""} class="${selected===path?'selected':''}"><td><span class="filename">${e.directory?'<span class="folder-icon">📁</span>':`<span class="file-icon">${esc(base(path).split('.').at(-1).slice(0,3).toUpperCase())}</span>`}${esc(base(path))}</span></td><td class="no-wrap">${e.directory?'—':bytes(e.bytes)}</td><td class="no-wrap"><small>${esc(new Date(e.modifiedAt).toLocaleTimeString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'}))}</small></td></tr>`).join('')}</tbody></table>${children.length?'':'<div class="empty">这个目录还是空的。<br>按题目要求创建文件夹，并上传源代码。</div>'}</div></section><aside class="preview-panel"><div class="preview-title"><span>文件内容预览</span><span class="badge">只读</span></div><div id="previewBody">${previewPath&&previewPath===selected?`<p class="muted" style="font-size:12px;overflow-wrap:anywhere">${esc(base(selected))}</p><pre class="code-preview">${esc(previewText)}</pre>`:'<div class="preview-placeholder">选择文件，在这里查看内容。<br>代码在本机编辑后重新上传。</div>'}</div></aside></div><div class="status-bar"><span>当前位置：${esc(displayPath(folder))}</span><span id="workspaceSaveState">${count} 个文件 · 已保存至服务器</span></div></div>
      <div class="task-strip">${w.problems.map(p=>`<button class="task-chip" data-action="student-problem" data-id="${p.id}"><strong>${esc(p.name)}</strong>${esc(p.directory)}/${esc(p.sourceName)} · ${p.maxScore} 分</button>`).join('')}</div><div class="card"><div class="card-body"><div class="exam-rule"><span>考场编号 <code>${esc(w.examCode)}</code></span><span>指定答案根目录 <code>${esc(displayPath(w.rootPath))}</code></span><span>你的答案文件夹名 <code>${esc(w.folderName)}</code></span></div><div class="actions">${w.mode==='TEACHING'||w.state==='CLOSED'?'<button class="button" data-action="check-directory">检查目录规范</button>':''}<button class="button primary" data-action="submit" ${editable?'':'disabled'}>${w.latestSubmission?'重新交卷':'保存交卷'}</button><button class="text-button" data-action="student-history">历次成绩</button><span class="muted" style="font-size:12px;margin-left:auto">${w.latestSubmission?'已保存交卷，截止前可重新提交':'尚未交卷；到时自动收取已保存文件'}</span></div></div></div>
      ${w.results?`<div class="card"><div class="card-head"><h2>我的成绩</h2>${badge('老师已发布','green')}</div>${resultsTable(w.results)}<div class="card-head"><h2>班级排名</h2><small>同分并列</small></div>${leaderboardTable(w.results)}</div>`:''}</div>`;
    updateClock(); bindDropZone();
  }
  function updateClock() {
    const el=$('#examClock'); if (!el||!workspace) return;
    if (workspace.state!=='OPEN') { el.textContent=stateLabel(workspace.state); el.style.fontSize='17px'; return; }
    const remaining=Math.max(0,Math.ceil((new Date(workspace.deadline).getTime()-Date.now()-serverOffset)/1000));
    el.textContent=[Math.floor(remaining/3600),Math.floor(remaining%3600/60),remaining%60].map(n=>String(n).padStart(2,'0')).join(':');
    el.style.color=remaining<300?'#c04d4d':'';
  }
  async function selectFile(path) {
    selected=path; previewPath=''; previewText='';
    if (workspace.entries[path].directory) {
      $$('[data-file-path]').forEach(row=>row.classList.toggle('selected',row.dataset.filePath===path));
      ['rename','move','delete'].forEach(action=>$(`[data-action="${action}"]`).disabled=workspace.state!=='OPEN');
      $('#previewBody').innerHTML='<div class="preview-placeholder">已选择文件夹。双击打开，或使用目录树进入。</div>';
      return;
    }
    if (!workspace.entries[path].directory) {
      const blob=await request(`/student/${session.participationId}/file?path=${encodeURIComponent(path)}`,{token:session.token,binary:true});
      previewText=/\.(cpp|in|out|ans|txt|md|h|csv)$/i.test(path)?await blob.text():'此文件不支持文本预览。'; previewPath=path;
    }
    renderDesktop();
  }
  async function studentOperation(action,path,target) {
    workspace=await request(`/student/${session.participationId}/files`,{method:'POST',token:session.token,body:{action,path,target,revision:workspace.revision}}); selected=''; previewPath=''; renderDesktop(); toast('已保存');
  }
  async function uploadStudentFiles(files) {
    if (!files.length) return;
    if (files.length>200||files.reduce((n,f)=>n+f.size,0)>25*1048576) throw new Error('一次最多上传200个文件、25MB；可分批上传');
    const target=folder, revision=workspace.revision, names=files.map(f=>f.webkitRelativePath||f.relativeName||f.name);
    const conflict=names.some(name=>Object.keys(workspace.entries).some(path=>workspace.environment==='WINDOWS'?path.toLowerCase()===(target+'/'+name).toLowerCase():path===target+'/'+name));
    const replace=conflict && confirm('当前目录存在同名文件，是否覆盖？覆盖后的文件需要重新交卷才会进入最终答案。');
    if (conflict&&!replace) return;
    const data=new FormData();data.append('path',target);data.append('revision',revision);data.append('replace',replace);
    files.forEach((f,i)=>{data.append('paths',names[i]);data.append('files',f,f.name);});
    workspace=await request(`/student/${session.participationId}/upload`,{method:'POST',token:session.token,body:data});renderDesktop();toast(`已保存 ${files.length} 个文件`);
  }
  function bindDropZone() {
    const zone=$('#dropZone'); if (!zone) return;
    zone.addEventListener('dragover',e=>{if([...e.dataTransfer.types].includes(MOVE_TYPE))return;e.preventDefault();zone.classList.add('dragover');});zone.addEventListener('dragleave',()=>zone.classList.remove('dragover'));
    zone.addEventListener('drop',e=>{if([...e.dataTransfer.types].includes(MOVE_TYPE))return;e.preventDefault();zone.classList.remove('dragover');if(workspace.state!=='OPEN'||workspace.environment==='WINDOWS'&&folder==='/'){toast('请在开考后进入可上传的目标目录');return;}const items=[...e.dataTransfer.items];const fallback=[...e.dataTransfer.files];guarded(async()=>{
      const result=[];
      async function collect(entry,prefix='') {
        if (!entry) return;
        if (result.length>200) throw new Error('一次最多上传200个文件');
        if(entry.isFile){const file=await new Promise((resolve,reject)=>entry.file(resolve,reject));file.relativeName=prefix+file.name;result.push(file);}
        else if(entry.isDirectory){const reader=entry.createReader();let entries;do{entries=await new Promise((resolve,reject)=>reader.readEntries(resolve,reject));for(const child of entries)await collect(child,prefix+entry.name+'/');}while(entries.length);}
      }
      if(items.some(item=>item.webkitGetAsEntry))for(const item of items)await collect(item.webkitGetAsEntry?.());else result.push(...fallback);
      await uploadStudentFiles(result);
    });});
  }

  function movable(path) {
    return workspace?.state === 'OPEN' && path !== '/' && !(workspace.environment === 'WINDOWS' && /^\/[A-Z]$/.test(path));
  }
  function clearDragStyle() {
    $$('.move-dragover,.move-dragging').forEach(el=>el.classList.remove('move-dragover','move-dragging'));
  }
  function validDrop(directory) {
    if (!draggedEntry || workspace?.state !== 'OPEN' || draggedEntry.participationId !== session?.participationId || !workspace.entries[directory]?.directory) return false;
    if (workspace.environment === 'WINDOWS' && directory === '/') return false;
    const source=workspace.environment==='WINDOWS'?draggedEntry.path.toLowerCase():draggedEntry.path;
    const destination=workspace.environment==='WINDOWS'?directory.toLowerCase():directory;
    return destination !== source && !destination.startsWith(source+'/');
  }
  document.addEventListener('dragstart',event=>{
    const source=event.target.closest('[draggable="true"]');
    const path=source?.dataset.filePath || source?.dataset.path;
    if (!path || !movable(path) || working) {if(source)event.preventDefault();return;}
    draggedEntry={path,participationId:session.participationId};
    event.dataTransfer.setData(MOVE_TYPE,path);event.dataTransfer.effectAllowed='move';
    source.classList.add('move-dragging');
  });
  document.addEventListener('dragover',event=>{
    if (![...event.dataTransfer.types].includes(MOVE_TYPE)) return;
    event.preventDefault();
    const target=event.target.closest('[data-drop-directory]');
    $$('.move-dragover').forEach(el=>el.classList.remove('move-dragover'));
    const valid=target && validDrop(target.dataset.dropDirectory);
    event.dataTransfer.dropEffect=valid?'move':'none';
    if(valid)target.classList.add('move-dragover');
  });
  document.addEventListener('dragleave',event=>{
    const target=event.target.closest('[data-drop-directory]');
    if(target && !target.contains(event.relatedTarget))target.classList.remove('move-dragover');
  });
  document.addEventListener('drop',event=>{
    if (![...event.dataTransfer.types].includes(MOVE_TYPE)) return;
    event.preventDefault();
    const target=event.target.closest('[data-drop-directory]'),entry=draggedEntry;
    const valid=target && validDrop(target.dataset.dropDirectory) && event.dataTransfer.getData(MOVE_TYPE)===entry?.path;
    draggedEntry=null;clearDragStyle();
    if(!valid){toast('不能移到这里，请选择其他文件夹');return;}
    const directory=target.dataset.dropDirectory,destination=(directory==='/'?'':directory)+'/'+base(entry.path);
    if(destination===entry.path){toast('文件已经在这个目录中');return;}
    guarded(async()=>{await studentOperation('move',entry.path,destination);toast('已移动至 '+displayPath(directory));});
  });
  document.addEventListener('dragend',()=>{draggedEntry=null;clearDragStyle();});
  function studentProblem(id) {
    const p=workspace.problems.find(p=>p.id===id);
    openDialog(p.name, `<div class="notice">源码应保存到 ${esc(displayPath(workspace.rootPath+'/'+workspace.folderName+'/'+p.directory+'/'+p.sourceName))}<br>${p.ioMode==='FILE'?`输入文件 ${esc(p.inputName)}，输出文件 ${esc(p.outputName)}`:'使用标准输入输出'} · ${p.timeLimitMs} ms · ${p.memoryMb} MB</div>${p.statement?`<pre class="detail-pre">${esc(p.statement)}</pre>`:'<p class="muted">请按老师提供的题面作答。</p>'}${p.samples.map((s,i)=>`<h3 class="form-section">样例 ${i+1}</h3><div class="sample-grid"><div><small>输入</small><pre>${esc(s.input)}</pre></div><div><small>输出</small><pre>${esc(s.answer)}</pre></div></div>`).join('')}`);
  }
  async function resultDetail(id) {
    const task=(teacher?activeBatch:workspace.results)?.tasks.find(t=>t.id===id);
    if (!task) return;
    openDialog(task.problemName+' · 评测详情', `<div class="notice">源码位置：${esc(displayPath(task.sourcePath))}<br>状态：${esc(task.state==='DONE'?verdictLabel(task.result?.verdict):stateLabel(task.state))} · 得分：${task.result?.score ?? '—'}</div>${task.directoryIssues.length?`<div class="notice warning">${task.directoryIssues.map(esc).join('<br>')}</div>`:''}${task.result?.message?`<pre class="detail-pre">${esc(task.result.message)}</pre>`:''}<div class="table-scroll"><table><thead><tr><th>测试点</th><th>结果</th><th>时间</th><th>内存</th></tr></thead><tbody>${(task.result?.cases||[]).map(c=>`<tr><td>${esc(c.id)}</td><td>${esc(verdictLabel(c.verdict))}</td><td>${c.runtimeMs} ms</td><td>${bytes(c.memoryBytes)}</td></tr>`).join('')}</tbody></table></div><p class="muted" style="font-size:11px;overflow-wrap:anywhere">交卷：${esc(task.submissionId)}<br>数据版本：${esc(task.dataVersion)}<br>${esc(task.result?.environment||'')}</p>`);
  }

  document.addEventListener('click', event => {
    const fileRow=event.target.closest('[data-file-path]');
    if(fileRow){if(draggedEntry)return;guarded(()=>selectFile(fileRow.dataset.filePath));return;}
    const button=event.target.closest('[data-action]'); if(!button||button.disabled)return;
    const {action,id,path}=button.dataset;
    if(action==='close-dialog'){dialog.close();return;}
    guarded(async()=>{
      if(action==='roster'){activeView='roster';currentExam=null;await loadTeacher();}
      else if(action==='add-student')studentForm();
      else if(action==='edit-student')studentForm(students.find(s=>s.id===id));
      else if(action==='revoke-sessions'){if(confirm('让这位学生退出所有设备？学生仍可使用姓名和学号重新进入。')){await request(`/students/${id}/revoke-sessions`,{method:'POST'});await loadTeacher();toast('已退出所有设备');}}
      else if(action==='import-roster')chooseFile('.csv',async f=>{if(!f.length)return;const data=new FormData();data.append('file',f[0]);await request('/students/import',{method:'POST',body:data});await loadTeacher();toast('名单已导入');});
      else if(action==='roster-template')saveBlob(new Blob(['\uFEFF姓名,班级,学号\r\n张三,竞赛一班,001\r\n李四,竞赛一班,002\r\n'],{type:'text/csv;charset=utf-8'}),'学生名单模板.csv');
      else if(action==='create-exam')createExamDialog();
      else if(action==='open-exam')await openExam(id);
      else if(action==='add-problem'){if($$('[data-task-row]').length>=10)throw new Error('每场最多10题');$('#taskForms').insertAdjacentHTML('beforeend',taskForm($$('[data-task-row]').length+1));}
      else if(action==='remove-problem')button.closest('[data-task-row]').remove();
      else if(action==='copy-join'){await navigator.clipboard.writeText(location.origin+'/csp-sim-student.html?exam='+currentExam.exam.joinCode);toast('学生加入链接已复制');}
      else if(action==='update-admission'){currentExam=await request(`/exams/${currentExam.id}/admission-numbers`,{method:'POST'});renderExam();toast('已按学号更新考号和目录');}
      else if(action==='start-exam'){if(confirm('开始后学生可以操作文件，计时立即开始。是否开考？')){currentExam=await request(`/exams/${currentExam.id}/start`,{method:'POST'});renderExam();}}
      else if(action==='close-exam'){if(confirm('结束后锁定全部答案，未交卷者自动收取已保存文件。是否结束？')){currentExam=await request(`/exams/${currentExam.id}/close`,{method:'POST'});renderExam();}}
      else if(action==='upload-data')await uploadData(id,false);
      else if(action==='upload-samples')await uploadData(id,true);
      else if(action==='generated-data')await generatedDataDialog(id);
      else if(action==='data-config')dataConfigDialog(id);
      else if(action==='grade'){activeBatch=await request(`/exams/${currentExam.id}/grade`,{method:'POST',body:{}});currentExam=await request('/exams/'+currentExam.id);renderExam();renderBatchDialog();toast('评测批次已创建');}
      else if(action==='view-batch')await batchDialog(id);
      else if(action==='retry-batch'){activeBatch=await request(`/exams/${currentExam.id}/batches/${id}/retry`,{method:'POST'});renderBatchDialog();}
      else if(action==='publish-batch'){currentExam=await request(`/exams/${currentExam.id}/batches/${id}/publish`,{method:'POST'});dialog.close();renderExam();toast('成绩已发布，学生现在可以查看');}
      else if(action==='submissions')await submissionsDialog(id);
      else if(action==='teacher-file'){const blob=await request(`/exams/${currentExam.id}/students/${id}/file?submissionId=${encodeURIComponent(button.dataset.submission)}&path=${encodeURIComponent(path)}`,{binary:true});openDialog(displayPath(path),`<pre class="detail-pre">${esc(await blob.text())}</pre>`);}
      else if(action==='result-detail')await resultDetail(id);
      else if(action==='folder'){folder=path;selected='';previewPath='';renderDesktop();}
      else if(action==='parent-folder'){folder=parent(folder);selected='';previewPath='';renderDesktop();}
      else if(action==='mkdir'){const name=prompt('新建文件夹名称');if(name)await studentOperation('mkdir',(folder==='/'?'':folder)+'/'+name);}
      else if(action==='rename'){const name=prompt('新的文件或文件夹名称',base(selected));if(name)await studentOperation('move',selected,(parent(selected)==='/'?'':parent(selected))+'/'+name);}
      else if(action==='move'){const target=prompt('完整目标路径（包含文件或文件夹名称）',displayPath(selected));if(target)await studentOperation('move',selected,target);}
      else if(action==='delete'){if(confirm('删除所选项目及其全部内容？之前交卷的副本会保留。'))await studentOperation('delete',selected);}
      else if(action==='upload-files')chooseFile('',uploadStudentFiles,true);
      else if(action==='upload-folder')chooseFile('',uploadStudentFiles,true,true);
      else if(action==='refresh-student'){await studentRefresh();toast('已刷新');}
      else if(action==='check-directory'){const result=await request(`/student/${session.participationId}/check`,{token:session.token});openDialog('目录规范检查',result.problems.map(p=>`<div class="problem-card" style="margin-bottom:12px"><h3>${esc(p.name)} ${badge(p.issues.length?'需要调整':'符合要求',p.issues.length?'orange':'green')}</h3><small>规定路径：${esc(displayPath(p.expected))}</small>${p.issues.length?`<p>${p.issues.map(esc).join('<br>')}</p>`:''}</div>`).join(''));}
      else if(action==='submit'){if(confirm('保存当前文件为一次交卷。截止前可修改并重新交卷，最终采用最近一次交卷。')){workspace=await request(`/student/${session.participationId}/submit`,{method:'POST',token:session.token});renderDesktop();toast('交卷副本已保存');}}
      else if(action==='student-problem')studentProblem(id);
      else if(action==='student-history'){const history=await request(`/student/${session.participationId}/history`,{token:session.token});openDialog('我的历次训练成绩',`<table><thead><tr><th>考场</th><th>状态</th><th>总分</th><th>排名</th></tr></thead><tbody>${history.map(e=>{const own=e.results?.leaderboard.find(s=>s.id===workspace.student.id);return `<tr><td>${esc(e.name)}</td><td>${own?'已发布':stateLabel(e.state)}</td><td>${own?.score??'—'}</td><td>${own?.rank??'—'}</td></tr>`;}).join('')}</tbody></table>`);}
      else if(action==='logout-student'){try{await request(`/student/${session.participationId}/leave`,{method:'POST',token:session.token});}finally{localStorage.removeItem(sessionKey(workspace.examCode));localStorage.removeItem(sessionKey(workspace.examId));location.reload();}}
    });
  });
  document.addEventListener('dblclick',event=>{const row=event.target.closest('[data-file-path]');if(row&&workspace.entries[row.dataset.filePath].directory){folder=row.dataset.filePath;selected='';previewPath='';renderDesktop();}});
  document.addEventListener('keydown',event=>{if(event.key==='Enter'&&event.target.matches('[data-file-path]')){event.preventDefault();const path=event.target.dataset.filePath;if(workspace.entries[path].directory){folder=path;selected='';renderDesktop();}else guarded(()=>selectFile(path));}});
  function updateExamNumbers() {
    const form = $('#examForm'), region = $('[name="regionCode"]',form).value.trim().toUpperCase(), group = $('[name="group"]',form).value;
    $('[name="regionCode"]',form).value = region;
    $$('[name="examNumber"]',form).forEach(input=>{
      const generated = region+'-'+group+input.dataset.studentNumber;
      if (input.value === input.dataset.generated) input.value = generated;
      input.dataset.generated = generated;
    });
  }
  document.addEventListener('change',event=>{
    if(event.target.matches('#examForm [name="environment"]'))$('#examForm [name="rootPath"]').value=event.target.value==='LINUX'?'/home/noi/Desktop':'D:/';
    if(event.target.matches('#examForm [name="group"]')){$('#examForm [name="durationMinutes"]').value=event.target.value==='J'?210:240;updateExamNumbers();}
    if(event.target.matches('#examForm [name="regionCode"]'))updateExamNumbers();
    if(event.target.matches('#examForm [name="folderPreset"]')&&event.target.value!=='custom')$('#examForm [name="folderPattern"]').value=event.target.value;
    if(event.target.matches('#examForm [name="folderPattern"]')){const pattern=event.target.value;$('#examForm [name="folderPreset"]').value=['{examNumber}','{region}-{studentNumber}'].includes(pattern)?pattern:'custom';}
    if(event.target.matches('[data-task-row] [name="directory"]')){const row=event.target.closest('[data-task-row]');$('[name="sourceName"]',row).value=event.target.value+'.cpp';$('[name="inputName"]',row).value=event.target.value+'.in';$('[name="outputName"]',row).value=event.target.value+'.out';}
    if(event.target.matches('[data-task-row] [name="ioMode"]')){const row=event.target.closest('[data-task-row]');$('[name="inputName"]',row).disabled=event.target.value==='STDIO';$('[name="outputName"]',row).disabled=event.target.value==='STDIO';}
  });
  document.addEventListener('submit',event=>{
    const form=event.target;if(!['studentForm','examForm','joinForm','dataConfigForm','generatedForm','reviewForm'].includes(form.id))return;
    event.preventDefault();guarded(async()=>{
      const data=new FormData(form),value=name=>String(data.get(name)||'');
      if(form.id==='studentForm'){const student={name:value('name'),className:value('className'),studentNumber:value('studentNumber')};await request('/students'+(form.dataset.id?'/'+form.dataset.id:''),{method:form.dataset.id?'PUT':'POST',body:form.dataset.id?{student,enabled:value('enabled')==='true'}:student});dialog.close();await loadTeacher();toast('学生档案已保存');}
      else if(form.id==='examForm'){
        const exam={name:value('name'),environment:value('environment'),mode:value('mode'),group:value('group'),regionCode:value('regionCode'),durationMinutes:Number(value('durationMinutes')),rootPath:value('rootPath'),folderPattern:value('folderPattern'),problems:$$('[data-task-row]',form).map(row=>{const val=n=>$(`[name="${n}"]`,row).value;return {name:val('taskName'),directory:val('directory'),sourceName:val('sourceName'),maxScore:Number(val('maxScore')),timeLimitMs:Number(val('timeLimitMs')),memoryMb:Number(val('memoryMb')),ioMode:val('ioMode'),inputName:val('inputName'),outputName:val('outputName'),statement:val('statement')};})};
        const assignments=$$('[data-student-pick]',form).filter(row=>$('[name="selected"]',row).checked).map(row=>({studentId:row.dataset.id,examNumber:$('[name="examNumber"]',row).value,folderName:$('[name="folderName"]',row).value}));
        currentExam=await request('/exams',{method:'POST',body:{exam,students:assignments}});activeView='exam';dialog.close();await loadTeacher();renderSidebar();renderExam();toast('考场已创建');
      }else if(form.id==='dataConfigForm'){const p=currentExam.exam.problems.find(p=>p.id===form.dataset.id);const cases=$$('[data-case-row]',form).map(row=>({id:row.dataset.id,score:Number($('[name="score"]',row).value),subtask:$('[name="subtask"]',row).value}));currentExam=await request(`/exams/${currentExam.id}/problems/${p.id}/data-config`,{method:'PUT',body:{dataVersion:form.dataset.version,scoring:value('scoring'),cases}});dialog.close();renderExam();toast('测试数据与分值已确认');}
      else if(form.id==='generatedForm'){const id=form.dataset.id;currentExam=await request(`/exams/${currentExam.id}/problems/${id}/generated-data`,{method:'POST',body:{jobId:value('jobId')}});renderExam();dataConfigDialog(id);}
      else if(form.id==='reviewForm'){activeBatch=await request(`/exams/${currentExam.id}/grade`,{method:'POST',body:{reviewPaths:{[form.dataset.id]:{[value('problemId')]:value('sourcePath')}}}});currentExam=await request('/exams/'+currentExam.id);renderExam();renderBatchDialog();}
      else if(form.id==='joinForm'){
        try{session=await request('/join/'+encodeURIComponent(value('examId')),{method:'POST',body:{name:value('name'),code:value('code')}});localStorage.setItem(sessionKey(session.examCode),JSON.stringify(session));folder='/';await studentRefresh();}
        catch(error){$('#joinError').textContent=error.message;throw error;}
      }
    });
  });
  async function initialize() {
    if(teacher){await loadTeacher();return;}
    const reference=new URLSearchParams(location.search).get('exam')||'';
    const examCode=reference && !/^[0-9]{6}$/.test(reference) ? (await request('/join/'+encodeURIComponent(reference))).examCode : reference;
    $('#joinForm [name="examId"]').value=examCode;
    try{session=JSON.parse(localStorage.getItem(sessionKey(examCode))||localStorage.getItem(sessionKey(reference))||'null');}catch{session=null;}
    if(session){try{await studentRefresh();localStorage.setItem(sessionKey(examCode),JSON.stringify(session));}catch(error){session=null;localStorage.removeItem(sessionKey(examCode));localStorage.removeItem(sessionKey(reference));toast(error.message);}}
  }
  initialize().catch(error=>{toast(error.message);const target=teacher?$('#teacherMain'):$('#joinError');target.textContent=error.message;});
  setInterval(updateClock,1000);
  setInterval(async()=>{
    if(working||polling||draggedEntry||document.hidden)return;polling=true;
    try{if(!teacher&&session&&workspace)await studentRefresh(!dialog.open);else if(teacher&&activeView==='exam'&&currentExam){currentExam=await request('/exams/'+currentExam.id);if(!dialog.open)renderExam();if(dialog.open&&activeBatch&&dialog.querySelector('[data-action="retry-batch"]')){activeBatch=await request(`/exams/${currentExam.id}/batches/${activeBatch.id}`);renderBatchDialog();}}}
    catch(error){toast(error.message);}finally{polling=false;}
  },5000);
})();
