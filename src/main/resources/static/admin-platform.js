(() => {
  const $=s=>document.querySelector(s);
  const names={homework:'可达 OJ 作业跟踪','csp-sim':'CSP 复赛模拟',atcoder:'AtCoder 成绩统计',leaderboard:'AtCoder 实时排行榜',typing:'双人打字 PK',quiz:'课堂问题抢答',paper:'CSP Paper Studio','data-generator':'算法数据工坊',username:'用户名 / 账号查询工具',more:'更多竞赛小工具'};
  let order=null;
  const h=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  async function api(url,options={}){const r=await fetch(url,options);const b=await r.json().catch(()=>({}));if(!r.ok)throw new Error(b.error||`请求失败 (${r.status})`);return b;}
  function renderOrder(){
    $('#toolOrderList').replaceChildren(...order.ids.map((id,index)=>{
      const row=document.createElement('li');row.style.padding='12px';row.append(document.createTextNode(names[id]+' '));
      for(const [label,delta]of[['上移',-1],['下移',1]]){const b=document.createElement('button');b.type='button';b.className='ghost-btn';b.textContent=label;b.setAttribute('aria-label',names[id]+label);b.disabled=index+delta<0||index+delta>=order.ids.length;b.onclick=()=>{[order.ids[index],order.ids[index+delta]]=[order.ids[index+delta],order.ids[index]];renderOrder();$('#toolOrderMessage').textContent='顺序已调整，点击保存后生效。';};row.append(b);}return row;
    }));
  }
  async function loadOrder(){try{order=await api('/api/tools/catalog');renderOrder();}catch(e){$('#toolOrderMessage').textContent=e.message;}}
  $('#saveToolOrder').onclick=async()=>{if(!order)return;const b=$('#saveToolOrder');b.disabled=true;try{order=await api('/api/admin/tool-order',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(order)});renderOrder();$('#toolOrderMessage').textContent='已保存，实用工具列表刷新后显示新顺序。';}catch(e){$('#toolOrderMessage').textContent=e.message;}finally{b.disabled=false;}};
  async function storage(){try{const s=await api('/api/admin/operations');$('#storageStatus').innerHTML=`<p>磁盘可用：${h(s.freeBytesText)} · 运行文件：${h(s.runtimeBytesText)}</p><p>数据生成队列：${h(s.queued)} · 执行中：${h(s.running)} · 失败任务：${h(s.failed)}</p><p>自动临时文件清理：${s.retentionDays>0?h(s.retentionDays)+' 天':'未启用'}（完成的数据包和学生文件不在自动清理范围）</p><ul>${s.alerts.map(a=>`<li>${h(a)}</li>`).join('')||'<li>暂无运行告警</li>'}</ul>`;}catch(e){$('#storageStatus').textContent=e.message;}}
  $('#refreshStorage').onclick=storage;
  $('#cleanupStorage').onclick=async()=>{try{const p=await api('/api/admin/operations/cleanup');$('#storageCleanup').textContent=`可清理 ${p.count} 个历史临时文件，共 ${p.bytesText}。`;if(p.count){const b=document.createElement('button');b.type='button';b.className='ghost-btn';b.textContent='确认清理这些临时文件';b.onclick=async()=>{b.disabled=true;try{await api('/api/admin/operations/cleanup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:p.token})});await storage();$('#storageCleanup').textContent='清理完成，数据包和业务记录已保留。';}catch(e){$('#storageCleanup').textContent=e.message;}};$('#storageCleanup').append(b);}}catch(e){$('#storageCleanup').textContent=e.message;}};
  document.querySelector('[data-panel="tools"]').addEventListener('click',loadOrder);
  document.querySelector('[data-panel="storage"]').addEventListener('click',storage);
})();
