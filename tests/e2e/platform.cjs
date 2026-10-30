/* Run against an isolated temporary database. Never points at the production server. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawn}=require('node:child_process');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../..');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dataforge-e2e-'));
const port=Number(process.env.DATAFORGE_E2E_PORT||18081),base=`http://127.0.0.1:${port}`;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let server,browser;
const log=fs.openSync(path.join(temporary,'server.log'),'a');
async function start(){
  if(!process.env.DATAFORGE_E2E_CLASSPATH)throw new Error('Build test classpath first; see tests/e2e/README.md');
  server=spawn('java',['-cp',`${path.join(root,'target/classes')}:${path.join(root,'target/test-classes')}:${process.env.DATAFORGE_E2E_CLASSPATH}`,
    'cn.datacraft.DataForgeApplication','--spring.profiles.active=e2e',`--server.port=${port}`,
    `--spring.datasource.url=jdbc:h2:file:${temporary}/database;DB_CLOSE_ON_EXIT=FALSE`,
    `--dataforge.runtime-dir=${temporary}/runtime`,'--dataforge.admin.password=browser-test-password',
    '--dataforge.homework.scheduling-enabled=true','--dataforge.execution.mode=local',
    '--dataforge.generation.scheduling-enabled=true','--dataforge.ai.api-key='],{cwd:root,stdio:['ignore',log,log]});
  for(let attempt=0;attempt<180;attempt++){
    if(server.exitCode!==null)throw new Error('Test server exited: '+path.join(temporary,'server.log'));
    try{if((await fetch(base+'/actuator/health')).ok)return;}catch{}
    await pause(250);
  }throw new Error('Test server did not become healthy');
}
async function stop(){if(!server||server.exitCode!==null)return;const exit=new Promise(resolve=>server.once('exit',resolve));server.kill('SIGTERM');await exit;}
async function api(page,url,method='GET',data){
  return page.evaluate(async({url,method,data})=>{
    const response=await fetch(url,{method,...(data===undefined?{}:{headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})});
    if(!response.ok)throw new Error(url+' returned '+response.status+' '+await response.text());
    return response.status===204?null:response.json();
  },{url,method,data});
}
(async()=>{
  await start();
  browser=await chromium.launch({headless:true,...(process.env.DATAFORGE_BROWSER_EXECUTABLE?{executablePath:process.env.DATAFORGE_BROWSER_EXECUTABLE}:{})});
  const context=await browser.newContext({viewport:{width:1365,height:900}}),page=await context.newPage();
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(base);assert.equal(new URL(page.url()).pathname,'/tools.html');
  assert.equal(await page.locator('.module-nav a').filter({hasText:'数据工坊'}).count(),0);
  await page.locator('[data-portal-auth="login"]').click();
  await page.locator('#portalLoginUsername').fill('admin');await page.locator('#portalLoginPassword').fill('browser-test-password');
  await page.locator('#portalLoginForm button[type="submit"]').click();
  await page.waitForFunction(()=>window.DataForgePortal?.state==='authenticated');
  assert.equal(await page.locator('#teacherWorkbench').count(),0);
  console.log('PASS login and tools without teacher workbench');

  await page.goto(base+'/admin.html');await page.locator('[data-panel="tools"]').click();
  await page.locator('#toolOrderList li').first().getByRole('button',{name:'可达 OJ 作业跟踪下移',exact:true}).click();
  await page.locator('#saveToolOrder').click();await page.getByText('已保存，实用工具列表刷新后显示新顺序。',{exact:true}).waitFor();
  await page.goto(base+'/tools.html');await page.waitForFunction(()=>document.querySelector('.portal-grid article')?.dataset.toolId==='csp-sim');
  await page.reload();await page.waitForFunction(()=>document.querySelector('.portal-grid article')?.dataset.toolId==='csp-sim');
  console.log('PASS administrator tool ordering and persistence');

  await page.route('**/api/auth/me',route=>route.fulfill({status:503,contentType:'application/json',body:'{}'}));
  await page.reload();await page.getByText('暂时无法确认登录状态，请检查网络后刷新重试。',{exact:true}).waitFor();
  await page.locator('[data-tool-id="homework"] button').click();
  assert.equal(await page.locator('#portalAuthView').isHidden(),true,'network failure must not open a login prompt');
  await page.unroute('**/api/auth/me');await page.reload();await page.waitForFunction(()=>window.DataForgePortal?.state==='authenticated');
  console.log('PASS transient network failure does not masquerade as logout');

  await api(page,'/api/tools/kedaoj-homework/settings','PUT',{credential:'fake-e2e-sid',domain:'system',autoUpdate:false});
  const classroom=await api(page,'/api/tools/kedaoj-homework/classes','POST',{name:'端到端测试班'});
  await api(page,`/api/tools/kedaoj-homework/classes/${classroom.id}/students`,'POST',[{name:'测试同学',uid:42}]);
  const assignment=await api(page,'/api/tools/kedaoj-homework/assignments','POST',{
    classId:classroom.id,title:'第一周测试',domain:'system',startAt:new Date(Date.now()-86400000).toISOString(),endAt:new Date(Date.now()+86400000).toISOString(),problemIds:['P0032']});
  await page.goto(base+`/kedaoj-homework.html?assignment=${assignment.id}`);await page.locator('#assignmentBoard').waitFor({state:'visible'});
  await page.locator('#refreshAssignment').click();
  await page.waitForFunction(async id=>{const r=await fetch('/api/tools/kedaoj-homework/assignments/'+id);if(!r.ok)return false;return (await r.json()).cells['42:32']?.status==='AC';},assignment.id,{timeout:30000});
  await page.locator('#assignmentBoard .hw-cell.AC').first().waitFor({state:'visible'});
  console.log('PASS teacher creates homework and checks OJ results through an isolated fixture');

  const cspStudent=await api(page,'/api/tools/csp-sim/students','POST',{name:'测试同学',className:'端到端测试班',studentNumber:'0042'});
  const exam=await api(page,'/api/tools/csp-sim/exams','POST',{exam:{name:'浏览器模拟考',environment:'LINUX',mode:'TEACHING',durationMinutes:30,problems:[{name:'求和',directory:'sum',sourceName:'sum.cpp'}]},students:[{studentId:cspStudent.id}]});
  const problem=exam.exam.problems[0];
  await page.goto(base+`/csp-sim.html?exam=${exam.id}`);
  const [dataChooser]=await Promise.all([page.waitForEvent('filechooser'),page.locator(`[data-action="upload-data"][data-id="${problem.id}"]`).click()]);
  await dataChooser.setFiles([{name:'1.in',mimeType:'application/octet-stream',buffer:Buffer.alloc(8*1024*1024,49)},{name:'1.out',mimeType:'text/plain',buffer:Buffer.from('1\n')}]);
  await page.locator('#dataConfigForm').waitFor({state:'visible',timeout:60000});
  await page.locator('button[form="dataConfigForm"]').click();await page.locator('#dataConfigForm').waitFor({state:'hidden'});
  assert.equal((await api(page,`/api/tools/csp-sim/exams/${exam.id}`)).exam.problems[0].dataConfirmed,true);
  await api(page,`/api/tools/csp-sim/exams/${exam.id}/start`,'POST');
  const studentPage=await context.newPage();studentPage.on('pageerror',error=>errors.push(error.message));
  await studentPage.goto(base+`/csp-sim-student.html?exam=${exam.exam.joinCode}`);
  await studentPage.locator('#joinForm [name="name"]').fill('测试同学');await studentPage.locator('#joinForm [name="code"]').fill('0042');
  await studentPage.locator('#joinForm button[type="submit"]').click();await studentPage.locator('[data-action="upload-files"]').waitFor({state:'visible'});
  const [sourceChooser]=await Promise.all([studentPage.waitForEvent('filechooser'),studentPage.locator('[data-action="upload-files"]').click()]);
  await sourceChooser.setFiles({name:'hello.cpp',mimeType:'text/plain',buffer:Buffer.from('int main(){return 0;}')});
  await studentPage.locator('[data-file-path$="/hello.cpp"]').waitFor({state:'visible'});
  await studentPage.reload();await studentPage.locator('[data-file-path$="/hello.cpp"]').waitFor({state:'visible'});
  studentPage.once('dialog',dialog=>dialog.accept());await studentPage.locator('[data-action="submit"]').click();
  await page.waitForFunction(async id=>{const r=await fetch('/api/tools/csp-sim/exams/'+id);return r.ok&&(await r.json()).students[0].latestSubmission;},exam.id,{timeout:15000});
  await api(page,`/api/tools/csp-sim/exams/${exam.id}/close`,'POST');
  const batch=await api(page,`/api/tools/csp-sim/exams/${exam.id}/grade`,'POST',{});
  await api(page,`/api/tools/csp-sim/exams/${exam.id}/batches/${batch.id}/publish`,'POST');
  await studentPage.reload();await studentPage.getByText('班级排名',{exact:true}).first().waitFor({state:'visible'});
  await studentPage.close();
  console.log('PASS streamed data upload, student session recovery, submission and published results');

  await api(page,'/api/admin/atcoder-leaderboard/config','PUT',{contestId:'abc999',displayTitle:'测试比赛'});
  await page.goto(base+'/admin.html?contestId=abc999');await page.locator('[data-panel="leaderboard"]').click();
  await page.locator('[data-manual-translation="abc999_a"]').click();
  await page.locator('#manualTranslationText').fill('【题目描述】\n给定两个数，输出 $a+b$。\n\n【输入格式】\n输入两个整数。\n\n【输出格式】\n输出它们的和。\n\n【样例输入 1】\n1 2\n\n【样例输出 1】\n3');
  await page.locator('#importManualTranslation').click();await page.locator('#translationEditorMeta').filter({hasText:'手动题面发布于'}).waitFor();
  await page.goto(base+'/atcoder-problems.html?contestId=abc999&task=abc999_a');
  await page.locator('#statementContent').getByText('给定两个数',{exact:false}).waitFor();
  await page.locator('#statementContent .katex').first().waitFor({state:'attached'});
  console.log('PASS manual translation import and formula rendering');

  await page.goto(base+'/atcoder-leaderboard.html');
  await page.waitForFunction(()=>document.querySelector('#contestSelector')?.value==='abc999');
  assert.equal(new URL(page.url()).searchParams.has('contestId'),false);
  await api(page,'/api/admin/atcoder-leaderboard/config','PUT',{contestId:'abc998',displayTitle:'自动切换测试'});
  await page.evaluate(()=>loadLeaderboard(false));
  await page.waitForFunction(()=>document.querySelector('#contestSelector')?.value==='abc998');
  await page.locator('#contestSelector').selectOption('abc999');
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('contestId')==='abc999');
  await api(page,'/api/admin/atcoder-leaderboard/config','PUT',{contestId:'abc997',displayTitle:'下一场测试'});
  await page.evaluate(()=>loadLeaderboard(false));
  assert.equal(await page.locator('#contestSelector').inputValue(),'abc999');
  console.log('PASS default leaderboard follows selection while explicit history remains pinned');

  await page.goto(base+'/tools.html');await page.waitForFunction(()=>window.DataForgePortal?.state==='authenticated');

  await page.locator('#openGenerator').click();
  const generator=page.frameLocator('#generatorFrame');
  await page.waitForFunction(()=>document.querySelector('#generatorFrame')?.contentDocument?.querySelector('#currentUsername')?.textContent==='admin');
  assert.equal(await generator.locator('.topbar').isHidden(),true,'embedded generator must not show a second navigation bar');
  const standard='#include<iostream>\nusing namespace std;int main(){int n;cin>>n;long long a,s=0;while(n--){cin>>a;s+=a;}cout<<s<<"\\n";}';
  await generator.locator('#standardCode').fill(standard);await generator.locator('#caseCount').fill('2');
  await generator.getByText('可选：输入校验与小数据对拍',{exact:true}).click();
  await generator.locator('#validatorCode').fill('#include<iostream>\nint main(){int n;if(!(std::cin>>n)||n<1||n>100000)return 1;long long a;while(n--)if(!(std::cin>>a)||a<0||a>1000000)return 2;return 0;}');
  await generator.locator('#bruteCode').fill(standard);
  await generator.locator('#analyzeButton').click();await generator.locator('#planView').waitFor({state:'visible',timeout:60000});
  await generator.locator('#confirmButton').click();await generator.locator('#downloadButton').waitFor({state:'visible',timeout:60000});
  await generator.locator('#qualityReportButton').click();await generator.locator('#qualityReport').waitFor({state:'visible'});
  assert.match(await generator.locator('#qualityReport').textContent(),/全部通过/);assert.match(await generator.locator('#qualityReport').textContent(),/2 组通过/);
  console.log('PASS real generation, validator, small-input comparison and report');

  await page.goto(base+'/tools.html');
  const me=await api(page,'/api/auth/me');assert.equal(me.username,'admin');
  await stop();await start();await page.reload();
  assert.equal((await api(page,'/api/auth/me')).username,'admin');
  await page.waitForFunction(()=>window.DataForgePortal?.state==='authenticated');
  await page.setViewportSize({width:390,height:844});await page.reload();await page.waitForFunction(()=>window.DataForgePortal?.state==='authenticated');
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+2),'mobile horizontal overflow');
  await page.screenshot({path:path.join(temporary,'tools-mobile.png'),fullPage:true});
  console.log('PASS server restart keeps login and mobile tool layout');
  await page.locator('#accountActions .account-menu-toggle').click();
  await page.locator('#logoutButton').click();await page.locator('#anonymousActions').waitFor({state:'visible'});
  const response=await context.request.get(base+'/api/auth/me');assert.equal(response.status(),401);
  assert.deepEqual(errors,[],'browser JavaScript errors');
  console.log('PASS logout invalidates session');console.log('E2E artifacts: '+temporary);
})().catch(error=>{console.error(error);console.error('E2E logs: '+temporary);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();await stop();fs.closeSync(log);});
