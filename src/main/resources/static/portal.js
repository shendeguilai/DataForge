const portal$ = (selector) => document.querySelector(selector);
const portalUI = window.DataForgeUI || {openDialog: (dialog) => dialog?.classList.remove('hidden'), closeDialog: (dialog) => dialog?.classList.add('hidden'), toast: (message) => window.alert(message), setBusy: (button, busy) => { if (button) button.disabled = busy; }};
let portalCurrentUser = null;
let portalPendingHref = null;

async function portalBoot() {
  ensurePortalAuthView();
  const queryNext = safePortalNext(new URLSearchParams(window.location.search).get('next'));
  portalPendingHref = queryNext;
  bindPortalEvents();
  try {
    const user = await portalRequest('/api/auth/me');
    showPortalUser(user);
    if (queryNext && new URLSearchParams(window.location.search).get('auth') === 'login') {
      window.setTimeout(() => { window.location.href = queryNext; }, 0);
    }
  } catch (_) {
    showPortalAnonymous();
    if (new URLSearchParams(window.location.search).get('auth') === 'login') showPortalAuth('login');
  }
}

function safePortalNext(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\') || value.includes('\\')) return null;
  try {
    const target = new URL(value, window.location.origin);
    if (target.origin !== window.location.origin) return null;
    return `${target.pathname}${target.search}${target.hash}`;
  } catch (_) {
    return null;
  }
}

function ensurePortalAuthView() {
  if (portal$('#portalAuthView')) return;
  const view = document.createElement('section');
  view.id = 'portalAuthView';
  view.className = 'auth-view hidden';
  view.setAttribute('role', 'dialog');
  view.setAttribute('aria-modal', 'true');
  view.setAttribute('aria-labelledby', 'portalAuthTitle');
  view.innerHTML = `
    <div class="auth-card">
      <button class="auth-close" id="portalCloseAuth" type="button" aria-label="暂不登录">×</button>
      <a class="brand auth-brand" href="/"><span class="brand-mark">D</span><span>DataForge</span></a>
      <p class="auth-hint">在当前页面登录，成功后可直接继续操作。</p>
      <div class="auth-tabs">
        <button class="active" data-portal-auth-tab="login" type="button">登录</button>
        <button data-portal-auth-tab="register" type="button">邀请码注册</button>
      </div>
      <form id="portalLoginForm" class="auth-form">
        <h2 id="portalAuthTitle">欢迎回来</h2><p>输入账号后，会留在当前页面。</p>
        <label>用户名<input id="portalLoginUsername" autocomplete="username" required></label>
        <label>密码<input id="portalLoginPassword" type="password" autocomplete="current-password" required></label>
        <button class="primary-btn wide" type="submit"><span>登录</span><b>→</b></button>
      </form>
      <form id="portalRegisterForm" class="auth-form hidden">
        <h2>创建账号</h2><p>用户名为 3～24 位字符，密码至少 8 位。</p>
        <label>用户名<input id="portalRegisterUsername" autocomplete="username" required></label>
        <label>密码<input id="portalRegisterPassword" type="password" autocomplete="new-password" required></label>
        <label>邀请码<input id="portalRegisterInviteCode" type="password" required></label>
        <button class="primary-btn wide" type="submit"><span>注册并登录</span><b>→</b></button>
      </form>
      <div id="portalAuthError" class="error-box hidden" role="alert"></div>
    </div>`;
  document.body.appendChild(view);
}

function bindPortalEvents() {
  const logout = portal$('#logoutButton');
  if (logout) logout.onclick = async () => {
    try {
      const response = await fetch('/api/auth/logout', {method: 'POST'});
      if (!response.ok) throw new Error('退出登录失败');
      location.reload();
    } catch (error) {
      portalUI.toast(error.message, {variant: 'error'});
    }
  };
  document.querySelectorAll('[data-portal-auth]').forEach((button) => {
    button.onclick = () => showPortalAuth(button.dataset.portalAuth, button);
  });
  document.querySelectorAll('[data-portal-auth-tab]').forEach((button) => {
    button.onclick = () => switchPortalAuthTab(button.dataset.portalAuthTab);
  });
  portal$('#portalCloseAuth').onclick = () => hidePortalAuth(true);
  portal$('#portalLoginForm').addEventListener('submit', loginFromPortal);
  portal$('#portalRegisterForm').addEventListener('submit', registerFromPortal);
  document.querySelectorAll('[data-coming-soon]').forEach((button) => {
    button.onclick = () => portalUI.toast(button.dataset.comingSoon || '这个功能正在路上');
  });
  document.querySelectorAll('[data-auth-required-href]').forEach((button) => {
    button.onclick = () => {
      const href = safePortalNext(button.dataset.authRequiredHref);
      if (!href) {
        portalUI.toast('目标地址无效', {variant: 'error'});
        return;
      }
      if (portalCurrentUser) location.href = href;
      else {
        portalPendingHref = href;
        showPortalAuth('login', button);
        const hint = portal$('#portalAuthView .auth-hint');
        if (hint) hint.textContent = button.dataset.authHint || '登录后即可继续使用该工具。';
      }
    };
  });
}

function showPortalAuth(tab = 'login', trigger) {
  switchPortalAuthTab(tab);
  portalUI.openDialog(portal$('#portalAuthView'), trigger);
  window.setTimeout(() => portal$(tab === 'register' ? '#portalRegisterUsername' : '#portalLoginUsername')?.focus(), 0);
}

function hidePortalAuth(clearPending = false) {
  portalUI.closeDialog(portal$('#portalAuthView'));
  portal$('#portalAuthError')?.classList.add('hidden');
  if (clearPending) portalPendingHref = null;
  const hint = portal$('#portalAuthView .auth-hint');
  if (hint) hint.textContent = '在当前页面登录，成功后可直接继续操作。';
}

function switchPortalAuthTab(tab) {
  document.querySelectorAll('[data-portal-auth-tab]').forEach((button) => {
    button.classList.toggle('active', button.dataset.portalAuthTab === tab);
  });
  portal$('#portalLoginForm').classList.toggle('hidden', tab !== 'login');
  portal$('#portalRegisterForm').classList.toggle('hidden', tab !== 'register');
  portal$('#portalAuthError').classList.add('hidden');
}

async function loginFromPortal(event) {
  event.preventDefault();
  await submitPortalAuth('/api/auth/login', {
    username: portal$('#portalLoginUsername').value,
    password: portal$('#portalLoginPassword').value
  }, event.submitter);
}

async function registerFromPortal(event) {
  event.preventDefault();
  await submitPortalAuth('/api/auth/register', {
    username: portal$('#portalRegisterUsername').value,
    password: portal$('#portalRegisterPassword').value,
    inviteCode: portal$('#portalRegisterInviteCode').value
  }, event.submitter);
}

async function submitPortalAuth(url, payload, submitButton) {
  const errorBox = portal$('#portalAuthError');
  errorBox.classList.add('hidden');
  portalUI.setBusy(submitButton, true, '正在处理…');
  try {
    const user = await portalRequest(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
    showPortalUser(user);
    hidePortalAuth();
    portal$('#portalLoginPassword').value = '';
    portal$('#portalRegisterPassword').value = '';
    window.dispatchEvent(new CustomEvent('dataforge:auth-changed', {detail: user}));
    portalUI.toast(url.endsWith('/register') ? '注册成功，已登录' : '登录成功', {variant: 'success'});
    const href = safePortalNext(portalPendingHref);
    portalPendingHref = null;
    if (href) window.setTimeout(() => { location.href = href; }, 250);
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    portalUI.setBusy(submitButton, false);
  }
}

function showPortalUser(user) {
  portalCurrentUser = user;
  portal$('#accountActions')?.classList.remove('hidden');
  portal$('#anonymousActions')?.classList.add('hidden');
  const name = portal$('#currentUsername');
  if (name) name.textContent = user.username;
  const admin = portal$('#adminLink');
  if (admin) admin.classList.toggle('hidden', user.role !== 'ADMIN');
}

function showPortalAnonymous() {
  portalCurrentUser = null;
  portal$('#accountActions')?.classList.add('hidden');
  portal$('#anonymousActions')?.classList.remove('hidden');
}

async function portalRequest(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `请求失败 (${response.status})`);
  return body;
}

portalBoot();
