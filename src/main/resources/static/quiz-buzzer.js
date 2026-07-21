const teacher$ = selector => document.querySelector(selector);
const teacherState = {
  catalog: null, room: null, socket: null, reconnectTimer: null, closedByClient: false,
  serverOffset: 0, roomCode: null
};

document.addEventListener('DOMContentLoaded', bootQuizTeacher);

async function bootQuizTeacher() {
  bindTeacherEvents();
  try {
    teacherState.catalog = await quizRequest('/api/tools/quiz/catalog');
    renderCatalogFilters();
  } catch (error) {
    quizToast(error.message);
  }
  const requested = new URLSearchParams(location.search).get('room');
  const saved = localStorage.getItem('dataforge.quiz.ownerRoom');
  const roomCode = (requested || saved || '').toUpperCase();
  if (roomCode) {
    try {
      const room = await quizRequest(`/api/tools/quiz/rooms/${encodeURIComponent(roomCode)}`);
      enterTeacherRoom(room);
    } catch (_) {
      localStorage.removeItem('dataforge.quiz.ownerRoom');
      history.replaceState(null, '', '/quiz-buzzer.html');
    }
  }
  setInterval(renderTeacherClock, 100);
  setInterval(() => sendTeacherSocket({type: 'ping'}), 20000);
}

function bindTeacherEvents() {
  teacher$('#createQuizRoomForm').addEventListener('submit', createQuizRoom);
  teacher$('#primaryRoundButton').onclick = handlePrimaryRoundAction;
  teacher$('#correctButton').onclick = () => judgeAnswer('CORRECT');
  teacher$('#wrongButton').onclick = () => judgeAnswer('WRONG');
  teacher$('#revealButton').onclick = revealAnswer;
  teacher$('#chooseQuestionButton').onclick = openQuestionPicker;
  teacher$('#closeQuestionPicker').onclick = closeQuestionPicker;
  teacher$('#questionPickerModal').onclick = event => {
    if (event.target === teacher$('#questionPickerModal')) closeQuestionPicker();
  };
  teacher$('#questionSearchInput').oninput = renderQuestionPicker;
  teacher$('#copyJoinButton').onclick = copyJoinInfo;
  teacher$('#rotateInviteButton').onclick = rotateInvite;
  teacher$('#closeQuizRoomButton').onclick = closeQuizRoom;
}

function renderCatalogFilters() {
  const catalog = teacherState.catalog;
  teacher$('#categoryFilters').innerHTML = catalog.categories.map(value => filterCheckbox('category', value)).join('');
  teacher$('#difficultyFilters').innerHTML = catalog.difficulties.map(value => filterCheckbox('difficulty', value)).join('');
  teacher$('#quizMatchNote').textContent = `共 ${catalog.total} 道题可用 · 当前题库以口头填空 / 简答为主`;
}

function filterCheckbox(group, value) {
  return `<label class="quiz-filter-option"><input type="checkbox" name="${group}" value="${quizEscape(value)}" checked><span>${quizEscape(value)}</span></label>`;
}

async function createQuizRoom(event) {
  event.preventDefault();
  const button = event.submitter;
  setTeacherBusy(button, true, '创建中');
  try {
    const room = await quizRequest('/api/tools/quiz/rooms', {
      method: 'POST', body: {
        name: teacher$('#quizRoomName').value.trim(),
        categories: checkedValues('category'), difficulties: checkedValues('difficulty'),
        questionCount: Number(teacher$('#quizQuestionCount').value),
        buzzSeconds: Number(teacher$('#quizBuzzSeconds').value)
      }
    });
    enterTeacherRoom(room);
  } catch (error) {
    quizToast(error.message);
  } finally {
    setTeacherBusy(button, false);
  }
}

function checkedValues(name) {
  return [...document.querySelectorAll(`input[name="${name}"]:checked`)].map(input => input.value);
}

function enterTeacherRoom(room) {
  teacherState.room = room;
  teacherState.roomCode = room.roomCode;
  teacherState.serverOffset = room.serverTime - Date.now();
  localStorage.setItem('dataforge.quiz.ownerRoom', room.roomCode);
  history.replaceState(null, '', `?room=${encodeURIComponent(room.roomCode)}`);
  teacher$('#teacherCreateView').classList.add('hidden');
  teacher$('#teacherRoomView').classList.remove('hidden');
  applyTeacherRoom(room);
  connectTeacherSocket();
}

function applyTeacherRoom(room) {
  if (!room || room.roomCode !== teacherState.roomCode) return;
  teacherState.room = room;
  teacherState.serverOffset = room.serverTime - Date.now();
  teacher$('#teacherRoomName').textContent = room.name;
  teacher$('#teacherRoomCode').textContent = room.roomCode;
  teacher$('#teacherInviteCode').textContent = room.inviteCode || '------';
  teacher$('#teacherJoinUrl').textContent = `${location.origin}${room.joinUrl}`;
  teacher$('#teacherRoomState').textContent = quizStateLabel(room.state);
  teacher$('#teacherOnlineCount').textContent = `${room.onlineCount} 人在线`;
  teacher$('#teacherMemberCount').textContent = `${room.members.length} / ${room.capacity}`;
  renderTeacherQuestion(room);
  renderTeacherControls(room);
  renderTeacherAnswer(room.referenceAnswer);
  renderTeacherWinner(room);
  renderTeacherMembers(room);
  renderTeacherClock();
}

function renderTeacherQuestion(room) {
  const image = teacher$('#teacherQuestionImage');
  const empty = teacher$('#teacherEmptyQuestion');
  if (!room.question) {
    image.classList.add('hidden');
    image.removeAttribute('src');
    empty.classList.remove('hidden');
    teacher$('#teacherQuestionType').textContent = '口头填空';
    teacher$('#teacherQuestionId').textContent = '未选题';
    teacher$('#teacherQuestionProgress').textContent = `0 / ${room.availableQuestions?.length || 0}`;
    teacher$('#teacherRoundStatus').textContent = '等待准备题目';
    return;
  }
  empty.classList.add('hidden');
  image.classList.remove('hidden');
  if (image.getAttribute('src') !== room.question.imageUrl) image.src = room.question.imageUrl;
  image.alt = `${room.question.id}：${room.question.promptText}`;
  teacher$('#teacherQuestionType').textContent = room.question.category;
  teacher$('#teacherQuestionId').textContent = room.question.id;
  teacher$('#teacherQuestionProgress').textContent = `${room.round.questionNumber} / ${room.round.questionTotal}`;
  teacher$('#teacherRoundStatus').textContent = teacherRoundMessage(room);
}

function teacherRoundMessage(room) {
  if (room.state === 'COUNTDOWN') return '请学生准备';
  if (room.state === 'OPEN') return '抢答已开放';
  if (room.state === 'ANSWERING') return `${room.round.winnerName} 正在回答`;
  if (room.state === 'TIMED_OUT') return '本轮无人抢答';
  if (room.state === 'REVEALED') return '答案与解析已同步给全班';
  return '题目已就绪';
}

function renderTeacherControls(room) {
  const primary = teacher$('#primaryRoundButton');
  const correct = teacher$('#correctButton');
  const wrong = teacher$('#wrongButton');
  const choose = teacher$('#chooseQuestionButton');
  const reveal = teacher$('#revealButton');
  [correct, wrong, choose, reveal].forEach(button => button.classList.add('hidden'));
  primary.classList.remove('hidden');
  primary.disabled = false;
  if (room.state === 'LOBBY') {
    setButtonLabel(primary, '准备第一题', '→');
  } else if (room.state === 'READY') {
    setButtonLabel(primary, '开始抢答', '▶');
    choose.classList.remove('hidden');
    reveal.classList.remove('hidden');
  } else if (room.state === 'TIMED_OUT') {
    setButtonLabel(primary, '重新开放抢答', '↻');
    reveal.classList.remove('hidden');
  } else if (room.state === 'ANSWERING') {
    primary.classList.add('hidden');
    correct.classList.remove('hidden');
    wrong.classList.remove('hidden');
    reveal.classList.remove('hidden');
  } else if (room.state === 'REVEALED') {
    setButtonLabel(primary, room.round.questionNumber >= room.round.questionTotal ? '题单已完成' : '准备下一题', '→');
    primary.disabled = room.round.questionNumber >= room.round.questionTotal;
  } else {
    setButtonLabel(primary, room.state === 'COUNTDOWN' ? '倒计时中' : '等待学生抢答', '…');
    primary.disabled = true;
    reveal.classList.remove('hidden');
  }
}

async function handlePrimaryRoundAction(event) {
  const room = teacherState.room;
  if (!room) return;
  const action = room.state === 'LOBBY' || room.state === 'REVEALED' ? 'next' : 'open';
  setTeacherBusy(event.currentTarget, true, '处理中');
  try {
    const updated = await quizRequest(`/api/tools/quiz/rooms/${room.roomCode}/rounds/${action}`, {method: 'POST'});
    applyTeacherRoom(updated);
  } catch (error) { quizToast(error.message); }
  finally { setTeacherBusy(event.currentTarget, false); }
}

async function judgeAnswer(result) {
  try {
    const room = await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}/rounds/judge`, {
      method: 'POST', body: {result}
    });
    applyTeacherRoom(room);
    quizToast(result === 'CORRECT' ? '已计 1 分，并向全班公布解析' : '已排除本次答题者，3 秒后重新开放');
  } catch (error) { quizToast(error.message); }
}

async function revealAnswer() {
  try {
    const room = await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}/rounds/reveal`, {method: 'POST'});
    applyTeacherRoom(room);
    teacher$('#teacherAnswerPanel').open = true;
  } catch (error) { quizToast(error.message); }
}

function renderTeacherAnswer(answer) {
  const target = teacher$('#teacherAnswerContent');
  if (!answer) {
    target.innerHTML = '<p>准备题目后显示参考答案。</p>';
    return;
  }
  target.innerHTML = answerMarkup(answer);
}

function answerMarkup(answer) {
  return `<h3>直接答案</h3><strong>${quizEscape(answer.answer)}</strong>
    <h3>为什么</h3><p>${quizEscape(answer.explanation)}</p>
    ${answer.example ? `<h3>示例</h3><pre>${quizEscape(answer.example)}</pre>` : ''}
    ${answer.pitfall ? `<h3>容易混淆</h3><p>${quizEscape(answer.pitfall)}</p>` : ''}`;
}

function renderTeacherWinner(room) {
  const target = teacher$('#teacherWinnerCard');
  if (room.round?.winnerName) {
    target.innerHTML = `<div><div class="quiz-winner-avatar">${quizEscape(quizInitial(room.round.winnerName))}</div><span>获得回答权</span><strong>${quizEscape(room.round.winnerName)}</strong></div>`;
  } else if (room.state === 'TIMED_OUT') {
    target.innerHTML = '<div><span>抢答时间已结束</span><strong>无人抢答</strong></div>';
  } else if (room.state === 'REVEALED') {
    target.innerHTML = '<div><span>本题已经完成</span><strong>解析已揭晓</strong></div>';
  } else {
    target.innerHTML = `<span>${room.state === 'OPEN' ? '正在等待第一个有效抢答' : '等待老师开放抢答'}</span>`;
  }
  teacher$('#teacherAttemptList').innerHTML = (room.round?.attempts || []).map(attempt =>
    `<div class="quiz-attempt-row"><span>${quizEscape(attempt.displayName)}</span><b>${attempt.result === 'WRONG' ? '回答错误' : attempt.result === 'CORRECT' ? '回答正确' : '老师揭晓'}</b></div>`
  ).join('');
}

function renderTeacherMembers(room) {
  teacher$('#teacherMemberList').innerHTML = room.members.map(member => `
    <div class="quiz-member-row">
      <span class="quiz-member-avatar ${member.online ? '' : 'offline'}">${quizEscape(quizInitial(member.displayName))}</span>
      <strong>${quizEscape(member.displayName)} ${member.owner ? '<small>老师</small>' : member.excluded ? '<small>本题已回答</small>' : ''}</strong>
      <span class="quiz-member-score">${member.score}</span>
      ${member.owner ? '<span></span>' : `<button class="quiz-kick" data-kick-member="${quizEscape(member.memberId)}" type="button">移除</button>`}
    </div>`).join('');
  teacher$('#teacherMemberList').querySelectorAll('[data-kick-member]').forEach(button => {
    button.onclick = () => kickMember(button.dataset.kickMember);
  });
}

async function kickMember(memberId) {
  try {
    const room = await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}/members/${encodeURIComponent(memberId)}`, {method: 'DELETE'});
    applyTeacherRoom(room);
  } catch (error) { quizToast(error.message); }
}

function openQuestionPicker() {
  teacher$('#questionSearchInput').value = '';
  renderQuestionPicker();
  teacher$('#questionPickerModal').classList.remove('hidden');
  setTimeout(() => teacher$('#questionSearchInput').focus(), 0);
}

function closeQuestionPicker() { teacher$('#questionPickerModal').classList.add('hidden'); }

function renderQuestionPicker() {
  const query = teacher$('#questionSearchInput').value.trim().toLowerCase();
  const available = teacherState.room?.availableQuestions || [];
  const filtered = available.filter(item => !query || `${item.id} ${item.category} ${item.promptText}`.toLowerCase().includes(query));
  teacher$('#questionPickerList').innerHTML = filtered.length ? filtered.map(item => `
    <div class="quiz-picker-row"><span>${quizEscape(item.id)}</span><div><strong>${quizEscape(item.promptText)}</strong><small>${quizEscape(item.category)} · ${quizEscape(item.difficulty)}</small></div><button data-question-id="${quizEscape(item.id)}" type="button">选择</button></div>`).join('') : '<p class="empty">没有匹配的剩余题目</p>';
  teacher$('#questionPickerList').querySelectorAll('[data-question-id]').forEach(button => {
    button.onclick = () => chooseQuestion(button.dataset.questionId);
  });
}

async function chooseQuestion(questionId) {
  try {
    const room = await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}/rounds/question`, {
      method: 'POST', body: {questionId}
    });
    applyTeacherRoom(room);
    closeQuestionPicker();
  } catch (error) { quizToast(error.message); }
}

async function copyJoinInfo() {
  const room = teacherState.room;
  const text = `问题抢答：${room.name}\n加入地址：${location.origin}${room.joinUrl}\n房间号：${room.roomCode}\n邀请码：${room.inviteCode}`;
  try { await navigator.clipboard.writeText(text); quizToast('加入信息已复制'); }
  catch (_) { quizToast('复制失败，请手动复制房间号和邀请码'); }
}

async function rotateInvite() {
  try {
    const room = await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}/invite/rotate`, {method: 'POST'});
    applyTeacherRoom(room); quizToast('邀请码已更换，已加入的学生不受影响');
  } catch (error) { quizToast(error.message); }
}

async function closeQuizRoom() {
  if (!confirm('确定结束并关闭当前课堂吗？本节课积分将不再保留。')) return;
  try {
    await quizRequest(`/api/tools/quiz/rooms/${teacherState.roomCode}`, {method: 'DELETE'});
    localStorage.removeItem('dataforge.quiz.ownerRoom');
    disconnectTeacherSocket(true);
    location.href = '/tools.html';
  } catch (error) { quizToast(error.message); }
}

function connectTeacherSocket() {
  disconnectTeacherSocket(true);
  teacherState.closedByClient = false;
  setTeacherSocketState('connecting', '连接中');
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const query = new URLSearchParams({room: teacherState.roomCode});
  const socket = new WebSocket(`${protocol}//${location.host}/ws/tools/quiz?${query}`);
  teacherState.socket = socket;
  socket.onopen = () => setTeacherSocketState('online', '实时连接');
  socket.onmessage = event => handleTeacherSocketMessage(event.data);
  socket.onerror = () => setTeacherSocketState('error', '连接异常');
  socket.onclose = () => {
    if (teacherState.socket !== socket) return;
    setTeacherSocketState('error', '断线重连中');
    if (!teacherState.closedByClient && teacherState.roomCode) {
      clearTimeout(teacherState.reconnectTimer);
      teacherState.reconnectTimer = setTimeout(connectTeacherSocket, 1500);
    }
  };
}

function handleTeacherSocketMessage(raw) {
  let message; try { message = JSON.parse(raw); } catch (_) { return; }
  if (message.type === 'room.snapshot') applyTeacherRoom(message.payload);
  else if (message.type === 'room.error') quizToast(message.payload?.message || '操作失败');
  else if (message.type === 'room.closed' || message.type === 'room.revoked') {
    localStorage.removeItem('dataforge.quiz.ownerRoom');
    quizToast(message.payload?.message || '房间已关闭');
    setTimeout(() => location.href = '/tools.html', 600);
  }
}

function sendTeacherSocket(message) {
  const socket = teacherState.socket;
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function disconnectTeacherSocket(manual) {
  clearTimeout(teacherState.reconnectTimer);
  teacherState.closedByClient = manual;
  const socket = teacherState.socket; teacherState.socket = null;
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'leaving');
}

function setTeacherSocketState(kind, text) {
  const target = teacher$('#teacherSocketState');
  target.className = `quiz-socket ${kind}`;
  target.innerHTML = `<i></i>${quizEscape(text)}`;
}

function renderTeacherClock() {
  const room = teacherState.room;
  const target = teacher$('#teacherClock');
  if (!room?.round) { target.textContent = '—'; return; }
  const now = Date.now() + teacherState.serverOffset;
  if (room.state === 'COUNTDOWN' && room.round.countdownEndsAt) {
    target.textContent = `${Math.max(1, Math.ceil((room.round.countdownEndsAt - now) / 1000))}`;
  } else if (room.state === 'OPEN' && room.round.endsAt) {
    target.textContent = `${Math.max(0, Math.ceil((room.round.endsAt - now) / 1000))}s`;
  } else target.textContent = room.state === 'ANSWERING' ? '已锁定' : '—';
}

function setTeacherBusy(button, busy, label) {
  if (!button) return;
  if (busy) { button.dataset.oldLabel = button.querySelector('span')?.textContent || button.textContent; button.disabled = true; if (button.querySelector('span')) button.querySelector('span').textContent = label; }
  else { button.disabled = false; if (button.dataset.oldLabel && button.querySelector('span')) button.querySelector('span').textContent = button.dataset.oldLabel; }
}

function setButtonLabel(button, label, icon) {
  button.innerHTML = `<span>${quizEscape(label)}</span><b>${quizEscape(icon)}</b>`;
}
