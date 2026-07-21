const student$ = selector => document.querySelector(selector);
const studentState = {
  room: null, roomCode: null, token: null, displayName: null, socket: null,
  reconnectTimer: null, closedByClient: false, sequence: 0, serverOffset: 0, buzzPending: false
};

document.addEventListener('DOMContentLoaded', bootQuizStudent);

async function bootQuizStudent() {
  bindStudentEvents();
  const requested = (new URLSearchParams(location.search).get('room') || '').toUpperCase();
  if (requested) student$('#studentRoomCode').value = requested;
  const saved = readStudentSession(requested);
  if (requested && saved?.token) {
    try {
      const room = await quizRequest(`/api/tools/quiz/rooms/${encodeURIComponent(requested)}`, {token: saved.token});
      studentState.displayName = saved.displayName;
      enterStudentRoom(room, saved.token);
    } catch (_) { clearStudentSession(requested); }
  }
  setInterval(renderStudentClock, 100);
  setInterval(() => sendStudentSocket({type: 'ping'}), 20000);
}

function bindStudentEvents() {
  student$('#studentJoinForm').addEventListener('submit', joinQuizRoom);
  student$('#studentBuzzButton').onclick = buzz;
  student$('#studentLeaveButton').onclick = leaveQuizRoom;
  student$('#studentRoomCode').oninput = event => event.target.value = event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
  document.addEventListener('keydown', event => {
    if (event.code === 'Space' && studentState.room && !student$('#studentBuzzButton').disabled
        && !['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON'].includes(document.activeElement?.tagName)) {
      event.preventDefault(); buzz();
    }
  });
  window.addEventListener('beforeunload', () => disconnectStudentSocket(true));
}

async function joinQuizRoom(event) {
  event.preventDefault();
  const roomCode = student$('#studentRoomCode').value.trim().toUpperCase();
  const displayName = student$('#studentDisplayName').value.trim();
  const button = event.submitter;
  setStudentBusy(button, true);
  try {
    const joined = await quizRequest(`/api/tools/quiz/rooms/${encodeURIComponent(roomCode)}/join`, {
      method: 'POST', body: {displayName, inviteCode: student$('#studentInviteCode').value.trim()}
    });
    saveStudentSession(roomCode, joined.token, displayName);
    studentState.displayName = displayName;
    enterStudentRoom(joined.room, joined.token);
  } catch (error) { quizToast(error.message); }
  finally { setStudentBusy(button, false); }
}

function enterStudentRoom(room, token) {
  studentState.room = room;
  studentState.roomCode = room.roomCode;
  studentState.token = token;
  studentState.sequence = 0;
  studentState.buzzPending = false;
  studentState.serverOffset = room.serverTime - Date.now();
  history.replaceState(null, '', `?room=${encodeURIComponent(room.roomCode)}`);
  student$('#studentJoinView').classList.add('hidden');
  student$('#studentRoomView').classList.remove('hidden');
  applyStudentRoom(room);
  connectStudentSocket();
}

function applyStudentRoom(room) {
  if (!room || room.roomCode !== studentState.roomCode) return;
  const previousRound = studentState.room?.round?.roundId;
  studentState.room = room;
  studentState.serverOffset = room.serverTime - Date.now();
  if (previousRound !== room.round?.roundId || room.state !== 'OPEN') studentState.buzzPending = false;
  student$('#studentRoomName').textContent = room.name;
  const self = room.members.find(member => member.memberId === room.selfMemberId);
  studentState.displayName = self?.displayName || studentState.displayName;
  student$('#studentIdentity').textContent = `${studentState.displayName || '学生'} · 房间 ${room.roomCode}`;
  student$('#studentOnlineCount').textContent = `${room.onlineCount} 人在线`;
  renderStudentQuestion(room);
  renderStudentBuzz(room);
  renderStudentWinner(room);
  renderStudentScores(room);
  renderStudentAnswer(room.revealedAnswer);
  renderStudentClock();
}

function renderStudentQuestion(room) {
  const image = student$('#studentQuestionImage');
  const empty = student$('#studentEmptyQuestion');
  if (!room.question) {
    image.classList.add('hidden'); image.removeAttribute('src'); empty.classList.remove('hidden');
    student$('#studentQuestionCategory').textContent = '等待发题';
    student$('#studentQuestionId').textContent = '—';
    student$('#studentQuestionProgress').textContent = '0 / 0';
    return;
  }
  empty.classList.add('hidden'); image.classList.remove('hidden');
  if (image.getAttribute('src') !== room.question.imageUrl) image.src = room.question.imageUrl;
  image.alt = `${room.question.id}：${room.question.promptText}`;
  student$('#studentQuestionCategory').textContent = room.question.category;
  student$('#studentQuestionId').textContent = room.question.id;
  student$('#studentQuestionProgress').textContent = `${room.round.questionNumber} / ${room.round.questionTotal}`;
}

function renderStudentBuzz(room) {
  const button = student$('#studentBuzzButton');
  const status = student$('#studentRoundStatus');
  const hint = student$('#studentBuzzHint');
  button.disabled = !room.canBuzz || studentState.buzzPending;
  if (room.state === 'OPEN' && room.canBuzz) {
    status.textContent = '抢答已开放'; hint.textContent = '现在可以点击按钮或按空格键。';
  } else if (room.state === 'OPEN') {
    status.textContent = '本题已回答'; hint.textContent = '本题不能再次抢答，等待其他同学回答。';
  } else if (room.state === 'COUNTDOWN') {
    status.textContent = '准备抢答'; hint.textContent = '倒计时结束后按钮会自动亮起。';
  } else if (room.state === 'ANSWERING') {
    status.textContent = room.round.winnerId === room.selfMemberId ? '你获得了回答权' : `${room.round.winnerName} 获得回答权`;
    hint.textContent = room.round.winnerId === room.selfMemberId ? '请口头回答老师的问题。' : '请等待老师判定。';
  } else if (room.state === 'TIMED_OUT') {
    status.textContent = '本轮无人抢答'; hint.textContent = '等待老师重新开放或揭晓答案。';
  } else if (room.state === 'REVEALED') {
    status.textContent = '答案已经揭晓'; hint.textContent = '阅读解析，等待下一题。';
  } else if (room.state === 'READY') {
    status.textContent = '题目已准备'; hint.textContent = '先思考，等待老师开始抢答。';
  } else { status.textContent = '等待老师发题'; hint.textContent = '抢答开放后按钮会自动亮起。'; }
}

function buzz() {
  const room = studentState.room;
  if (!room?.canBuzz || studentState.buzzPending || !room.round) return;
  studentState.buzzPending = true;
  student$('#studentBuzzButton').disabled = true;
  student$('#studentBuzzHint').textContent = '抢答信号已发送，等待服务器判定…';
  sendStudentSocket({type: 'quiz.buzz', sequence: ++studentState.sequence, roundId: room.round.roundId});
}

function renderStudentWinner(room) {
  const target = student$('#studentWinnerCard');
  if (room.round?.winnerName) {
    const self = room.round.winnerId === room.selfMemberId;
    target.innerHTML = `<div><div class="quiz-winner-avatar">${quizEscape(quizInitial(room.round.winnerName))}</div><span>${self ? '你抢到了' : '获得回答权'}</span><strong>${quizEscape(room.round.winnerName)}</strong></div>`;
  } else if (room.state === 'TIMED_OUT') target.innerHTML = '<div><span>抢答时间结束</span><strong>无人抢答</strong></div>';
  else if (room.state === 'REVEALED') target.innerHTML = '<div><span>本题已经完成</span><strong>查看答案解析</strong></div>';
  else target.innerHTML = `<span>${room.state === 'OPEN' ? '抢答进行中' : '等待老师开放抢答'}</span>`;
  student$('#studentAttemptList').innerHTML = (room.round?.attempts || []).map(attempt =>
    `<div class="quiz-attempt-row"><span>${quizEscape(attempt.displayName)}</span><b>${attempt.result === 'WRONG' ? '回答错误' : attempt.result === 'CORRECT' ? '回答正确' : '老师揭晓'}</b></div>`
  ).join('');
}

function renderStudentScores(room) {
  student$('#studentScoreList').innerHTML = room.members.filter(member => !member.owner).map((member, index) => `
    <div class="quiz-member-row"><span class="quiz-member-avatar ${member.online ? '' : 'offline'}">${index + 1}</span><strong>${quizEscape(member.displayName)}${member.memberId === room.selfMemberId ? ' <small>我</small>' : ''}</strong><span class="quiz-member-score">${member.score}</span><span></span></div>`).join('') || '<p class="empty">还没有学生加入</p>';
}

function renderStudentAnswer(answer) {
  const panel = student$('#studentRevealedAnswer');
  if (!answer) { panel.classList.add('hidden'); student$('#studentAnswerContent').innerHTML = ''; return; }
  panel.classList.remove('hidden');
  student$('#studentAnswerContent').innerHTML = `<h3>直接答案</h3><strong>${quizEscape(answer.answer)}</strong><h3>为什么</h3><p>${quizEscape(answer.explanation)}</p>${answer.example ? `<h3>示例</h3><pre>${quizEscape(answer.example)}</pre>` : ''}${answer.pitfall ? `<h3>容易混淆</h3><p>${quizEscape(answer.pitfall)}</p>` : ''}`;
}

async function leaveQuizRoom() {
  try {
    await quizRequest(`/api/tools/quiz/rooms/${studentState.roomCode}/leave`, {method: 'POST', token: studentState.token});
  } catch (_) {}
  clearStudentSession(studentState.roomCode);
  disconnectStudentSocket(true);
  location.href = '/quiz-join.html';
}

function connectStudentSocket() {
  disconnectStudentSocket(true);
  studentState.closedByClient = false;
  setStudentSocketState('connecting', '连接中');
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const query = new URLSearchParams({room: studentState.roomCode, token: studentState.token});
  const socket = new WebSocket(`${protocol}//${location.host}/ws/tools/quiz?${query}`);
  studentState.socket = socket;
  socket.onopen = () => setStudentSocketState('online', '实时连接');
  socket.onmessage = event => handleStudentSocketMessage(event.data);
  socket.onerror = () => setStudentSocketState('error', '连接异常');
  socket.onclose = () => {
    if (studentState.socket !== socket) return;
    setStudentSocketState('error', '断线重连中');
    if (!studentState.closedByClient && studentState.roomCode) {
      clearTimeout(studentState.reconnectTimer);
      studentState.reconnectTimer = setTimeout(connectStudentSocket, 1500);
    }
  };
}

function handleStudentSocketMessage(raw) {
  let message; try { message = JSON.parse(raw); } catch (_) { return; }
  if (message.type === 'room.snapshot') applyStudentRoom(message.payload);
  else if (message.type === 'room.error') { studentState.buzzPending = false; quizToast(message.payload?.message || '抢答失败'); }
  else if (message.type === 'room.closed' || message.type === 'room.revoked') {
    clearStudentSession(studentState.roomCode);
    quizToast(message.payload?.message || '房间已关闭');
    setTimeout(() => location.href = '/quiz-join.html', 700);
  }
}

function sendStudentSocket(message) {
  const socket = studentState.socket;
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  else { studentState.buzzPending = false; quizToast('实时连接尚未恢复，请稍后再试'); }
}

function disconnectStudentSocket(manual) {
  clearTimeout(studentState.reconnectTimer); studentState.closedByClient = manual;
  const socket = studentState.socket; studentState.socket = null;
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'leaving');
}

function setStudentSocketState(kind, text) {
  const target = student$('#studentSocketState');
  target.className = `quiz-socket ${kind}`; target.innerHTML = `<i></i>${quizEscape(text)}`;
}

function renderStudentClock() {
  const room = studentState.room;
  const target = student$('#studentClock');
  if (!room?.round) { target.textContent = '—'; return; }
  const now = Date.now() + studentState.serverOffset;
  if (room.state === 'COUNTDOWN' && room.round.countdownEndsAt) target.textContent = `${Math.max(1, Math.ceil((room.round.countdownEndsAt - now) / 1000))}`;
  else if (room.state === 'OPEN' && room.round.endsAt) target.textContent = `${Math.max(0, Math.ceil((room.round.endsAt - now) / 1000))}s`;
  else target.textContent = room.state === 'ANSWERING' ? '已锁定' : '—';
}

function studentSessionKey(roomCode) { return `dataforge.quiz.student.${roomCode || ''}`; }
function saveStudentSession(roomCode, token, displayName) { localStorage.setItem(studentSessionKey(roomCode), JSON.stringify({token, displayName})); }
function readStudentSession(roomCode) { try { return JSON.parse(localStorage.getItem(studentSessionKey(roomCode))); } catch (_) { return null; } }
function clearStudentSession(roomCode) { if (roomCode) localStorage.removeItem(studentSessionKey(roomCode)); }
function setStudentBusy(button, busy) { if (!button) return; button.disabled = busy; button.querySelector('span').textContent = busy ? '进入中…' : '进入课堂'; }
