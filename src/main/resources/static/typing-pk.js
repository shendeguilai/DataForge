const typing$ = (selector) => document.querySelector(selector);
const TYPING_TAB_WIDTH = 4;

const typingState = {
  user: null,
  publicRooms: [],
  room: null,
  token: null,
  selectedRoomId: null,
  socket: null,
  socketClosedByClient: false,
  reconnectTimer: null,
  sequence: 0,
  composing: false,
  lastLocalInputAt: 0,
  lastSnapshotAt: 0,
  controlSignature: '',
  roomPoller: null,
  soundEnabled: localStorage.getItem('dataforge.typing.sound') === 'on',
  audioContext: null,
  lastCountdownSecond: null,
  lastRenderedState: null,
  lastRenderedRoundId: null,
  playerRenderStats: new Map()
};

document.addEventListener('DOMContentLoaded', bootTypingPk);

async function bootTypingPk() {
  bindTypingEvents();
  updateSoundToggle();
  typingState.user = await currentUser();
  await loadRooms();
  typingState.roomPoller = setInterval(() => {
    if (!typingState.room) loadRooms(true);
  }, 5000);
  setInterval(updateLocalClock, 100);
  setInterval(() => sendSocketMessage({type: 'ping'}), 20000);

  const roomId = new URLSearchParams(location.search).get('room');
  if (roomId) await tryEnterRoom(roomId, true);
}

function bindTypingEvents() {
  window.addEventListener('dataforge:auth-changed', event => {
    typingState.user = event.detail;
    if (!typingState.room) typing$('#guestDisplayName').value = event.detail?.username || '';
  });
  typing$('#createRoomButton').onclick = () => {
    if (!typingState.user) {
      typingToast('请先登录或注册，再创建房间。');
      return;
    }
    openModal(typing$('#createRoomModal'));
    setTimeout(() => typing$('#newRoomName').focus(), 0);
  };
  typing$('#refreshRoomsButton').onclick = () => loadRooms();
  typing$('#createRoomForm').addEventListener('submit', createRoom);
  typing$('#joinRoomForm').addEventListener('submit', joinRoom);
  typing$('#battleForm').addEventListener('submit', startBattle);
  typing$('#randomArticleButton').onclick = selectRandomArticle;
  typing$('#articleSelect').onchange = updateArticleMeta;
  typing$('#copyInviteButton').onclick = copyInviteCode;
  typing$('#rotateInviteButton').onclick = rotateInviteCode;
  typing$('#endBattleButton').onclick = endBattle;
  typing$('#resetBattleButton').onclick = resetBattle;
  typing$('#closeRoomButton').onclick = closeRoom;
  typing$('#backToLobbyButton').onclick = leaveRoomView;
  typing$('#soundToggleButton').onclick = toggleSound;
  document.querySelectorAll('[data-reaction]').forEach(button => {
    button.onclick = () => sendReaction(button.dataset.reaction, button);
  });
  typing$('#typingInput').addEventListener('compositionstart', () => typingState.composing = true);
  typing$('#typingInput').addEventListener('compositionend', () => {
    typingState.composing = false;
    submitTypingInput();
  });
  typing$('#typingInput').addEventListener('input', submitTypingInput);
  typing$('#typingInput').addEventListener('keydown', handleTypingKeydown);
  typing$('#typingInput').addEventListener('paste', blockInsertedContent);
  typing$('#typingInput').addEventListener('drop', blockInsertedContent);
  typing$('#typingInput').addEventListener('focus', moveCaretToEnd);
  typing$('#typingInput').addEventListener('click', moveCaretToEnd);
  document.querySelectorAll('[data-close-modal]').forEach(button => {
    button.onclick = () => closeModal(button.closest('.modal-view'));
  });
  document.querySelectorAll('.modal-view').forEach(modal => {
    modal.addEventListener('click', event => {
      if (event.target === modal) closeModal(modal);
    });
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') document.querySelectorAll('.modal-view:not(.hidden)').forEach(closeModal);
  });
  document.addEventListener('pointerdown', unlockAudio, {once: true});
  window.addEventListener('beforeunload', () => disconnectSocket(true));
}

async function currentUser() {
  try {
    return await typingApi('/api/auth/me');
  } catch (_) {
    return null;
  }
}

async function loadRooms(silent = false) {
  if (!silent) typing$('#roomList').innerHTML = '<div class="room-list-state">正在读取房间列表…</div>';
  try {
    typingState.publicRooms = await typingApi('/api/tools/typing/rooms');
    renderRoomList();
  } catch (error) {
    if (!silent) typing$('#roomList').innerHTML = `<div class="room-list-state">${escapeHtml(error.message)}</div>`;
  }
}

function renderRoomList() {
  const rooms = typingState.publicRooms;
  typing$('#roomCount').textContent = `${rooms.length} 个房间`;
  typing$('#waitingRoomCount').textContent = rooms.filter(room => room.state === 'WAITING').length;
  typing$('#runningRoomCount').textContent = rooms.filter(room => ['COUNTDOWN', 'RUNNING'].includes(room.state)).length;
  typing$('#onlineRoomMemberCount').textContent = rooms.reduce((sum, room) => sum + Number(room.onlineCount || 0), 0);
  if (!rooms.length) {
    typing$('#roomList').innerHTML = `<div class="room-list-state empty-state">
      <b class="empty-keyboard">⌨</b><strong>赛场正在等待第一声键响</strong>
      <span>登录后创建房间，把邀请码发给同学，就可以开始一场实时打字 PK。</span>
    </div>`;
    return;
  }
  typing$('#roomList').innerHTML = rooms.map(room => `
    <article class="room-row" data-room-id="${escapeAttr(room.roomId)}" tabindex="0" role="button" aria-label="进入 ${escapeAttr(room.name)}">
      <div class="room-row-top"><span class="state-pill ${String(room.state).toLowerCase()}">${stateLabel(room.state)}</span><span>${room.onlineCount} / ${room.capacity} 在线</span></div>
      <h3>${escapeHtml(room.name)}</h3>
      <div class="room-row-meta"><span>房主 ${escapeHtml(room.ownerName)}</span></div>
      <div class="room-row-capacity"><i style="width:${Math.min(100, room.onlineCount * 100 / room.capacity)}%"></i></div>
      <div class="room-row-footer"><span>${room.state === 'RUNNING' ? '可中途加入观战' : room.state === 'COUNTDOWN' ? '即将开赛' : '邀请码进入'}</span><span class="room-enter">进入赛场 <b>→</b></span></div>
    </article>
  `).join('');
  typing$('#roomList').querySelectorAll('[data-room-id]').forEach(row => {
    row.onclick = () => tryEnterRoom(row.dataset.roomId);
    row.onkeydown = event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        tryEnterRoom(row.dataset.roomId);
      }
    };
  });
}

async function tryEnterRoom(roomId, fromUrl = false) {
  const publicRoom = typingState.publicRooms.find(room => room.roomId === roomId);
  const saved = readRoomSession(roomId);
  try {
    if (publicRoom && typingState.user && publicRoom.ownerName.toLowerCase() === typingState.user.username.toLowerCase()) {
      const room = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(roomId)}`);
      enterRoom(room, null);
      return;
    }
    if (saved?.token) {
      const room = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(roomId)}`, {token: saved.token});
      enterRoom(room, saved.token);
      return;
    }
  } catch (_) {
    clearRoomSession(roomId);
  }

  if (!publicRoom) {
    if (fromUrl) {
      history.replaceState(null, '', '/typing-pk.html');
      typingToast('房间不存在、已关闭或身份已经失效。');
    }
    return;
  }
  typingState.selectedRoomId = roomId;
  typing$('#joinRoomTitle').textContent = `加入「${publicRoom.name}」`;
  typing$('#guestDisplayName').value = typingState.user?.username || '';
  typing$('#guestInviteCode').value = '';
  openModal(typing$('#joinRoomModal'));
  setTimeout(() => (typing$('#guestDisplayName').value ? typing$('#guestInviteCode') : typing$('#guestDisplayName')).focus(), 0);
}

async function createRoom(event) {
  event.preventDefault();
  const button = event.submitter;
  setButtonBusy(button, true, '创建中');
  try {
    const room = await typingApi('/api/tools/typing/rooms', {
      method: 'POST',
      body: {name: typing$('#newRoomName').value.trim()}
    });
    closeModal(typing$('#createRoomModal'));
    typing$('#createRoomForm').reset();
    enterRoom(room, null);
  } catch (error) {
    typingToast(error.message);
  } finally {
    setButtonBusy(button, false);
  }
}

async function joinRoom(event) {
  event.preventDefault();
  const roomId = typingState.selectedRoomId;
  const button = event.submitter;
  if (!roomId) return;
  setButtonBusy(button, true, '进入中');
  try {
    const joined = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(roomId)}/join`, {
      method: 'POST',
      body: {
        displayName: typing$('#guestDisplayName').value.trim(),
        inviteCode: typing$('#guestInviteCode').value.trim()
      }
    });
    saveRoomSession(roomId, joined.token, typing$('#guestDisplayName').value.trim());
    closeModal(typing$('#joinRoomModal'));
    typing$('#joinRoomForm').reset();
    enterRoom(joined.room, joined.token);
  } catch (error) {
    typingToast(error.message);
  } finally {
    setButtonBusy(button, false);
  }
}

function enterRoom(room, token) {
  typingState.room = room;
  typingState.token = token;
  typingState.sequence = 0;
  typingState.controlSignature = '';
  typingState.lastRenderedState = null;
  typingState.lastRenderedRoundId = null;
  typingState.lastCountdownSecond = null;
  typingState.playerRenderStats.clear();
  history.replaceState(null, '', `?room=${encodeURIComponent(room.roomId)}`);
  typing$('#lobbyView').classList.add('hidden');
  typing$('#roomView').classList.remove('hidden');
  applyRoomSnapshot(room);
  connectSocket();
}

async function leaveRoomView() {
  if (!typingState.room) return showLobby();
  if (!typingState.room.owner && typingState.token) {
    try {
      await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(typingState.room.roomId)}/leave`, {
        method: 'POST', token: typingState.token
      });
      clearRoomSession(typingState.room.roomId);
    } catch (error) {
      typingToast(error.message);
      return;
    }
  }
  showLobby();
}

function showLobby() {
  disconnectSocket(true);
  typingState.room = null;
  typingState.token = null;
  typingState.controlSignature = '';
  typingState.lastRenderedState = null;
  typingState.lastRenderedRoundId = null;
  typingState.playerRenderStats.clear();
  history.replaceState(null, '', '/typing-pk.html');
  typing$('#roomView').classList.add('hidden');
  typing$('#lobbyView').classList.remove('hidden');
  loadRooms();
}

function connectSocket() {
  disconnectSocket(true);
  if (!typingState.room) return;
  typingState.socketClosedByClient = false;
  setSocketState('connecting', '连接中');
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const query = new URLSearchParams({roomId: typingState.room.roomId});
  if (typingState.token) query.set('token', typingState.token);
  const socket = new WebSocket(`${protocol}//${location.host}/ws/tools/typing?${query}`);
  typingState.socket = socket;
  typingState.sequence = 0;
  socket.onopen = () => {
    setSocketState('online', '实时连接');
    if (typingState.room) renderReactionControls(typingState.room);
  };
  socket.onmessage = event => handleSocketMessage(event.data);
  socket.onerror = () => setSocketState('error', '连接异常');
  socket.onclose = () => {
    if (typingState.socket !== socket) return;
    setSocketState('error', '已断线，重连中');
    if (typingState.room) renderReactionControls(typingState.room);
    if (!typingState.socketClosedByClient && typingState.room) {
      clearTimeout(typingState.reconnectTimer);
      typingState.reconnectTimer = setTimeout(connectSocket, 1500);
    }
  };
}

function disconnectSocket(manual) {
  clearTimeout(typingState.reconnectTimer);
  typingState.socketClosedByClient = manual;
  const socket = typingState.socket;
  typingState.socket = null;
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'leaving');
}

function handleSocketMessage(rawMessage) {
  let message;
  try {
    message = JSON.parse(rawMessage);
  } catch (_) {
    return;
  }
  if (message.type === 'room.snapshot') {
    applyRoomSnapshot(message.payload);
    return;
  }
  if (message.type === 'room.reaction') {
    showReaction(message.payload);
    return;
  }
  if (message.type === 'room.error') {
    typingToast(message.payload?.message || '输入同步失败');
    return;
  }
  if (message.type === 'room.closed' || message.type === 'room.revoked') {
    if (typingState.room) clearRoomSession(typingState.room.roomId);
    typingToast(message.payload?.message || '房间已关闭');
    setTimeout(showLobby, 500);
  }
}

function applyRoomSnapshot(room) {
  if (!room || !typingState.room || room.roomId !== typingState.room.roomId) return;
  const previousState = typingState.lastRenderedState;
  const previousRoundId = typingState.lastRenderedRoundId;
  const roundId = room.battle?.roundId || null;
  if (roundId !== previousRoundId) {
    typingState.playerRenderStats.clear();
    typingState.lastCountdownSecond = null;
  }
  typingState.room = room;
  typingState.lastSnapshotAt = Date.now();
  typing$('#roomName').textContent = room.name;
  typing$('#roomOwner').textContent = room.ownerName;
  typing$('#roomOnlineCount').textContent = room.members.filter(member => member.online).length;
  typing$('#roomState').textContent = stateLabel(room.state);
  typing$('#roomState').className = `state-pill ${room.state.toLowerCase()}`;
  renderOwnerControls(room);
  renderMembers(room);
  renderHistory(room);
  renderBattle(room);
  renderReactionControls(room);
  playStateTransition(previousState, previousRoundId, room);
  typingState.lastRenderedState = room.state;
  typingState.lastRenderedRoundId = roundId;
}

function renderOwnerControls(room) {
  const panel = typing$('#ownerPanel');
  panel.classList.toggle('hidden', !room.owner);
  if (!room.owner) return;
  typing$('#inviteCode').textContent = room.inviteCode || '------';
  const waiting = room.state === 'WAITING';
  const activeBattle = room.state === 'COUNTDOWN' || room.state === 'RUNNING';
  typing$('#battleForm').classList.toggle('hidden', room.state === 'FINISHED');
  typing$('#endBattleButton').classList.toggle('hidden', !activeBattle);
  typing$('#resetBattleButton').classList.toggle('hidden', room.state !== 'FINISHED');

  const signature = [room.state, ...room.members.map(member => `${member.memberId}:${member.online}`),
    ...room.articles.map(article => `${article.id}:${article.title}:${article.category}:${article.length}`)].join('|');
  if (signature !== typingState.controlSignature) {
    const oldLeft = typing$('#leftPlayerSelect').value;
    const oldRight = typing$('#rightPlayerSelect').value;
    const oldArticle = typing$('#articleSelect').value;
    const onlineMembers = room.members.filter(member => member.online);
    const options = room.members.map(member =>
      `<option value="${escapeAttr(member.memberId)}" ${member.online ? '' : 'disabled'}>${escapeHtml(member.displayName)}${member.owner ? '（房主）' : ''}${member.online ? '' : '（离线）'}</option>`
    ).join('');
    typing$('#leftPlayerSelect').innerHTML = `<option value="">选择选手</option>${options}`;
    typing$('#rightPlayerSelect').innerHTML = `<option value="">选择选手</option>${options}`;
    const categoryOrder = ['中文', '英文', '代码'];
    typing$('#articleSelect').innerHTML = categoryOrder.map(category => {
      const items = room.articles.filter(article => article.category === category);
      if (!items.length) return '';
      return `<optgroup label="${category}">${items.map(article =>
        `<option value="${escapeAttr(article.id)}">${escapeHtml(article.title)}</option>`
      ).join('')}</optgroup>`;
    }).join('');
    typing$('#leftPlayerSelect').value = room.members.some(member => member.memberId === oldLeft && member.online)
      ? oldLeft : onlineMembers[0]?.memberId || '';
    typing$('#rightPlayerSelect').value = room.members.some(member => member.memberId === oldRight && member.online && member.memberId !== typing$('#leftPlayerSelect').value)
      ? oldRight : onlineMembers.find(member => member.memberId !== typing$('#leftPlayerSelect').value)?.memberId || '';
    typing$('#articleSelect').value = room.articles.some(article => article.id === oldArticle)
      ? oldArticle : room.articles[0]?.id || '';
    typingState.controlSignature = signature;
    updateArticleMeta();
  }
  typing$('#battleForm').querySelectorAll('select, button').forEach(control => control.disabled = !waiting);
}

function renderMembers(room) {
  typing$('#memberCount').textContent = `${room.members.length} / 30`;
  typing$('#memberList').innerHTML = room.members.map(member => {
    const role = member.owner ? '房主' : member.playerSide ? (member.playerSide === 'LEFT' ? '左侧选手' : '右侧选手') : '观众';
    const canKick = room.owner && !member.owner && !['COUNTDOWN', 'RUNNING'].includes(room.state);
    const sideClass = member.playerSide === 'LEFT' ? 'player-left' : member.playerSide === 'RIGHT' ? 'player-right' : '';
    return `<div class="member-row ${member.online ? 'online' : ''} ${sideClass}">
      <i></i><span class="member-avatar">${escapeHtml(playerInitial(member.displayName))}</span><strong>${escapeHtml(member.displayName)} <small>${role}${member.online ? '' : ' · 离线'}</small></strong>
      ${canKick ? `<button class="kick-button" data-kick="${escapeAttr(member.memberId)}" type="button">移除</button>` : ''}
    </div>`;
  }).join('');
  typing$('#memberList').querySelectorAll('[data-kick]').forEach(button => {
    button.onclick = () => kickMember(button.dataset.kick);
  });
}

function renderHistory(room) {
  const history = room.history || [];
  typing$('#historyPanel').classList.toggle('hidden', !history.length);
  typing$('#historyList').innerHTML = history.map(item => `
    <div class="history-item ${item.winnerId ? 'win' : ''}">
      <strong>${item.finishReason === 'MANUAL' ? '本局已手动结束' : item.winnerName ? `${escapeHtml(item.winnerName)} 获胜` : '本局平手'}</strong>
      <span>${escapeHtml(item.articleTitle)} · ${finishReasonLabel(item.finishReason)}</span>
      <span>${escapeHtml(item.left.displayName)} ${item.left.cpm} CPM / ${escapeHtml(item.right.displayName)} ${item.right.cpm} CPM · 最佳连击 ${Math.max(item.left.bestCombo || 0, item.right.bestCombo || 0)}</span>
    </div>
  `).join('');
}

function renderBattle(room) {
  const battle = room.battle;
  const running = room.state === 'RUNNING';
  const finished = room.state === 'FINISHED';
  const countdown = room.state === 'COUNTDOWN';
  typing$('#waitingNote').classList.toggle('hidden', Boolean(battle));
  typing$('#resultStrip').classList.toggle('hidden', !finished);

  if (!battle) {
    typing$('.arena').classList.remove('code-article');
    typing$('#articleCategory').textContent = '等待房主设置';
    typing$('#articleTitle').textContent = '比赛尚未开始';
    resetDuelHeader();
    resetPlayerScreen(typing$('#leftPlayerScreen'), '左侧选手', 'L');
    resetPlayerScreen(typing$('#rightPlayerScreen'), '右侧选手', 'R');
    typing$('#typingDock').classList.add('hidden');
    typing$('#countdownOverlay').classList.add('hidden');
    typing$('#resultStrip').innerHTML = '';
    delete typing$('#resultStrip').dataset.roundId;
    return;
  }

  typing$('#articleCategory').textContent = battle.article.category;
  typing$('#articleTitle').textContent = battle.article.title;
  typing$('.arena').classList.toggle('code-article', battle.article.category === '代码');
  renderDuelHeader(room, battle);
  renderPlayer(typing$('#leftPlayerScreen'), battle.left, battle.article.content, room, 'LEFT');
  renderPlayer(typing$('#rightPlayerScreen'), battle.right, battle.article.content, room, 'RIGHT');
  typing$('#countdownOverlay').classList.toggle('hidden', !countdown);

  if (finished) {
    renderResult(battle);
  }

  const typingDock = typing$('#typingDock');
  typingDock.classList.toggle('hidden', !(running && room.canType));
  if (running && room.canType) {
    const own = battle.left.memberId === room.selfMemberId ? battle.left : battle.right;
    const input = typing$('#typingInput');
    input.removeAttribute('maxlength');
    if (!typingState.composing && Date.now() - typingState.lastLocalInputAt > 120 && input.value !== own.input) {
      input.value = own.input;
    }
    if (document.activeElement !== input) setTimeout(() => input.focus(), 0);
  } else if (!running) {
    typing$('#typingInput').value = '';
  }
}

function renderPlayer(screen, player, content, room, side) {
  const previous = typingState.playerRenderStats.get(player.memberId);
  screen.querySelector('.player-name').textContent = player.displayName;
  screen.classList.toggle('online', player.online);
  screen.classList.toggle('winner', room.state === 'FINISHED' && room.battle.winnerId === player.memberId);
  screen.classList.toggle('self-player', room.selfMemberId === player.memberId);
  screen.querySelector('.self-badge').classList.toggle('hidden', room.selfMemberId !== player.memberId);
  const combo = Number(player.currentCombo || 0);
  const comboBadge = screen.querySelector('.combo-badge');
  comboBadge.textContent = `${combo} COMBO`;
  comboBadge.classList.toggle('hot', combo >= 5);
  if (previous && combo > previous.combo && (combo === 5 || combo % 10 === 0)) {
    comboBadge.classList.remove('punch');
    void comboBadge.offsetWidth;
    comboBadge.classList.add('punch');
    if (room.selfMemberId === player.memberId) playCue('combo');
  }
  if (previous && player.errors > previous.errors) {
    screen.classList.remove('mistake-pulse');
    void screen.offsetWidth;
    screen.classList.add('mistake-pulse');
  }
  const copy = screen.querySelector('.typing-copy');
  if (!content) {
    copy.className = 'typing-copy empty-copy';
    copy.textContent = room.state === 'COUNTDOWN' ? '文章将在倒计时结束后显示' : '等待比赛开始';
  } else {
    copy.className = 'typing-copy';
    copy.innerHTML = renderTypingText(content, player.input, player.correctCount);
    if (!previous || player.correctCount !== previous.correctCount) {
      const currentCharacter = copy.querySelector('.wrong, .cursor-char');
      if (currentCharacter) {
        const focusOffset = currentCharacter.getBoundingClientRect().top
          - copy.getBoundingClientRect().top + copy.scrollTop;
        copy.scrollTop = Math.max(0, focusOffset - copy.clientHeight * .32);
      }
    }
  }
  screen.querySelector('.player-progress i').style.width = `${Math.min(100, player.progress)}%`;
  screen.querySelector('[data-stat="progress"]').textContent = `${player.progress}%`;
  screen.querySelector('[data-stat="cpm"]').textContent = `${player.cpm} CPM`;
  screen.querySelector('[data-stat="accuracy"]').textContent = `${Number(player.accuracy).toFixed(player.accuracy % 1 ? 1 : 0)}%`;
  screen.querySelector('[data-stat="errors"]').textContent = player.errors;
  typingState.playerRenderStats.set(player.memberId, {
    combo,
    errors: player.errors,
    correctCount: player.correctCount,
    side
  });
}

function resetPlayerScreen(screen, name) {
  screen.classList.remove('online', 'winner', 'leading', 'self-player', 'mistake-pulse');
  screen.querySelector('.player-name').textContent = name;
  screen.querySelector('.self-badge').classList.add('hidden');
  const comboBadge = screen.querySelector('.combo-badge');
  comboBadge.textContent = '0 COMBO';
  comboBadge.classList.remove('hot', 'punch');
  const copy = screen.querySelector('.typing-copy');
  copy.className = 'typing-copy empty-copy';
  copy.textContent = '等待房主选择参赛者';
  screen.querySelector('.player-progress i').style.width = '0%';
  screen.querySelector('[data-stat="progress"]').textContent = '0%';
  screen.querySelector('[data-stat="cpm"]').textContent = '0 CPM';
  screen.querySelector('[data-stat="accuracy"]').textContent = '0%';
  screen.querySelector('[data-stat="errors"]').textContent = '0';
}

function renderDuelHeader(room, battle) {
  const left = battle.left;
  const right = battle.right;
  const leftInitial = playerInitial(left.displayName);
  const rightInitial = playerInitial(right.displayName);
  typing$('#leftDuelistName').textContent = left.displayName;
  typing$('#rightDuelistName').textContent = right.displayName;
  typing$('#leftDuelistAvatar').textContent = leftInitial;
  typing$('#rightDuelistAvatar').textContent = rightInitial;
  typing$('#leftRaceMarker').textContent = leftInitial;
  typing$('#rightRaceMarker').textContent = rightInitial;

  const score = recentPairScore(room.history || [], left.memberId, right.memberId);
  typing$('#leftSeriesScore').textContent = `近 5 局 · ${score.leftWins} 胜${score.draws ? ` · ${score.draws} 平` : ''}`;
  typing$('#rightSeriesScore').textContent = `近 5 局 · ${score.rightWins} 胜${score.draws ? ` · ${score.draws} 平` : ''}`;

  updateRaceLane('left', left.progress);
  updateRaceLane('right', right.progress);
  const leftCount = typedCharacterCount(battle.article.content, left.correctCount);
  const rightCount = typedCharacterCount(battle.article.content, right.correctCount);
  const difference = leftCount - rightCount;
  const lead = typing$('#leadIndicator');
  const leftScreen = typing$('#leftPlayerScreen');
  const rightScreen = typing$('#rightPlayerScreen');
  leftScreen.classList.toggle('leading', difference > 0);
  rightScreen.classList.toggle('leading', difference < 0);
  if (difference > 0) {
    lead.className = 'race-lead left';
    lead.textContent = `${left.displayName} 领先 ${difference} 字符`;
  } else if (difference < 0) {
    lead.className = 'race-lead right';
    lead.textContent = `${right.displayName} 领先 ${Math.abs(difference)} 字符`;
  } else {
    lead.className = 'race-lead neutral';
    lead.textContent = leftCount ? '双方并驾齐驱' : room.state === 'COUNTDOWN' ? '即将起跑' : '双方同一起跑线';
  }
}

function resetDuelHeader() {
  typing$('#leftDuelistName').textContent = '左侧选手';
  typing$('#rightDuelistName').textContent = '右侧选手';
  typing$('#leftDuelistAvatar').textContent = 'L';
  typing$('#rightDuelistAvatar').textContent = 'R';
  typing$('#leftSeriesScore').textContent = '近 5 局 · 0 胜';
  typing$('#rightSeriesScore').textContent = '近 5 局 · 0 胜';
  typing$('#leftRaceMarker').textContent = 'L';
  typing$('#rightRaceMarker').textContent = 'R';
  updateRaceLane('left', 0);
  updateRaceLane('right', 0);
  typing$('#leadIndicator').className = 'race-lead neutral';
  typing$('#leadIndicator').textContent = '等待选手起跑';
}

function updateRaceLane(side, progress) {
  const safeProgress = Math.max(0, Math.min(100, Number(progress || 0)));
  typing$(`#${side}RaceProgress`).style.width = `${safeProgress}%`;
  typing$(`#${side}RaceMarker`).style.left = `${Math.max(1.5, Math.min(98.5, safeProgress))}%`;
  typing$(`#${side}RacePercent`).textContent = `${Math.round(safeProgress)}%`;
}

function recentPairScore(history, leftId, rightId) {
  const matches = history.filter(item => {
    const participants = [item.left?.memberId, item.right?.memberId];
    return participants.includes(leftId) && participants.includes(rightId);
  }).slice(0, 5);
  return matches.reduce((score, item) => {
    if (item.winnerId === leftId) score.leftWins++;
    else if (item.winnerId === rightId) score.rightWins++;
    else score.draws++;
    return score;
  }, {leftWins: 0, rightWins: 0, draws: 0});
}

function renderResult(battle) {
  const strip = typing$('#resultStrip');
  const neutral = battle.finishReason === 'MANUAL' || !battle.winnerId;
  strip.classList.toggle('neutral', neutral);
  if (strip.dataset.roundId === battle.roundId) return;
  strip.dataset.roundId = battle.roundId;
  const winnerText = battle.finishReason === 'MANUAL' ? '本局已由房主结束'
    : battle.winnerName ? `${battle.winnerName} 获胜` : '本局平手';
  const icon = battle.finishReason === 'MANUAL' ? '■' : battle.winnerId ? '🏆' : '🤝';
  const duration = formatDuration(Math.max(battle.left.elapsedMillis, battle.right.elapsedMillis));
  const confetti = neutral ? '' : Array.from({length: 20}, (_, index) => {
    const colors = ['#ff665f', '#27b8ad', '#d9f450', '#ffffff'];
    return `<i class="confetti" style="--confetti-x:${3 + (index * 17) % 94}%;--confetti-color:${colors[index % colors.length]};--confetti-delay:${(index % 6) * .08}s"></i>`;
  }).join('');
  strip.innerHTML = `${confetti}<div class="result-main"><span>${icon}</span><div><strong>${escapeHtml(winnerText)}</strong><br><small>${finishReasonLabel(battle.finishReason)} · ${duration}</small></div></div>
    <div class="result-stats"><span>${escapeHtml(battle.left.displayName)} ${battle.left.cpm} CPM · ${formatAccuracy(battle.left.accuracy)} · 最佳连击 ${battle.left.bestCombo || 0}</span><span>${escapeHtml(battle.right.displayName)} ${battle.right.cpm} CPM · ${formatAccuracy(battle.right.accuracy)} · 最佳连击 ${battle.right.bestCombo || 0}</span></div>`;
}

function typedCharacterCount(content, correctIndex) {
  if (!content || !correctIndex) return 0;
  return Array.from(content.slice(0, correctIndex)).length;
}

function playerInitial(name) {
  return Array.from(String(name || '').trim())[0]?.toUpperCase() || '?';
}

function formatAccuracy(value) {
  const number = Number(value || 0);
  return `${number.toFixed(number % 1 ? 1 : 0)}%`;
}

function renderTypingText(article, input, correctCount) {
  const correct = article.slice(0, correctCount);
  const wrong = input.slice(correctCount);
  if (wrong) {
    return `<span class="correct">${escapeHtml(correct)}</span><span class="wrong" title="请退格修正">${escapeHtml(wrong)}</span><span class="remaining">${escapeHtml(article.slice(correctCount))}</span>`;
  }
  const pending = article.slice(correctCount);
  const cursor = Array.from(pending)[0] || '';
  const remaining = pending.slice(cursor.length);
  return `<span class="correct">${escapeHtml(correct)}</span>${cursor ? `<span class="cursor-char">${escapeHtml(cursor)}</span>` : ''}<span class="remaining">${escapeHtml(remaining)}</span>`;
}

function submitTypingInput() {
  if (typingState.composing || !typingState.room?.canType || typingState.room.state !== 'RUNNING') return;
  const article = typingState.room.battle?.article?.content;
  if (!article) return;
  const input = typing$('#typingInput');
  let value = input.value;
  const correct = commonPrefixLength(value, article);
  if (correct < value.length) {
    const wrongCharacter = Array.from(value.slice(correct))[0] || '';
    value = value.slice(0, correct) + wrongCharacter;
  }
  if (input.value !== value) input.value = value;
  moveCaretToEnd();
  typingState.lastLocalInputAt = Date.now();
  sendSocketMessage({type: 'typing.input', sequence: ++typingState.sequence, text: value});
}

function handleTypingKeydown(event) {
  if (event.key !== 'Tab' || !typingState.room?.canType || typingState.room.state !== 'RUNNING') return;
  event.preventDefault();
  if (typingState.composing) return;

  const article = typingState.room.battle?.article?.content;
  const input = typing$('#typingInput');
  if (!article || !input) return;

  if (event.shiftKey) {
    const trailingSpaces = Math.min(TYPING_TAB_WIDTH, input.value.length - input.value.trimEnd().length);
    if (!trailingSpaces) return;
    input.value = input.value.slice(0, -trailingSpaces);
  } else {
    const correct = commonPrefixLength(input.value, article);
    if (correct < input.value.length) {
      typingToast('请先退格修正当前错字。');
      return;
    }
    let expectedSpaces = 0;
    while (expectedSpaces < TYPING_TAB_WIDTH && article[correct + expectedSpaces] === ' ') {
      expectedSpaces++;
    }
    input.value += ' '.repeat(expectedSpaces || TYPING_TAB_WIDTH);
  }

  moveCaretToEnd();
  submitTypingInput();
}

function sendSocketMessage(message) {
  if (typingState.socket?.readyState === WebSocket.OPEN) {
    typingState.socket.send(JSON.stringify(message));
  }
}

function sendReaction(emoji, button) {
  if (!canSendReaction(typingState.room)) {
    typingToast(typingState.room?.canType ? '参赛者比赛中请专注输入，结束后再庆祝。' : '实时连接建立后才能发送助威。');
    return;
  }
  sendSocketMessage({type: 'room.reaction', emoji});
  button?.classList.remove('punch');
  if (button) {
    void button.offsetWidth;
    button.classList.add('punch');
  }
}

function canSendReaction(room) {
  if (!room || typingState.socket?.readyState !== WebSocket.OPEN) return false;
  const self = room.members.find(member => member.memberId === room.selfMemberId);
  if (!self) return false;
  return !(['COUNTDOWN', 'RUNNING'].includes(room.state) && Boolean(self.playerSide));
}

function renderReactionControls(room) {
  const enabled = canSendReaction(room);
  typing$('#reactionDock').classList.toggle('disabled', !enabled);
  document.querySelectorAll('[data-reaction]').forEach(button => button.disabled = !enabled);
  const self = room.members.find(member => member.memberId === room.selfMemberId);
  typing$('#reactionHelp').textContent = enabled ? '点击表情为选手加油'
    : self?.playerSide && ['COUNTDOWN', 'RUNNING'].includes(room.state) ? '参赛期间专注输入，观众正在为你助威' : '实时连接建立后即可助威';
}

function showReaction(reaction) {
  if (!reaction?.emoji) return;
  const layer = typing$('#reactionLayer');
  while (layer.children.length >= 18) layer.firstElementChild?.remove();
  const burst = document.createElement('div');
  burst.className = 'reaction-burst';
  burst.style.setProperty('--reaction-x', `${12 + Math.random() * 76}%`);
  burst.style.setProperty('--reaction-drift', `${-45 + Math.random() * 90}px`);
  const emoji = document.createElement('b');
  emoji.textContent = reaction.emoji;
  const sender = document.createElement('span');
  sender.textContent = reaction.displayName || '观众';
  burst.append(emoji, sender);
  layer.appendChild(burst);
  setTimeout(() => burst.remove(), 2800);
}

function toggleSound() {
  typingState.soundEnabled = !typingState.soundEnabled;
  localStorage.setItem('dataforge.typing.sound', typingState.soundEnabled ? 'on' : 'off');
  updateSoundToggle();
  if (typingState.soundEnabled) {
    unlockAudio();
    playCue('enabled');
  }
}

function updateSoundToggle() {
  const button = typing$('#soundToggleButton');
  if (!button) return;
  button.classList.toggle('enabled', typingState.soundEnabled);
  button.setAttribute('aria-pressed', String(typingState.soundEnabled));
  typing$('#soundToggleIcon').textContent = typingState.soundEnabled ? '♫' : '♪';
  typing$('#soundToggleLabel').textContent = typingState.soundEnabled ? '音效开启' : '音效关闭';
}

function unlockAudio() {
  if (!typingState.soundEnabled) return;
  const AudioContext = window.AudioContext || window.webkitAudioContext;
  if (!AudioContext) return;
  if (!typingState.audioContext) typingState.audioContext = new AudioContext();
  if (typingState.audioContext.state === 'suspended') typingState.audioContext.resume().catch(() => {});
}

function playCue(cue) {
  if (!typingState.soundEnabled) return;
  unlockAudio();
  const context = typingState.audioContext;
  if (!context || context.state !== 'running') return;
  const notes = {
    enabled: [[520, .06, 0], [690, .08, .07]],
    tick: [[440, .055, 0]],
    start: [[520, .07, 0], [660, .07, .08], [880, .12, .16]],
    combo: [[740, .055, 0], [980, .08, .055]],
    finish: [[660, .08, 0], [830, .08, .09], [1040, .18, .18]]
  }[cue] || [];
  notes.forEach(([frequency, duration, delay]) => playTone(context, frequency, duration, delay));
}

function playTone(context, frequency, duration, delay) {
  const oscillator = context.createOscillator();
  const gain = context.createGain();
  const start = context.currentTime + delay;
  oscillator.type = 'sine';
  oscillator.frequency.setValueAtTime(frequency, start);
  gain.gain.setValueAtTime(.0001, start);
  gain.gain.exponentialRampToValueAtTime(.075, start + .012);
  gain.gain.exponentialRampToValueAtTime(.0001, start + duration);
  oscillator.connect(gain).connect(context.destination);
  oscillator.start(start);
  oscillator.stop(start + duration + .02);
}

function playStateTransition(previousState, previousRoundId, room) {
  if (!previousState || previousRoundId !== (room.battle?.roundId || null)) return;
  if (previousState === 'COUNTDOWN' && room.state === 'RUNNING') playCue('start');
  if (previousState !== 'FINISHED' && room.state === 'FINISHED') playCue('finish');
}

function blockInsertedContent(event) {
  event.preventDefault();
  typingToast('比赛中不能粘贴或拖入文本。');
}

function moveCaretToEnd() {
  const input = typing$('#typingInput');
  if (document.activeElement !== input) return;
  const end = input.value.length;
  input.setSelectionRange(end, end);
}

async function startBattle(event) {
  event.preventDefault();
  const room = typingState.room;
  if (!room?.owner) return;
  const left = typing$('#leftPlayerSelect').value;
  const right = typing$('#rightPlayerSelect').value;
  if (!left || !right || left === right) {
    typingToast('请选择两名不同的在线成员。');
    return;
  }
  setButtonBusy(typing$('#startBattleButton'), true, '准备中');
  try {
    const snapshot = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(room.roomId)}/start`, {
      method: 'POST',
      body: {leftMemberId: left, rightMemberId: right, articleId: typing$('#articleSelect').value}
    });
    applyRoomSnapshot(snapshot);
  } catch (error) {
    typingToast(error.message);
  } finally {
    setButtonBusy(typing$('#startBattleButton'), false);
  }
}

async function resetBattle() {
  try {
    const room = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(typingState.room.roomId)}/reset`, {method: 'POST'});
    typingState.controlSignature = '';
    applyRoomSnapshot(room);
  } catch (error) {
    typingToast(error.message);
  }
}

async function endBattle() {
  const room = typingState.room;
  if (!room?.owner || !['COUNTDOWN', 'RUNNING'].includes(room.state)) return;
  if (!confirm('确定手动结束本局比赛吗？本局不会判定胜者。')) return;
  const button = typing$('#endBattleButton');
  setButtonBusy(button, true, '结束中');
  try {
    const snapshot = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(room.roomId)}/finish`, {method: 'POST'});
    applyRoomSnapshot(snapshot);
    typingToast('本局比赛已手动结束。');
  } catch (error) {
    typingToast(error.message);
  } finally {
    setButtonBusy(button, false);
  }
}

async function rotateInviteCode() {
  if (!confirm('旧邀请码会立即失效，确定重新生成吗？')) return;
  try {
    const room = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(typingState.room.roomId)}/invite/rotate`, {method: 'POST'});
    applyRoomSnapshot(room);
    typingToast('邀请码已更新。');
  } catch (error) {
    typingToast(error.message);
  }
}

async function copyInviteCode() {
  const code = typingState.room?.inviteCode;
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code);
    typingToast(`邀请码 ${code} 已复制。`);
  } catch (_) {
    typingToast(`邀请码：${code}`);
  }
}

async function kickMember(memberId) {
  const member = typingState.room.members.find(item => item.memberId === memberId);
  if (!member || !confirm(`确定将 ${member.displayName} 移出房间吗？`)) return;
  try {
    const room = await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(typingState.room.roomId)}/members/${encodeURIComponent(memberId)}`, {method: 'DELETE'});
    typingState.controlSignature = '';
    applyRoomSnapshot(room);
  } catch (error) {
    typingToast(error.message);
  }
}

async function closeRoom() {
  if (!confirm('关闭后所有成员都会离开，确定关闭房间吗？')) return;
  try {
    await typingApi(`/api/tools/typing/rooms/${encodeURIComponent(typingState.room.roomId)}`, {method: 'DELETE'});
    showLobby();
  } catch (error) {
    typingToast(error.message);
  }
}

function selectRandomArticle() {
  const options = Array.from(typing$('#articleSelect').options);
  if (!options.length) return;
  typing$('#articleSelect').value = options[Math.floor(Math.random() * options.length)].value;
  updateArticleMeta();
}

function updateArticleMeta() {
  const article = typingState.room?.articles?.find(item => item.id === typing$('#articleSelect').value);
  typing$('#articleMeta').textContent = article ? `${article.category} · ${article.length} 字符 · 正文将在开赛时显示` : '请选择比赛文章';
}

function updateLocalClock() {
  const room = typingState.room;
  if (!room) return;
  const serverNow = room.serverTime + (Date.now() - typingState.lastSnapshotAt);
  const battle = room.battle;
  if (room.state === 'COUNTDOWN' && battle) {
    const seconds = Math.max(0, Math.ceil((battle.countdownEndsAt - serverNow) / 1000));
    if (seconds > 0 && seconds !== typingState.lastCountdownSecond) playCue('tick');
    typingState.lastCountdownSecond = seconds;
    typing$('#countdownNumber').textContent = seconds || '开始';
    typing$('#clockLabel').textContent = '倒计时';
    typing$('#matchClock').textContent = `00:0${Math.min(9, seconds)}`;
  } else if (room.state === 'RUNNING' && battle) {
    typingState.lastCountdownSecond = null;
    const remaining = Math.max(0, battle.endsAt - serverNow);
    typing$('#clockLabel').textContent = '剩余时间';
    typing$('#matchClock').textContent = formatClock(remaining);
  } else if (room.state === 'FINISHED' && battle) {
    typingState.lastCountdownSecond = null;
    typing$('#clockLabel').textContent = '本局用时';
    typing$('#matchClock').textContent = formatClock(Math.max(battle.left.elapsedMillis, battle.right.elapsedMillis));
  } else {
    typingState.lastCountdownSecond = null;
    typing$('#clockLabel').textContent = '等待中';
    typing$('#matchClock').textContent = '05:00';
  }
}

function setSocketState(kind, label) {
  const target = typing$('#socketState');
  target.className = `socket-state ${kind}`;
  target.querySelector('span').textContent = label;
}

async function typingApi(url, options = {}) {
  const headers = {};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.token) headers['X-Room-Token'] = options.token;
  const response = await fetch(url, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const payload = response.status === 204 ? null : await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload?.error || payload?.message || `请求失败：HTTP ${response.status}`);
  return payload;
}

function setButtonBusy(button, busy, busyLabel) {
  if (!button) return;
  const label = button.querySelector('span');
  if (!button.dataset.label && label) button.dataset.label = label.textContent;
  button.disabled = busy;
  if (label) label.textContent = busy ? busyLabel : button.dataset.label;
}

function openModal(modal) {
  modal.classList.remove('hidden');
}

function closeModal(modal) {
  modal?.classList.add('hidden');
}

function saveRoomSession(roomId, token, displayName) {
  sessionStorage.setItem(`dataforge.typing.${roomId}`, JSON.stringify({token, displayName}));
}

function readRoomSession(roomId) {
  try {
    return JSON.parse(sessionStorage.getItem(`dataforge.typing.${roomId}`));
  } catch (_) {
    return null;
  }
}

function clearRoomSession(roomId) {
  sessionStorage.removeItem(`dataforge.typing.${roomId}`);
}

function stateLabel(state) {
  return {WAITING: '等待中', COUNTDOWN: '倒计时', RUNNING: '进行中', FINISHED: '已结束'}[state] || state;
}

function finishReasonLabel(reason) {
  return reason === 'COMPLETED' ? '完成全文'
    : reason === 'TIMEOUT' ? '时间到'
      : reason === 'MANUAL' ? '房主手动结束' : '比赛结束';
}

function formatClock(milliseconds) {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

function formatDuration(milliseconds) {
  const seconds = Math.max(0, Math.round(milliseconds / 1000));
  return `${Math.floor(seconds / 60)}分${String(seconds % 60).padStart(2, '0')}秒`;
}

function commonPrefixLength(left, right) {
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < left.length && rightIndex < right.length) {
    const leftCodePoint = left.codePointAt(leftIndex);
    const rightCodePoint = right.codePointAt(rightIndex);
    if (leftCodePoint !== rightCodePoint) break;
    leftIndex += leftCodePoint > 0xFFFF ? 2 : 1;
    rightIndex += rightCodePoint > 0xFFFF ? 2 : 1;
  }
  return leftIndex;
}

function typingToast(message) {
  const toast = typing$('#toast');
  toast.textContent = message || '操作失败';
  toast.classList.add('show');
  clearTimeout(typingToast.timer);
  typingToast.timer = setTimeout(() => toast.classList.remove('show'), 2800);
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function escapeAttr(value) {
  return escapeHtml(value);
}
