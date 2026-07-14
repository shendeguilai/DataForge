const hanoi = {
  n: 5,
  towers: [],
  selected: null,
  moves: 0,
  autoMoves: [],
  autoIndex: 0,
  timer: null,
  running: false,
  colors: ['#ef6c65', '#f2b84b', '#d7ef72', '#56c7e8', '#45c5a0', '#7c74e8', '#b9b4ff', '#f08cc6']
};

const hanoi$ = (selector) => document.querySelector(selector);
const hanoi$$ = (selector) => Array.from(document.querySelectorAll(selector));

function initHanoi() {
  hanoi$('#diskCount').onchange = () => resetHanoi(Number(hanoi$('#diskCount').value));
  hanoi$('#speedRange').oninput = () => {
    hanoi$('#speedLabel').textContent = `${hanoiSpeed()}ms`;
  };
  hanoi$('#autoButton').onclick = startAuto;
  hanoi$('#pauseButton').onclick = togglePause;
  hanoi$('#stepButton').onclick = stepAuto;
  hanoi$('#hintButton').onclick = showHint;
  hanoi$('#resetButton').onclick = () => resetHanoi(hanoi.n);
  hanoi$$('.tower').forEach(tower => tower.onclick = () => handleTowerClick(Number(tower.dataset.tower)));
  resetHanoi(5);
}

function resetHanoi(n) {
  stopAuto();
  hanoi.n = n;
  hanoi.towers = [[], [], []];
  for (let disk = n; disk >= 1; disk--) hanoi.towers[0].push(disk);
  hanoi.selected = null;
  hanoi.moves = 0;
  hanoi.autoMoves = buildRecursiveMoves(n, 0, 2, 1);
  hanoi.autoIndex = 0;
  hanoi$('#diskCount').value = String(n);
  hanoi$('#minimumMoves').textContent = String(2 ** n - 1);
  setMessage('点击 A 柱选择最上方盘子，再点击目标柱移动。');
  renderHanoi();
}

function handleTowerClick(index) {
  clearHints();
  if (hanoi.running) {
    setMessage('自动演示中，先暂停后再手动移动。');
    return;
  }
  if (hanoi.selected === null) {
    if (!hanoi.towers[index].length) {
      setMessage(`${towerName(index)} 柱没有可移动的盘子。`);
      return;
    }
    hanoi.selected = index;
    setMessage(`已选择 ${towerName(index)} 柱，点击目标柱完成移动。`);
    renderHanoi();
    return;
  }
  if (hanoi.selected === index) {
    hanoi.selected = null;
    setMessage('已取消选择。');
    renderHanoi();
    return;
  }
  moveDisk(hanoi.selected, index, true);
  hanoi.selected = null;
  renderHanoi();
}

function moveDisk(from, to, countMove) {
  const source = hanoi.towers[from];
  const target = hanoi.towers[to];
  if (!source.length) {
    setMessage(`${towerName(from)} 柱没有可移动的盘子。`);
    return false;
  }
  const disk = source[source.length - 1];
  if (target.length && target[target.length - 1] < disk) {
    setMessage(`非法移动：不能把 ${disk} 号大盘放在小盘上。`);
    return false;
  }
  source.pop();
  target.push(disk);
  if (countMove) hanoi.moves++;
  setMessage(`移动 ${disk} 号盘：${towerName(from)} → ${towerName(to)}。`);
  if (isSolved()) {
    stopAuto();
    setMessage(`完成！共移动 ${hanoi.moves} 步，最优步数是 ${2 ** hanoi.n - 1}。`);
  }
  return true;
}

function startAuto() {
  resetHanoi(hanoi.n);
  hanoi.running = true;
  hanoi$('#pauseButton').textContent = '暂停';
  setMessage('自动演示开始：递归策略是先把 n-1 个盘子移到辅助柱，再移动最大盘。');
  scheduleAuto();
}

function togglePause() {
  if (!hanoi.running && hanoi.autoIndex < hanoi.autoMoves.length) {
    hanoi.running = true;
    hanoi$('#pauseButton').textContent = '暂停';
    setMessage('继续自动演示。');
    scheduleAuto();
    return;
  }
  if (hanoi.running) {
    hanoi.running = false;
    clearTimeout(hanoi.timer);
    hanoi$('#pauseButton').textContent = '继续';
    setMessage('已暂停，可以单步观察或手动移动。');
  }
}

function stepAuto() {
  if (hanoi.running) togglePause();
  if (!hanoi.autoMoves.length || hanoi.autoIndex >= hanoi.autoMoves.length) {
    setMessage('已经没有剩余的自动步骤。');
    return;
  }
  applyAutoMove();
}

function scheduleAuto() {
  clearTimeout(hanoi.timer);
  if (!hanoi.running) return;
  hanoi.timer = setTimeout(() => {
    applyAutoMove();
    if (hanoi.running && hanoi.autoIndex < hanoi.autoMoves.length) scheduleAuto();
  }, hanoiSpeed());
}

function applyAutoMove() {
  const next = hanoi.autoMoves[hanoi.autoIndex];
  if (!next) return;
  hanoi.autoIndex++;
  moveDisk(next[0], next[1], true);
  renderHanoi();
}

function stopAuto() {
  hanoi.running = false;
  clearTimeout(hanoi.timer);
  hanoi.timer = null;
  const pause = hanoi$('#pauseButton');
  if (pause) pause.textContent = '暂停';
}

function showHint() {
  clearHints();
  if (isSolved()) {
    setMessage('已经完成啦，不需要提示。');
    return;
  }
  const hint = findBestNextMove();
  if (!hint) {
    setMessage('暂时没有找到提示，可以重置后再试。');
    return;
  }
  hanoi$$('.tower')[hint[0]].classList.add('hint');
  hanoi$$('.tower')[hint[1]].classList.add('hint');
  setMessage(`提示：把 ${towerName(hint[0])} 柱最上方盘子移动到 ${towerName(hint[1])} 柱。`);
}

function findBestNextMove() {
  const start = encodeState(hanoi.towers);
  const goal = Array.from({length: hanoi.n}, () => 2).join('');
  if (start === goal) return null;
  const queue = [start];
  const seen = new Set([start]);
  const parent = new Map();
  let found = null;
  for (let head = 0; head < queue.length; head++) {
    const state = queue[head];
    for (const move of legalMovesFromState(state)) {
      const next = applyMoveToState(state, move);
      if (seen.has(next)) continue;
      seen.add(next);
      parent.set(next, {prev: state, move});
      if (next === goal) {
        found = next;
        head = queue.length;
        break;
      }
      queue.push(next);
    }
  }
  if (!found) return null;
  let current = found;
  let step = parent.get(current);
  while (step && step.prev !== start) {
    current = step.prev;
    step = parent.get(current);
  }
  return step?.move || null;
}

function legalMovesFromState(state) {
  const towers = [[], [], []];
  for (let disk = hanoi.n; disk >= 1; disk--) {
    towers[Number(state[disk - 1])].push(disk);
  }
  const moves = [];
  for (let from = 0; from < 3; from++) {
    const disk = towers[from][towers[from].length - 1];
    if (!disk) continue;
    for (let to = 0; to < 3; to++) {
      if (from === to) continue;
      const top = towers[to][towers[to].length - 1];
      if (!top || top > disk) moves.push([from, to]);
    }
  }
  return moves;
}

function applyMoveToState(state, move) {
  const positions = state.split('').map(Number);
  let movingDisk = null;
  for (let disk = 1; disk <= hanoi.n; disk++) {
    if (positions[disk - 1] === move[0]) {
      movingDisk = disk;
      break;
    }
  }
  positions[movingDisk - 1] = move[1];
  return positions.join('');
}

function encodeState(towers) {
  const positions = Array(hanoi.n).fill(0);
  towers.forEach((tower, index) => tower.forEach(disk => positions[disk - 1] = index));
  return positions.join('');
}

function buildRecursiveMoves(n, from, to, aux) {
  if (n === 0) return [];
  return [
    ...buildRecursiveMoves(n - 1, from, aux, to),
    [from, to],
    ...buildRecursiveMoves(n - 1, aux, to, from)
  ];
}

function renderHanoi() {
  hanoi$$('.tower').forEach((tower, index) => {
    tower.classList.toggle('selected', hanoi.selected === index);
    const stack = tower.querySelector('.tower-stack');
    stack.innerHTML = hanoi.towers[index].map(disk => diskHtml(disk)).join('');
  });
  hanoi$('#moveCount').textContent = String(hanoi.moves);
  const progress = Math.round(100 * hanoi.towers[2].length / hanoi.n);
  const progressBar = hanoi$('#progressBar');
  const progressText = hanoi$('#progressText');
  if (progressBar) progressBar.style.width = `${progress}%`;
  if (progressText) progressText.textContent = `${progress}%`;
  const board = hanoi$('#hanoiBoard');
  if (board) board.classList.toggle('complete', isSolved());
}

function diskHtml(disk) {
  const min = 34;
  const max = 92;
  const width = min + (max - min) * disk / hanoi.n;
  const color = hanoi.colors[(disk - 1) % hanoi.colors.length];
  return `<span class="hanoi-disk" style="width:${width}%;background:${color};">${disk}</span>`;
}

function hanoiSpeed() {
  return Number(hanoi$('#speedRange').value);
}

function isSolved() {
  return hanoi.towers[2].length === hanoi.n;
}

function clearHints() {
  hanoi$$('.tower').forEach(tower => tower.classList.remove('hint'));
}

function setMessage(message) {
  hanoi$('#hanoiMessage').textContent = message;
}

function towerName(index) {
  return ['A', 'B', 'C'][index];
}

initHanoi();
