(() => {
  const $ = s => document.querySelector(s);
  const h = value => String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let learning = null, busy = false, openAfterLogin = false;
  const labels = {QUEUED:'排队中',RUNNING:'执行中',OPEN:'进行中',CLOSED:'已收卷',FAILED:'失败，可重试',PARTIAL:'部分失败',WAITING_CONFIRMATION:'待确认',CANCELLED:'已取消'};
  async function get(url, options={}) {
    const response = await fetch(url, options);
    const body = await response.json().catch(()=>({}));
    if (!response.ok) {const error=new Error(body.error||`请求失败 (${response.status})`);error.status=response.status;throw error;}
    return body;
  }
  async function orderTools() {
    try {
      const order=await get('/api/tools/catalog');const grid=$('.portal-grid');
      for(const id of order.ids){const card=document.querySelector(`[data-tool-id="${CSS.escape(id)}"]`);if(card)grid.append(card);}
    } catch (_) { /* The default catalog remains usable while the server is unavailable. */ }
  }
  function list(title,rows) {
    return `<section><h3>${h(title)}</h3><ul>${rows.length?rows.join(''):'<li class="hint">暂无待办</li>'}</ul></section>`;
  }
  async function refresh() {
    if(busy)return;busy=true;
    try {
      const data=await get('/api/tools/workbench');$('#teacherWorkbench').classList.remove('hidden');$('#workbenchError').textContent='';
      $('#workbenchSummary').innerHTML=`<div><strong>${data.pendingCells}</strong><span>已确认待完成题次</span></div><div><strong>${data.unknownCells}</strong><span>待核验题次</span></div><div><strong>${data.exams.filter(e=>e.state==='OPEN').length}</strong><span>进行中考场</span></div>`;
      $('#workbenchTasks').innerHTML=list('班级作业',data.assignments.map(a=>`<li><a href="${h(a.href)}">${h(a.title)}</a><small>待完成 ${a.pending} · 待核验 ${a.unknown}</small></li>`))
        +list('考场与后台任务',[...data.exams.map(e=>`<li><a href="${h(e.href)}">${h(e.title)}</a><small>${h(labels[e.state]||e.state)}</small></li>`),...data.tasks.slice(0,12).map(t=>`<li><a href="${h(t.href)}">${h(t.kind)}</a><small>${h(labels[t.state]||t.state)}</small></li>`)])
        +list('连接状态',data.connections.map(c=>`<li><a href="${h(c.href)}">可达 OJ</a><small>${c.state==='CONNECTED'?'上次身份验证成功（更新时再次检查）':'需要检查连接或重新录入凭据'}</small></li>`));
    } catch(error) {
      if(error.status===401)$('#teacherWorkbench').classList.add('hidden');
      else $('#workbenchError').textContent='工作台暂时无法更新，保留上次数据。请稍后刷新。';
    } finally {busy=false;}
  }
  async function loadLearning() {
    try {
      learning=await get('/api/tools/learning');
      $('#learningBody').innerHTML='<p>按作业开始所在周统计；待核验单独列出，不判定为未完成。知识点标签由老师确认，考试记录仅在关联身份后显示。</p>'
        +learning.students.map(s=>`<details class="learning-student"><summary>${h(s.name)} · UID ${s.uid}${s.needsAttention?' · 最近两周均有已核验的未完成题目':''}</summary>
          <div class="table-wrap"><table><thead><tr><th>周起始日</th><th>确认完成 / 总题次</th><th>待核验</th><th>补交</th></tr></thead><tbody>${s.weeks.map(w=>`<tr><td>${h(w.week)}</td><td>${w.completed} / ${w.total}</td><td>${w.unknown}</td><td>${w.late}</td></tr>`).join('')}</tbody></table></div>
          <p>${s.tags.map(t=>`${h(t.tag)}：${t.completed}/${t.total}，待核验 ${t.unknown}`).join('；')||'暂无已标注题目的统计'}</p>
          <label>关联模拟考试学生<select data-link-uid="${s.uid}"><option value="">不关联</option>${learning.examStudents.map(e=>`<option value="${h(e.id)}" ${learning.preferences.links[s.uid]===e.id?'selected':''}>${h(e.name)} · ${h(e.studentNumber)}</option>`).join('')}</select></label>
          <ul>${s.exams.map(e=>`<li><a href="${h(e.href)}">${h(e.name)}</a>：${h(e.score)} / ${h(e.maximum)}</li>`).join('')||'<li>暂无已关联且已发布的考试成绩</li>'}</ul></details>`).join('')
        +(learning.students.length?'':'<p>添加班级和学生后，这里会显示学习记录。</p>')
        +`<details><summary>维护题目知识点标签</summary>${learning.problems.map(p=>`<label class="learning-tag">${h(p.title)}<input data-tag-key="${h(p.key)}" value="${h((learning.preferences.tags[p.key]||[]).join('，'))}" placeholder="用逗号分隔，例如：二分，前缀和"></label>`).join('')}</details><button type="button" class="primary-btn" id="saveLearning">保存学生关联与题目标签</button><p id="learningMessage" role="status"></p>`;
      $('#saveLearning').onclick=saveLearning;
    } catch(error){$('#learningBody').textContent=error.message;}
  }
  async function saveLearning() {
    const button=$('#saveLearning');button.disabled=true;
    try {
      const prefs=structuredClone(learning.preferences);prefs.links={};
      document.querySelectorAll('[data-link-uid]').forEach(select=>{if(select.value)prefs.links[select.dataset.linkUid]=select.value;});
      document.querySelectorAll('[data-tag-key]').forEach(input=>{const tags=[...new Set(input.value.split(/[,，]/).map(s=>s.trim()).filter(Boolean))];if(tags.length)prefs.tags[input.dataset.tagKey]=tags;else delete prefs.tags[input.dataset.tagKey];});
      learning.preferences=await get('/api/tools/learning/settings',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(prefs)});
      await loadLearning();$('#learningMessage').textContent='已保存，统计已更新。';
    } catch(error){$('#learningMessage').textContent=error.message;}finally{button.disabled=false;}
  }
  $('#refreshWorkbench').onclick=refresh;
  function openGenerator(job,from) {
    const params=new URLSearchParams({embedded:'1'});
    if(job)params.set('job',job);if(from)params.set('from',from);
    $('#generatorFrame').src='/data-generator.html?'+params;
    $('#generatorTool').classList.remove('hidden');
    $('#generatorTool').scrollIntoView({behavior:'smooth',block:'start'});
  }
  $('#openGenerator').onclick=()=>{
    if(window.DataForgePortal?.state==='authenticated')openGenerator();
    else if(window.DataForgePortal?.state==='anonymous'){openAfterLogin=true;window.showPortalAuth('login',$('#openGenerator'));}
    else window.DataForgeUI.toast('暂时无法确认登录状态，请检查网络后刷新重试。',{variant:'error'});
  };
  $('#closeGenerator').onclick=()=>{$('#generatorTool').classList.add('hidden');$('#generatorFrame').removeAttribute('src');$('#openGenerator').focus();};
  $('#learningSection').addEventListener('toggle',()=>{if($('#learningSection').open&&!learning)loadLearning();});
  window.addEventListener('dataforge:auth-changed',()=>{learning=null;refresh();if(openAfterLogin){openAfterLogin=false;openGenerator();}});
  setInterval(()=>{if(!document.hidden)refresh();},60000);
  orderTools();refresh();
  const params=new URLSearchParams(location.search);
  if(params.get('tool')==='generator')openGenerator(params.get('job'),params.get('from'));
})();
