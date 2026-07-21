function quizEscape(value) {
  return String(value ?? '').replace(/[&<>'"]/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  })[character]);
}

async function quizRequest(url, options = {}) {
  const headers = {...(options.headers || {})};
  if (options.token) headers['X-Room-Token'] = options.token;
  const request = {method: options.method || 'GET', headers};
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    request.body = JSON.stringify(options.body);
  }
  const response = await fetch(url, request);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || body.message || `请求失败 (${response.status})`);
  return body;
}

function quizToast(message) {
  const toast = document.querySelector('#toast');
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(quizToast.timer);
  quizToast.timer = setTimeout(() => toast.classList.remove('show'), 2600);
}

function quizStateLabel(state) {
  return ({
    LOBBY: '等待准备', READY: '准备就绪', COUNTDOWN: '即将开始', OPEN: '抢答中',
    ANSWERING: '回答中', TIMED_OUT: '抢答超时', REVEALED: '答案已揭晓'
  })[state] || state || '等待中';
}

function quizInitial(name) {
  return Array.from(String(name || '?'))[0] || '?';
}
