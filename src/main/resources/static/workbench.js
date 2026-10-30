(() => {
  const $ = s => document.querySelector(s);
  let openAfterLogin = false;
  async function orderTools() {
    try {
      const response=await fetch('/api/tools/catalog');if(!response.ok)return;
      const order=await response.json(),grid=$('.portal-grid');
      for(const id of order.ids){const card=document.querySelector('[data-tool-id="'+CSS.escape(id)+'"]');if(card)grid.append(card);}
    } catch (_) { /* Retain default order when offline. */ }
  }
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
  window.addEventListener('dataforge:auth-changed',()=>{if(openAfterLogin&&window.DataForgePortal?.state==='authenticated'){openAfterLogin=false;openGenerator();}});
  orderTools();
  const params=new URLSearchParams(location.search);
  if(params.get('tool')==='generator')openGenerator(params.get('job'),params.get('from'));
})();
