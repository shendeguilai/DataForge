const portal$ = (selector) => document.querySelector(selector);
let portalCurrentUser = null;
let portalPendingHref = null;

async function portalBoot() {
  ensurePortalAuthView();
  bindPortalEvents();
  try {
    const user = await portalRequest('/api/auth/me');
    showPortalUser(user);
  } catch (_) {
    showPortalAnonymous();
  }
}

function ensurePortalAuthView() {
  if (portal$('#portalAuthView')) return;
  document.body.insertAdjacentHTML('beforeend', `
    <section id="portalAuthView" class="auth-view hidden" role="dialog" aria-modal="true" aria-labelledby="portalAuthTitle">
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
      </div>
    </section>`);
}

function bindPortalEvents() {
  const logout = portal$('#logoutButton');
  if (logout) {
    logout.onclick = async () => {
      await fetch('/api/auth/logout', {method: 'POST'});
      location.reload();
    };
  }
  document.querySelectorAll('[data-portal-auth]').forEach(button => {
    button.onclick = () => showPortalAuth(button.dataset.portalAuth);
  });
  document.querySelectorAll('[data-portal-auth-tab]').forEach(button => {
    button.onclick = () => switchPortalAuthTab(button.dataset.portalAuthTab);
  });
  portal$('#portalCloseAuth').onclick = () => hidePortalAuth(true);
  portal$('#portalLoginForm').addEventListener('submit', loginFromPortal);
  portal$('#portalRegisterForm').addEventListener('submit', registerFromPortal);
  portal$('#portalAuthView').addEventListener('click', event => {
    if (event.target === portal$('#portalAuthView')) hidePortalAuth(true);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !portal$('#portalAuthView').classList.contains('hidden')) hidePortalAuth(true);
  });
  document.querySelectorAll('[data-coming-soon]').forEach(button => {
    button.onclick = () => portalToast(button.dataset.comingSoon || '这个功能正在路上');
  });
  document.querySelectorAll('[data-auth-required-href]').forEach(button => {
    button.onclick = () => {
      const href = button.dataset.authRequiredHref;
      if (portalCurrentUser) {
        location.href = href;
      } else {
        portalPendingHref = href;
        showPortalAuth('login');
        const hint = portal$('#portalAuthView .auth-hint');
        if (hint) hint.textContent = '登录后即可进入教师抢答控制台。学生请使用老师提供的加入链接。';
      }
    };
  });
}

function showPortalAuth(tab = 'login') {
  switchPortalAuthTab(tab);
  portal$('#portalAuthView').classList.remove('hidden');
  setTimeout(() => portal$(tab === 'register' ? '#portalRegisterUsername' : '#portalLoginUsername').focus(), 0);
}

function hidePortalAuth(clearPending = false) {
  portal$('#portalAuthView').classList.add('hidden');
  portal$('#portalAuthError').classList.add('hidden');
  if (clearPending) portalPendingHref = null;
  const hint = portal$('#portalAuthView .auth-hint');
  if (hint) hint.textContent = '在当前页面登录，成功后可直接继续操作。';
}

function switchPortalAuthTab(tab) {
  document.querySelectorAll('[data-portal-auth-tab]').forEach(button => {
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
  if (submitButton) submitButton.disabled = true;
  try {
    const user = await portalRequest(url, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    showPortalUser(user);
    hidePortalAuth();
    portal$('#portalLoginPassword').value = '';
    portal$('#portalRegisterPassword').value = '';
    window.dispatchEvent(new CustomEvent('dataforge:auth-changed', {detail: user}));
    portalToast(url.endsWith('/register') ? '注册成功，已登录' : '登录成功');
    if (portalPendingHref) {
      const href = portalPendingHref;
      portalPendingHref = null;
      setTimeout(() => location.href = href, 250);
    }
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove('hidden');
  } finally {
    if (submitButton) submitButton.disabled = false;
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

function portalToast(message) {
  const toast = portal$('#toast');
  if (!toast) {
    alert(message);
    return;
  }
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2600);
}

portalBoot();
