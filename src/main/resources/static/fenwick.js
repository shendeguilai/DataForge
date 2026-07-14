const bitN = 8;
const initialA = [0, 3, 1, 4, 1, 5, 9, 2, 6];

const scenes = {
  range: createScene(),
  add: createScene(),
  query: createScene(),
  diff: createDiffScene()
};

const bit$ = (selector) => document.querySelector(selector);
const bit$$ = (selector) => Array.from(document.querySelectorAll(selector));

function createScene() {
  return {
    n: bitN,
    a: [...initialA],
    tree: buildTree(initialA),
    runId: 0,
    steps: [],
    stepIndex: 0
  };
}

function createDiffScene() {
  const d = buildDiff(initialA);
  return {
    n: bitN,
    a: [...initialA],
    d,
    tree: buildTree(d),
    runId: 0,
    steps: [],
    stepIndex: 0
  };
}

function initFenwick() {
  renderRangeDiagram();
  renderAddDiagram();
  renderQueryDiagram();
  renderDiffBoard();
  showLowbitRange();
  bit$('#showRangeButton').onclick = showLowbitRange;
  bit$('#addButton').onclick = animateAdd;
  bit$('#addPrevButton').onclick = prevAdd;
  bit$('#addStepButton').onclick = stepAdd;
  bit$('#resetAddButton').onclick = resetAddDemo;
  bit$('#queryButton').onclick = animateQuery;
  bit$('#queryPrevButton').onclick = prevQuery;
  bit$('#queryStepButton').onclick = stepQuery;
  bit$('#resetQueryButton').onclick = resetQueryDemo;
  bit$('#diffButton').onclick = animateDiff;
  bit$('#diffPrevButton').onclick = prevDiff;
  bit$('#diffStepButton').onclick = stepDiff;
  bit$('#resetDiffButton').onclick = resetDiffDemo;
  bit$('#addIndexInput').oninput = discardAddSteps;
  bit$('#addDeltaInput').oninput = discardAddSteps;
  bit$('#queryIndexInput').oninput = discardQuerySteps;
  bit$('#diffLeftInput').oninput = discardDiffSteps;
  bit$('#diffRightInput').oninput = discardDiffSteps;
  bit$('#diffDeltaInput').oninput = discardDiffSteps;
  bit$('#diffQueryInput').oninput = discardDiffSteps;
}

function refreshAddDiagramFromInputs() {
  const x = selectedIndex('addIndexInput');
  const k = selectedDelta();
  renderAddDiagram({ x, k, path: updatePath(x) });
  setText('addMessage', `准备演示 add(${x}, ${k})。红色竖线是修改点 a[${x}]。`);
  setText('addFormula', `会更新所有包含 a[${x}] 的 tree 区间。`);
  setText('addLine', `更新路径：${updatePath(x).join(' -> ')}。`);
}

function lowbit(x) {
  return x & -x;
}

function buildTree(source) {
  const tree = Array(bitN + 1).fill(0);
  for (let i = 1; i <= bitN; i++) {
    for (let j = i; j <= bitN; j += lowbit(j)) {
      tree[j] += source[i];
    }
  }
  return tree;
}

function buildDiff(source) {
  return Array.from({length: bitN + 1}, (_, i) => i === 0 ? 0 : source[i] - source[i - 1]);
}

function renderBoard(prefix, scene, onPick) {
  bit$(`#${prefix}ArrayRow`).innerHTML = Array.from({length: scene.n}, (_, k) => {
    const i = k + 1;
    return `<button class="bit-cell" data-${prefix}-array="${i}" type="button"><b>a[${i}]</b><span>${scene.a[i]}</span></button>`;
  }).join('');
  bit$(`#${prefix}TreeRow`).innerHTML = Array.from({length: scene.n}, (_, k) => {
    const i = k + 1;
    return `<button class="bit-cell" data-${prefix}-tree="${i}" type="button"><b>tree[${i}]</b><span>${scene.tree[i]}</span></button>`;
  }).join('');
  if (!onPick) return;
  bit$$(`[data-${prefix}-array], [data-${prefix}-tree]`).forEach(cell => {
    cell.onclick = () => onPick(Number(cell.dataset[`${prefix}Array`] || cell.dataset[`${prefix}Tree`]));
  });
}

function renderRangeDiagram() {
  const tones = ['#fff0b8', '#e4f2d8', '#fff0b8', '#dcecf8', '#fff0b8', '#e4f2d8', '#fff0b8', '#e8ddf7'];
  const lines = ['#f5a400', '#4f9d31', '#f5a400', '#2e93d6', '#f5a400', '#4f9d31', '#f5a400', '#8752c7'];
  const rows = [
    {
      label: '下标 i',
      values: Array.from({length: bitN}, (_, k) => String(k + 1)),
      cellClass: 'index-cell'
    },
    {
      label: 'lowbit(i)',
      values: Array.from({length: bitN}, (_, k) => String(lowbit(k + 1))),
      cellClass: 'lowbit-cell'
    },
    {
      label: 'tree[i]\n负责区间',
      values: Array.from({length: bitN}, (_, k) => {
        const i = k + 1;
        return `[${i - lowbit(i) + 1}, ${i}]`;
      }),
      cellClass: 'interval-cell'
    }
  ];
  bit$('#rangeSummaryGrid').innerHTML = rows.map(row => {
    const label = `<div class="range-label">${row.label.replace('\n', '<br>')}</div>`;
    const cells = row.values.map((value, index) => {
      const i = index + 1;
      const tone = row.cellClass === 'interval-cell' ? ` style="--tone: ${tones[index]}"` : '';
      return `<button class="range-cell ${row.cellClass}" data-range-tree="${i}" type="button"${tone}>${value}</button>`;
    }).join('');
    return label + cells;
  }).join('');

  const axis = `<div class="range-axis">
    <div class="range-row-label"></div>
    <div class="range-axis-track">${Array.from({length: bitN}, (_, k) => `<span>${k + 1}</span>`).join('')}</div>
  </div>`;
  const mapRows = Array.from({length: bitN}, (_, k) => {
    const i = k + 1;
    const left = i - lowbit(i) + 1;
    return `<div class="range-line-row" data-range-row="${i}">
      <div class="range-row-label">tree[${i}]</div>
      <div class="range-track">
        <button class="range-segment" data-range-tree="${i}" type="button" style="grid-column: ${left} / ${i + 1}; --line-color: ${lines[k]}">
          <span class="range-start">${left}</span>
          <span class="range-end">${i}</span>
        </button>
      </div>
    </div>`;
  }).join('');
  bit$('#rangeIntervalMap').innerHTML = axis + mapRows;
  bit$$('[data-range-tree]').forEach(cell => {
    cell.onclick = () => showRangeForPickedCell(Number(cell.dataset.rangeTree));
  });
}

function renderAddDiagram(options = {}) {
  const x = options.x ?? Number(bit$('#addIndexInput')?.value || 6);
  const path = options.path ?? updatePath(x);
  const completed = options.completed ?? [];
  const current = options.current ?? null;
  const k = options.k ?? Number(bit$('#addDeltaInput')?.value || 3);
  const lines = ['#f5a400', '#4f9d31', '#f5a400', '#2e93d6', '#f5a400', '#4f9d31', '#f5a400', '#8752c7'];
  const axis = `<div class="range-axis">
    <div class="range-row-label">下标</div>
    <div class="range-axis-track">${Array.from({length: bitN}, (_, idx) => `<span>${idx + 1}</span>`).join('')}</div>
  </div>`;
  const rows = Array.from({length: bitN}, (_, idx) => {
    const i = idx + 1;
    const left = i - lowbit(i) + 1;
    const isCandidate = path.includes(`tree[${i}]`);
    const isDone = completed.includes(i);
    const isCurrent = current === i;
    const rowClass = [
      'range-line-row',
      'add-line-row',
      isCandidate ? 'candidate' : 'dimmed',
      isDone ? 'done' : '',
      isCurrent ? 'current active' : ''
    ].filter(Boolean).join(' ');
    const status = isDone
      ? `<span class="add-status">已加 ${k}</span>`
      : isCurrent
        ? `<span class="add-status">当前加 ${k}</span>`
        : isCandidate
          ? `<span class="add-status">待更新</span>`
          : '';
    const point = `<span class="add-point-marker" style="grid-column: ${x} / ${x + 1}; grid-row: 1;"></span>`;
    return `<div class="${rowClass}" data-add-row="${i}">
      <div class="range-row-label">tree[${i}]</div>
      <div class="range-track">
        ${point}
        <button class="range-segment" data-add-tree="${i}" type="button" style="grid-column: ${left} / ${i + 1}; --line-color: ${lines[idx]}; grid-row: 1;">
          <span class="range-start">${left}</span>
          <span class="range-end">${i}</span>
          ${status}
        </button>
      </div>
    </div>`;
  }).join('');
  bit$('#addIntervalMap').innerHTML = axis + rows;
  bit$$('[data-add-tree]').forEach(cell => {
    cell.onclick = () => {
      const index = Number(cell.dataset.addTree);
      setText('addMessage', `tree[${index}] 维护 [${index - lowbit(index) + 1}, ${index}]。${path.includes(`tree[${index}]`) ? `这个区间包含 a[${x}]，会被 add(${x}, ${k}) 更新。` : `这个区间不包含 a[${x}]，本次不会更新。`}`);
    };
  });
}

function renderQueryDiagram(options = {}) {
  const x = options.x ?? Number(bit$('#queryIndexInput')?.value || 6);
  const path = options.path ?? queryPath(x);
  const completed = options.completed ?? [];
  const current = options.current ?? null;
  const lines = ['#f5a400', '#4f9d31', '#f5a400', '#2e93d6', '#f5a400', '#4f9d31', '#f5a400', '#8752c7'];
  const axis = `<div class="range-axis">
    <div class="range-row-label">前缀</div>
    <div class="range-axis-track">
      ${Array.from({length: bitN}, (_, idx) => `<span>${idx + 1}</span>`).join('')}
      <span class="query-prefix-marker" style="grid-column: 1 / ${x + 1}; grid-row: 2;">[1, ${x}]</span>
    </div>
  </div>`;
  const rows = Array.from({length: bitN}, (_, idx) => {
    const i = idx + 1;
    const left = i - lowbit(i) + 1;
    const isCandidate = path.includes(i);
    const isDone = completed.includes(i);
    const isCurrent = current === i;
    const rowClass = [
      'range-line-row',
      'query-line-row',
      isCandidate ? 'candidate' : 'dimmed',
      isDone ? 'done' : '',
      isCurrent ? 'current active' : ''
    ].filter(Boolean).join(' ');
    const status = isDone
      ? `<span class="query-status">已取</span>`
      : isCurrent
        ? `<span class="query-status">当前取</span>`
        : isCandidate
          ? `<span class="query-status">待取</span>`
          : '';
    return `<div class="${rowClass}" data-query-row="${i}">
      <div class="range-row-label">tree[${i}]</div>
      <div class="range-track">
        <button class="range-segment" data-query-tree="${i}" type="button" style="grid-column: ${left} / ${i + 1}; --line-color: ${lines[idx]};">
          <span class="range-start">${left}</span>
          <span class="range-end">${i}</span>
          ${status}
        </button>
      </div>
    </div>`;
  }).join('');
  bit$('#queryIntervalMap').innerHTML = axis + rows;
  bit$$('[data-query-tree]').forEach(cell => {
    cell.onclick = () => {
      const index = Number(cell.dataset.queryTree);
      const left = index - lowbit(index) + 1;
      setText('queryMessage', `tree[${index}] 维护 [${left}, ${index}]。${path.includes(index) ? `query(${x}) 会取这一段。` : `query(${x}) 不会取这一段。`}`);
    };
  });
}

function renderDiffBoard() {
  const scene = scenes.diff;
  bit$('#diffArrayRow').innerHTML = Array.from({length: scene.n}, (_, k) => {
    const i = k + 1;
    return `<button class="bit-cell" data-diff-array="${i}" type="button"><b>a[${i}]</b><span>${scene.a[i]}</span></button>`;
  }).join('');
  bit$('#diffDiffRow').innerHTML = Array.from({length: scene.n}, (_, k) => {
    const i = k + 1;
    return `<button class="bit-cell" data-diff-d="${i}" type="button"><b>d[${i}]</b><span>${scene.d[i]}</span></button>`;
  }).join('');
  bit$('#diffTreeRow').innerHTML = Array.from({length: scene.n}, (_, k) => {
    const i = k + 1;
    return `<button class="bit-cell" data-diff-tree="${i}" type="button"><b>tree[${i}]</b><span>${scene.tree[i]}</span></button>`;
  }).join('');
}

function selectedIndex(inputId) {
  const input = bit$(`#${inputId}`);
  const raw = Number(input.value || 1);
  const value = Math.min(bitN, Math.max(1, raw));
  input.value = String(value);
  return value;
}

function selectedRange() {
  let l = selectedIndex('diffLeftInput');
  let r = selectedIndex('diffRightInput');
  if (l > r) {
    [l, r] = [r, l];
    bit$('#diffLeftInput').value = String(l);
    bit$('#diffRightInput').value = String(r);
  }
  return { l, r };
}

function selectedDelta() {
  return Number(bit$('#addDeltaInput').value || 0);
}

function selectedDiffDelta() {
  return Number(bit$('#diffDeltaInput').value || 0);
}

function showRangeForPickedCell(index) {
  bit$('#rangeIndexInput').value = String(index);
  showLowbitRange();
}

function showLowbitRange() {
  stopAnimation(scenes.range);
  clearRangeHighlights();
  clearCodeHighlights();
  const i = selectedIndex('rangeIndexInput');
  const lb = lowbit(i);
  const left = i - lb + 1;
  markRange(i);
  highlightCode('rangeLowbit');
  setText('rangeMessage', `lowbit(${i}) = ${lb}，所以 tree[${i}] 维护原数组区间 [${left}, ${i}]。`);
  setText('rangeFormula', `${i} 的二进制是 ${i.toString(2)}，最低位的 1 代表 ${lb}；tree[${i}] = a[${left}] + ... + a[${i}]。`);
  setText('rangeLine', `当前高亮：tree[${i}] 覆盖 a[${left}] 到 a[${i}]。`);
}

async function animateAdd() {
  const scene = scenes.add;
  prepareAddSteps();
  await playSteps(scene, 640);
}

function stepAdd() {
  stopAnimation(scenes.add);
  runNextStep(scenes.add, prepareAddSteps);
}

function prevAdd() {
  stopAnimation(scenes.add);
  replayToStep(scenes.add, prepareAddSteps, resetAddDemo, scenes.add.stepIndex - 1);
}

async function animateQuery() {
  const scene = scenes.query;
  prepareQuerySteps();
  await playSteps(scene, 680);
}

function stepQuery() {
  stopAnimation(scenes.query);
  runNextStep(scenes.query, prepareQuerySteps);
}

function prevQuery() {
  stopAnimation(scenes.query);
  replayToStep(scenes.query, prepareQuerySteps, resetQueryDemo, scenes.query.stepIndex - 1);
}

async function animateDiff() {
  const scene = scenes.diff;
  prepareDiffSteps();
  await playSteps(scene, 700);
}

function stepDiff() {
  stopAnimation(scenes.diff);
  runNextStep(scenes.diff, prepareDiffSteps);
}

function prevDiff() {
  stopAnimation(scenes.diff);
  replayToStep(scenes.diff, prepareDiffSteps, resetDiffDemo, scenes.diff.stepIndex - 1);
}

function prepareAddSteps() {
  const scene = scenes.add;
  resetAddState();
  clearCodeHighlights();
  const x = selectedIndex('addIndexInput');
  const k = selectedDelta();
  const path = updatePath(x);
  scene.a[x] += k;
  const completed = [];
  scene.steps = [
    () => {
      renderAddDiagram({ x, k, path, completed });
      highlightCode('add1');
      setText('addMessage', `第 1 步：进入 add(${x}, ${k})，先让 a[${x}] += ${k}。`);
      setText('addFormula', `图中的红色竖线是修改点 x=${x}；只有包含它的 tree 区间需要更新。`);
      setText('addLine', `本次更新路径会是：${path.join(' -> ')}。`);
    }
  ];

  for (let i = x; i <= scene.n; i += lowbit(i)) {
    const current = i;
    const lb = lowbit(current);
    const left = current - lb + 1;
    scene.steps.push(
      () => {
        renderAddDiagram({ x, k, path, completed, current });
        highlightCode('add2');
        setText('addMessage', `判断循环：当前 i=${current}，满足 i <= n。`);
        setText('addFormula', `tree[${current}] 维护 [${left}, ${current}]，这段包含 a[${x}]。`);
        setText('addLine', `所以本轮要更新 tree[${current}]。`);
      },
      () => {
        highlightCode('add3');
        scene.tree[current] += k;
        completed.push(current);
        renderAddDiagram({ x, k, path, completed, current });
        setText('addMessage', `执行 tree[${current}] += ${k}。`);
        setText('addFormula', `tree[${current}] 新值为 ${scene.tree[current]}。`);
        setText('addLine', `已完成这一段区间 [${left}, ${current}] 的更新。`);
      },
      () => {
        const next = current + lb;
        highlightCode('add2');
        renderAddDiagram({ x, k, path, completed });
        setText('addMessage', `循环递增：i += lowbit(i)，也就是 ${current} + ${lb} = ${next}。`);
        setText('addFormula', next <= scene.n ? `下一步继续检查 tree[${next}]。` : `i=${next} 已经超过 n，下一步结束 add。`);
        setText('addLine', next <= scene.n ? `下一段候选区间：tree[${next}]。` : `更新路径已经走完：${path.join(' -> ')}。`);
      }
    );
  }

  scene.steps.push(() => {
    clearCodeHighlights();
    renderAddDiagram({ x, k, path, completed: path.map(item => Number(item.match(/\d+/)[0])) });
    setText('addMessage', `完成：add(${x}, ${k}) 更新的是 ${path.join('、')}。`);
    setText('addFormula', `这些 tree 节点维护的区间都包含 a[${x}]，所以都要加上 ${k}。`);
    setText('addLine', `最终更新路径：${path.join(' -> ')}。`);
  });
  scene.stepIndex = 0;
}

function prepareQuerySteps() {
  const scene = scenes.query;
  clearCodeHighlights();
  const x = selectedIndex('queryIndexInput');
  const path = queryPath(x);
  const completed = [];
  let ans = 0;
  const segments = [];
  scene.steps = [
    () => {
      renderQueryDiagram({ x, path, completed });
      highlightCode('query1');
      setText('queryMessage', `第 1 步：进入 query(${x})，目标是求 a[1] 到 a[${x}] 的和。`);
      setText('queryFormula', `上方虚线框是目标前缀 [1,${x}]；接下来会拆成若干条 tree 区间线段。`);
      setText('queryLine', `待拆分前缀：[1, ${x}]。`);
    },
    () => {
      renderQueryDiagram({ x, path, completed });
      highlightCode('query2');
      setText('queryMessage', '初始化答案变量。');
      setText('queryFormula', 'int ans = 0。');
      setText('queryLine', '当前 ans = 0。');
    }
  ];

  for (let i = x; i > 0; i -= lowbit(i)) {
    const current = i;
    const lb = lowbit(current);
    const left = current - lb + 1;
    scene.steps.push(
      () => {
        renderQueryDiagram({ x, path, completed, current });
        highlightCode('query3');
        setText('queryMessage', `判断循环：当前 i=${current}，还没有到 0。`);
        setText('queryFormula', `tree[${current}] 维护 [${left}, ${current}]，可以整段加入答案。`);
        setText('queryLine', `本轮拆出区间 [${left}, ${current}]。`);
      },
      () => {
        highlightCode('query4');
        ans += scene.tree[current];
        segments.push(`[${left},${current}]`);
        completed.push(current);
        renderQueryDiagram({ x, path, completed, current });
        setText('queryMessage', `执行 ans += tree[${current}]。`);
        setText('queryFormula', `tree[${current}] = ${scene.tree[current]}，当前 ans = ${ans}。`);
        setText('queryLine', `已经拆出：${segments.join(' + ')}。`);
      },
      () => {
        const next = current - lb;
        highlightCode('query3');
        renderQueryDiagram({ x, path, completed });
        setText('queryMessage', `循环递减：i -= lowbit(i)，也就是 ${current} - ${lb} = ${next}。`);
        setText('queryFormula', next ? `下一步继续取 tree[${next}]。` : 'i 变成 0，下一步返回 ans。');
        setText('queryLine', next ? `左侧剩余部分继续拆。` : `拆分完成：${segments.join(' + ')}。`);
      }
    );
  }

  scene.steps.push(() => {
    highlightCode('query6');
    renderQueryDiagram({ x, path, completed: path });
    setText('queryMessage', `完成：query(${x}) = ${ans}。`);
    setText('queryFormula', `区间拆分：${segments.join(' + ')}，拼起来正好是 [1,${x}]。`);
    setText('queryLine', `return ans，返回 ${ans}。`);
  });
  scene.stepIndex = 0;
}

function prepareDiffSteps() {
  const scene = scenes.diff;
  resetDiffState();
  clearHighlights('diff');
  clearDiffPointHighlights();
  clearCodeHighlights();
  const { l, r } = selectedRange();
  const k = selectedDiffDelta();
  const x = selectedIndex('diffQueryInput');
  const updateOps = [
    { pos: l, delta: k, code: 'diffAdd2', label: `add(${l}, ${k})` }
  ];
  if (r + 1 <= bitN) {
    updateOps.push({ pos: r + 1, delta: -k, code: 'diffAdd3', label: `add(${r + 1}, ${-k})` });
  }

  scene.steps = [
    () => {
      renderDiffBoard();
      for (let p = l; p <= r; p++) markDiffArray(p, 'cover');
      highlightCode('diffBuild');
      setText('diffMessage', `第 1 步：树状数组维护差分 d[i] = a[i] - a[i-1]。`);
      setText('diffFormula', `当前 d[] 已经建好，query(x) 得到的是 d[1] + ... + d[x]，也就是 a[x]。`);
      setText('diffLine', `准备执行区间 [${l},${r}] += ${k}。`);
    },
    () => {
      highlightCode('diffAdd1');
      clearHighlights('diff');
      clearDiffPointHighlights();
      for (let p = l; p <= r; p++) markDiffArray(p, 'cover');
      setText('diffMessage', `第 2 步：进入 range_add(${l}, ${r}, ${k})。`);
      setText('diffFormula', `区间加在差分上只改两个边界：d[${l}] += ${k}，${r + 1 <= bitN ? `d[${r + 1}] -= ${k}` : `r+1=${r + 1} 超过 n，右边界不用改`}。`);
      setText('diffLine', `关键转化：[l,r] += k -> add(l,k), add(r+1,-k)。`);
    }
  ];

  updateOps.forEach(op => {
    const path = updatePath(op.pos);
    scene.steps.push(
      () => {
        clearHighlights('diff');
        clearDiffPointHighlights();
        for (let p = l; p <= r; p++) markDiffArray(p, 'cover');
        markDiffPoint(op.pos, 'visit');
        path.forEach(item => markTree('diff', Number(item.match(/\d+/)[0]), 'cover'));
        highlightCode(op.code);
        setText('diffMessage', `边界修改：执行 ${op.label}。`);
        setText('diffFormula', `这会让 d[${op.pos}] ${op.delta >= 0 ? '+' : '-'}= ${Math.abs(op.delta)}，并沿树状数组路径更新。`);
        setText('diffLine', `更新路径：${path.join(' -> ')}。`);
      },
      () => {
        applyDiffPoint(op.pos, op.delta);
        renderDiffBoard();
        for (let p = l; p <= r; p++) markDiffArray(p, 'cover');
        markDiffPoint(op.pos, 'visit');
        path.forEach(item => markTree('diff', Number(item.match(/\d+/)[0]), 'visit'));
        highlightCode(op.code);
        setText('diffMessage', `${op.label} 完成。`);
        setText('diffFormula', `现在 d[${op.pos}] = ${scene.d[op.pos]}，受影响的 tree 节点也同步更新。`);
        setText('diffLine', `树状数组维护的是差分 d[]，不是直接维护 a[]。`);
      }
    );
  });

  scene.steps.push(
    () => {
      clearHighlights('diff');
      clearDiffPointHighlights();
      for (let p = l; p <= r; p++) markDiffArray(p, 'cover');
      highlightCode('diffQuery1');
      setText('diffMessage', `区间加完成后，准备查询位置 x=${x} 的单点值。`);
      setText('diffFormula', `因为 a[${x}] = d[1] + ... + d[${x}]，所以 point_query(${x}) 直接 return query(${x})。`);
      setText('diffLine', `接下来把 query(${x}) 拆成若干个 tree 区间。`);
    }
  );

  let ans = 0;
  const segments = [];
  for (let i = x; i > 0; i -= lowbit(i)) {
    const current = i;
    const lb = lowbit(current);
    const left = current - lb + 1;
    scene.steps.push(
      () => {
        clearHighlights('diff');
        clearDiffPointHighlights();
        for (let p = left; p <= current; p++) markDiffPoint(p, 'cover');
        markTree('diff', current, 'visit');
        highlightCode('diffQuery2');
        setText('diffMessage', `query(${x}) 当前访问 tree[${current}]。`);
        setText('diffFormula', `tree[${current}] 维护差分区间 [${left}, ${current}]，可以整段加入答案。`);
        setText('diffLine', `本轮取出差分区间 [${left}, ${current}]。`);
      },
      () => {
        ans += scene.tree[current];
        segments.push(`[${left},${current}]`);
        clearHighlights('diff');
        clearDiffPointHighlights();
        for (let p = left; p <= current; p++) markDiffPoint(p, 'cover');
        markTree('diff', current, 'visit');
        highlightCode('diffQuery2');
        setText('diffMessage', `ans += tree[${current}] = ${scene.tree[current]}。`);
        setText('diffFormula', `当前 ans = ${ans}，已经累加差分区间 ${segments.join(' + ')}。`);
        setText('diffLine', `下一步 i -= lowbit(i)：${current} - ${lb} = ${current - lb}。`);
      }
    );
  }

  scene.steps.push(() => {
    clearHighlights('diff');
    clearDiffPointHighlights();
    for (let p = 1; p <= x; p++) markDiffPoint(p, 'cover');
    markDiffArray(x, 'visit');
    highlightCode('diffQuery2');
    setText('diffMessage', `完成：point_query(${x}) = ${ans}。`);
    setText('diffFormula', `差分前缀 ${segments.join(' + ')} 拼起来是 [1,${x}]，还原出 a[${x}] = ${ans}。`);
    setText('diffLine', `这就是区间加 + 单点查询：修改看边界，查询看差分前缀。`);
  });
  scene.stepIndex = 0;
}

function runNextStep(scene, prepare) {
  if (!scene.steps.length || scene.stepIndex >= scene.steps.length) {
    prepare();
  }
  scene.steps[scene.stepIndex]();
  scene.stepIndex += 1;
}

function replayToStep(scene, prepare, reset, targetCount) {
  const count = Math.max(0, targetCount);
  if (!scene.steps.length || count === 0) {
    reset();
    return;
  }
  const limit = Math.min(count, scene.steps.length);
  prepare();
  for (let index = 0; index < limit; index++) {
    scene.steps[index]();
  }
  scene.stepIndex = limit;
}

async function playSteps(scene, interval) {
  const runId = startAnimation(scene);
  while (scene.stepIndex < scene.steps.length) {
    runNextStep(scene, () => {});
    if (!await waitBit(interval, scene, runId)) return;
  }
}

function updatePath(x) {
  const path = [];
  for (let i = x; i <= bitN; i += lowbit(i)) path.push(`tree[${i}]`);
  return path;
}

function queryPath(x) {
  const path = [];
  for (let i = x; i > 0; i -= lowbit(i)) path.push(i);
  return path;
}

function resetAddDemo() {
  stopAnimation(scenes.add);
  resetAddState();
  discardAddSteps();
  clearCodeHighlights();
  bit$('#addIndexInput').value = '6';
  bit$('#addDeltaInput').value = '3';
  renderAddDiagram({ x: 6, k: 3, path: updatePath(6) });
  setText('addMessage', '点击“下一步”或“自动演示” add(6, 3)，观察哪些 tree 区间会一起变化。');
  setText('addFormula', 'tree[6] 维护 [5,6]，tree[8] 维护 [1,8]，它们都包含 a[6]。');
  setText('addLine', '更新路径：tree[6] -> tree[8]。');
}

function resetQueryDemo() {
  stopAnimation(scenes.query);
  scenes.query.steps = [];
  scenes.query.stepIndex = 0;
  clearCodeHighlights();
  bit$('#queryIndexInput').value = '6';
  renderQueryDiagram({ x: 6, path: queryPath(6) });
  setText('queryMessage', '点击“下一步”或“自动演示” query(6)，观察前缀 [1,6] 如何被拆分。');
  setText('queryFormula', '从 i=6 开始，每次加入 tree[i]，再令 i -= lowbit(i)。');
  setText('queryLine', 'query(6) 会拆成 [5,6] + [1,4]。');
}

function resetDiffDemo() {
  stopAnimation(scenes.diff);
  resetDiffState();
  discardDiffSteps();
  clearHighlights('diff');
  clearDiffPointHighlights();
  clearCodeHighlights();
  bit$('#diffLeftInput').value = '3';
  bit$('#diffRightInput').value = '6';
  bit$('#diffDeltaInput').value = '2';
  bit$('#diffQueryInput').value = '5';
  setText('diffMessage', '点击“下一步”或“自动演示”，观察差分树状数组如何完成区间加和单点查询。');
  setText('diffFormula', '差分含义：a[x] = d[1] + ... + d[x]，所以 point_query(x) = query(x)。');
  setText('diffLine', '区间 [3,6] += 2，会变成 add(3,2) 和 add(7,-2)。');
}

function resetAddState() {
  scenes.add.a = [...initialA];
  scenes.add.tree = buildTree(initialA);
  renderAddDiagram();
}

function resetDiffState() {
  scenes.diff.a = [...initialA];
  scenes.diff.d = buildDiff(initialA);
  scenes.diff.tree = buildTree(scenes.diff.d);
  renderDiffBoard();
}

function applyDiffPoint(pos, delta) {
  scenes.diff.d[pos] += delta;
  for (let i = pos; i <= bitN; i += lowbit(i)) {
    scenes.diff.tree[i] += delta;
  }
  for (let i = 1; i <= bitN; i++) {
    scenes.diff.a[i] = scenes.diff.a[i - 1] + scenes.diff.d[i];
  }
}

function discardAddSteps() {
  stopAnimation(scenes.add);
  scenes.add.steps = [];
  scenes.add.stepIndex = 0;
  refreshAddDiagramFromInputs();
}

function discardQuerySteps() {
  stopAnimation(scenes.query);
  scenes.query.steps = [];
  scenes.query.stepIndex = 0;
  const x = selectedIndex('queryIndexInput');
  renderQueryDiagram({ x, path: queryPath(x) });
  setText('queryMessage', `准备演示 query(${x})。虚线框表示目标前缀 [1,${x}]。`);
  setText('queryFormula', `会依次取 ${queryPath(x).map(i => `tree[${i}]`).join('、')}。`);
  setText('queryLine', `拆分路径：${queryPath(x).map(i => {
    const left = i - lowbit(i) + 1;
    return `[${left},${i}]`;
  }).join(' + ')}。`);
}

function discardDiffSteps() {
  stopAnimation(scenes.diff);
  scenes.diff.steps = [];
  scenes.diff.stepIndex = 0;
}

function markArray(prefix, i, cls) {
  bit$(`[data-${prefix}-array="${i}"]`)?.classList.add(cls);
}

function markTree(prefix, i, cls) {
  bit$(`[data-${prefix}-tree="${i}"]`)?.classList.add(cls);
}

function clearHighlights(prefix) {
  bit$$(`[data-${prefix}-array], [data-${prefix}-tree]`).forEach(cell => cell.classList.remove('cover', 'visit'));
}

function markDiffArray(i, cls) {
  bit$(`[data-diff-array="${i}"]`)?.classList.add(cls);
}

function markDiffPoint(i, cls) {
  bit$(`[data-diff-d="${i}"]`)?.classList.add(cls);
}

function clearDiffPointHighlights() {
  bit$$('[data-diff-d]').forEach(cell => cell.classList.remove('cover', 'visit'));
}

function markRange(i) {
  bit$$(`[data-range-tree="${i}"]`).forEach(cell => cell.classList.add('active'));
  bit$(`[data-range-row="${i}"]`)?.classList.add('active');
}

function clearRangeHighlights() {
  bit$$('[data-range-tree], [data-range-row]').forEach(cell => cell.classList.remove('active'));
}

function highlightCode(id) {
  clearCodeHighlights();
  bit$(`[data-code="${id}"]`)?.classList.add('active');
}

function clearCodeHighlights() {
  bit$$('[data-code]').forEach(line => line.classList.remove('active'));
}

function setText(id, message) {
  bit$(`#${id}`).textContent = message;
}

function startAnimation(scene) {
  scene.runId += 1;
  return scene.runId;
}

function stopAnimation(scene) {
  scene.runId += 1;
}

function waitBit(ms, scene, runId) {
  return new Promise(resolve => {
    setTimeout(() => resolve(runId === scene.runId), ms);
  });
}

initFenwick();
