const portal$ = (selector) => document.querySelector(selector);

async function portalBoot() {
  bindPortalEvents();
  try {
    const user = await portalApi('/api/auth/me');
    showPortalUser(user);
  } catch (_) {
    showPortalAnonymous();
  }
}

function bindPortalEvents() {
  const logout = portal$('#logoutButton');
  if (logout) {
    logout.onclick = async () => {
      await fetch('/api/auth/logout', {method: 'POST'});
      location.reload();
    };
  }
  document.querySelectorAll('[data-coming-soon]').forEach(button => {
    button.onclick = () => portalToast(button.dataset.comingSoon || '这个功能正在路上');
  });
}

function showPortalUser(user) {
  portal$('#accountActions')?.classList.remove('hidden');
  portal$('#anonymousActions')?.classList.add('hidden');
  const name = portal$('#currentUsername');
  if (name) name.textContent = user.username;
  const admin = portal$('#adminLink');
  if (admin) admin.classList.toggle('hidden', user.role !== 'ADMIN');
}

function showPortalAnonymous() {
  portal$('#accountActions')?.classList.add('hidden');
  portal$('#anonymousActions')?.classList.remove('hidden');
}

async function portalApi(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(String(response.status));
  return response.json();
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
