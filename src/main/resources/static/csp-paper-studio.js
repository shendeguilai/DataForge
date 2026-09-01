(() => {
  const $ = selector => document.querySelector(selector);
  const MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;
  const API_ROOT = '/api/tools/csp-paper-studio';
  const state = {
    filename: '', preamble: '', modules: [], analysis: null, selected: null,
    originals: new Map(), filter: 'all', timer: null, authenticated: false,
    analysisController: null, analysisRevision: 0
  };
  const els = {
    fileInput: $('#fileInput'), openBtn: $('#openBtn'), sampleBtn: $('#sampleBtn'),
    downloadMdBtn: $('#downloadMdBtn'), exportBtn: $('#exportBtn'), filename: $('#filename'),
    summary: $('#summary'), moduleCount: $('#moduleCount'), moduleList: $('#moduleList'),
    preview: $('#preview'), previewTitle: $('#previewTitle'), previewMeta: $('#previewMeta'),
    moduleStatus: $('#moduleStatus'), issues: $('#issues'), editor: $('#editor'),
    restoreBtn: $('#restoreBtn'), numbering: $('#numbering'), toast: $('#toast'),
    workspace: $('#workspace'), liveState: $('#liveState'), privacy: $('#studioPrivacy'),
    loginButton: $('#studioLoginButton')
  };

  function toast(message) {
    if (window.DataForgeUI?.toast) {
      window.DataForgeUI.toast(message, {duration: 2200});
      return;
    }
    els.toast.textContent = message;
    els.toast.classList.add('show');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => els.toast.classList.remove('show'), 2200);
  }

  function esc(value = '') {
    return String(value).replace(/[&<>"']/g, char => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
  }

  function setAuthenticated(authenticated) {
    state.authenticated = authenticated;
    const loaded = Boolean(state.modules.length);
    els.openBtn.disabled = !authenticated;
    els.sampleBtn.disabled = !authenticated;
    els.numbering.disabled = !authenticated;
    els.downloadMdBtn.disabled = !authenticated || !loaded;
    els.exportBtn.disabled = !authenticated || !loaded;
    els.editor.disabled = !authenticated || !loaded;
    els.restoreBtn.disabled = !authenticated || !loaded;
    els.privacy.classList.toggle('authenticated', authenticated);
    if (authenticated) {
      els.privacy.firstElementChild.textContent = '文件仅发送到当前 DataForge 服务器解析，不会转交第三方或持久保存。';
      if (!loaded) els.summary.textContent = '打开一个 CSP 初赛 Markdown 文件开始检查。';
    } else {
      els.privacy.firstElementChild.textContent = '请先登录。当前编辑内容会保留在这个浏览器页面中。';
      if (!loaded) els.summary.textContent = '登录后打开一个 CSP 初赛 Markdown 文件开始检查。';
    }
  }

  function requestLogin(message = '登录后即可使用 CSP Paper Studio。') {
    const hint = $('#portalAuthView .auth-hint');
    if (hint) hint.textContent = message;
    if (typeof showPortalAuth === 'function') showPortalAuth('login');
  }

  async function checkAuthentication(showPrompt = false) {
    try {
      const response = await fetch('/api/auth/me', {headers: {'Accept': 'application/json'}});
      setAuthenticated(response.ok);
      if (!response.ok && showPrompt) requestLogin();
      return response.ok;
    } catch (_) {
      setAuthenticated(false);
      if (showPrompt) requestLogin('暂时无法确认登录状态，请稍后重试。');
      return false;
    }
  }

  async function apiFetch(url, options = {}) {
    const response = await fetch(url, options);
    if (response.status === 401) {
      setAuthenticated(false);
      requestLogin('登录状态已过期。重新登录后，当前编辑内容仍会保留。');
      throw new Error('登录状态已过期');
    }
    if (!response.ok) {
      let message = `请求失败 (${response.status})`;
      try {
        const body = await response.json();
        message = body.error || message;
      } catch (_) {}
      throw new Error(message);
    }
    return response;
  }

  function splitSource(markdown) {
    const pattern = /^## 第\s*(\d+)\s*题\s*$/gm;
    const matches = [...markdown.matchAll(pattern)];
    if (!matches.length) return {preamble: markdown, modules: []};
    const modules = [];
    for (let index = 0; index < matches.length; index++) {
      const start = matches[index].index + matches[index][0].length;
      const end = index + 1 < matches.length ? matches[index + 1].index : markdown.length;
      modules.push({number: Number(matches[index][1]), body: markdown.slice(start, end)});
    }
    return {preamble: markdown.slice(0, matches[0].index), modules};
  }

  function buildMd() {
    let output = state.preamble.trimEnd();
    for (const module of state.modules) {
      output += `\n\n## 第 ${module.number} 题\n` + module.body.replace(/^\n+/, '');
    }
    return output.trimEnd() + '\n';
  }

  async function analyze(selectAfter = true) {
    if (!state.authenticated) {
      requestLogin();
      throw new Error('请先登录');
    }
    const revision = ++state.analysisRevision;
    if (state.analysisController) state.analysisController.abort();
    const controller = new AbortController();
    state.analysisController = controller;
    const response = await apiFetch(`${API_ROOT}/analyze`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({markdown: buildMd()}),
      signal: controller.signal
    });
    const analysis = await response.json();
    if (revision !== state.analysisRevision) return false;
    state.analysis = analysis;
    updateSummary();
    renderList();
    if (selectAfter) {
      if (state.selected == null || !state.modules.some(module => module.number === state.selected)) {
        state.selected = state.modules[0]?.number ?? null;
      }
      renderSelected(true);
    }
    return true;
  }

  function updateSummary() {
    const analysis = state.analysis;
    const counts = analysis.counts;
    els.moduleCount.textContent = analysis.modules.length;
    els.filename.textContent = state.filename || '未命名.md';
    const group = `${analysis.meta.year} CSP-${analysis.meta.group}`;
    const extra = analysis.summary_errors.length ? ` · 整卷错误 ${analysis.summary_errors.length}` : '';
    els.summary.textContent = `${group} · 正常 ${counts.ok} · 提醒 ${counts.warning} · 错误 ${counts.error}${extra}`;
    els.workspace.classList.remove('empty');
    setAuthenticated(state.authenticated);
  }

  function kindName(kind, number) {
    return kind === 'choice' ? `第 ${number} 题` : kind === 'reading' ? `阅读程序 ${number - 15}` : `完善程序 ${number - 18}`;
  }
  function kindSub(kind) { return kind === 'choice' ? '单项选择题' : kind === 'reading' ? '程序阅读' : '程序填空'; }

  function renderList() {
    const modules = state.analysis?.modules || [];
    const visible = modules.filter(module => state.filter === 'all' || module.kind === state.filter);
    els.moduleList.innerHTML = visible.map(module => `<button class="module-item ${module.number === state.selected ? 'active' : ''}" data-no="${module.number}" type="button">
      <span class="dot ${module.status}"></span><span class="module-label"><b>${kindName(module.kind, module.number)}</b><small>${kindSub(module.kind)}${module.errors[0] ? ' · ' + esc(module.errors[0]) : ''}</small></span></button>`).join('') || '<div class="empty-side">当前筛选下没有模块。</div>';
    els.moduleList.querySelectorAll('.module-item').forEach(button => button.addEventListener('click', () => {
      state.selected = Number(button.dataset.no);
      renderList();
      renderSelected(true);
    }));
  }

  function texLite(tex) {
    let output = esc(tex.trim());
    output = output.replace(/\\mathtt\{([^{}]*)\}/g, '<code>$1</code>').replace(/\\tt\{([^{}]*)\}/g, '<code>$1</code>');
    output = output.replace(/\\sum_\{([^{}]*)\}\^\{([^{}]*)\}/g, '<span class="sum">∑</span><sub>$1</sub><sup>$2</sup>');
    output = output.replace(/\\sqrt\{([^{}]*)\}/g, '√($1)').replace(/\\times/g, '×').replace(/\\cdot/g, '·').replace(/\\in/g, '∈').replace(/\\leq/g, '≤').replace(/\\geq/g, '≥').replace(/\\sim/g, '∼').replace(/\\log/g, 'log');
    for (let index = 0; index < 3; index++) {
      output = output.replace(/([A-Za-z0-9)\]}]+)_\{([^{}]*)\}/g, '$1<sub>$2</sub>').replace(/([A-Za-z0-9)\]}]+)_([A-Za-z0-9]+)/g, '$1<sub>$2</sub>');
      output = output.replace(/([A-Za-z0-9)\]}]+)\^\{([^{}]*)\}/g, '$1<sup>$2</sup>').replace(/([A-Za-z0-9)\]}]+)\^([A-Za-z0-9]+)/g, '$1<sup>$2</sup>');
    }
    output = output.replace(/\\\{/g, '{').replace(/\\\}/g, '}').replace(/\\_/g, '_').replace(/\\%/g, '%');
    return `<span class="math">${output}</span>`;
  }

  function inline(value = '') {
    let output = '', position = 0;
    const pattern = /(\$[^$\n]*\$|`[^`\n]*`)/g;
    let match;
    while ((match = pattern.exec(value))) {
      output += esc(value.slice(position, match.index));
      const token = match[0];
      output += token[0] === '$' ? texLite(token.slice(1, -1)) : `<code>${esc(token.slice(1, -1))}</code>`;
      position = match.index + token.length;
    }
    return output + esc(value.slice(position));
  }

  function codeTable(meta) {
    return `<table class="code-box"><tbody>${meta.normalized_lines.map((line, index) => `<tr><td class="code-no">${String(index + 1).padStart(2, '0')}</td><td class="code-text">${esc(line) || '&nbsp;'}</td></tr>`).join('')}</tbody></table>`;
  }

  function optsHtml(options, layout) {
    const className = layout === '1x4' ? 'one' : layout === '2x2' ? 'two' : 'four';
    return `<div class="options ${className}" data-export-layout="${layout}">${options.map(option => `<div class="option">${inline(option)}</div>`).join('')}</div>`;
  }

  function fitPreviewOptions() {
    els.preview.querySelectorAll('.options.one').forEach(box => {
      box.classList.remove('preview-tight', 'preview-fallback-two');
      const overflow = () => [...box.querySelectorAll('.option')].some(option => option.scrollWidth > option.clientWidth + 2);
      if (overflow()) box.classList.add('preview-tight');
      if (overflow()) box.classList.add('preview-fallback-two');
    });
  }

  function previewHtml(module) {
    const preview = module.preview;
    if (!preview) return '<div class="empty-preview"><h2>这个模块暂时无法解析</h2><p>请根据上方错误提示修改右侧 Markdown。</p></div>';
    if (preview.type === 'choice') {
      let html = '', first = true;
      for (const block of preview.blocks) {
        if (block.type === 'text') {
          html += `<p class="q-stem">${first ? module.number + '. ' : ''}${inline(block.text)}</p>`;
          first = false;
        } else {
          if (first) { html += `<p class="q-stem">${module.number}.</p>`; first = false; }
          html += codeTable(block.code);
        }
      }
      return html + optsHtml(preview.options, preview.layout);
    }
    if (preview.type === 'reading') {
      let html = `<div class="section-title">（${preview.section_index}）阅读下列程序，回答问题。</div>${codeTable(preview.code)}`;
      if (preview.note) html += `<p class="note">${inline(preview.note)}</p>`;
      html += '<div class="type-title">·判断题：</div>';
      preview.judges.forEach((question, index) => {
        const label = preview.global_numbering ? (preview.judge_numbers?.[index] ?? index + 1) : `(${index + 1})`;
        html += `<p class="subq">${label}${preview.global_numbering ? '.' : ''} ${inline(question)}${preview.global_numbering && !/[（(]\s*[）)]\s*$/.test(question) ? '（ ）' : ''}</p>`;
      });
      html += '<div class="type-title">·单选题：</div>';
      preview.choices.forEach((choice, index) => {
        const label = preview.global_numbering ? (preview.choice_numbers?.[index] ?? index + 1) : `(${preview.judges.length + index + 1})`;
        html += `<p class="subq">${label}${preview.global_numbering ? '.' : ''} ${inline(choice.text)}</p>${optsHtml(choice.options, choice.layout)}`;
      });
      return html;
    }
    let html = '';
    if (preview.prose?.length) {
      html += `<p class="q-stem">（${preview.section_index}）${inline(preview.prose[0])}</p>`;
      preview.prose.slice(1).forEach(value => html += `<p class="note">${inline(value)}</p>`);
    } else {
      html += `<div class="section-title">（${preview.section_index}）</div>`;
    }
    html += codeTable(preview.code);
    preview.groups.forEach((group, index) => {
      const label = preview.global_numbering ? (preview.group_numbers?.[index] ?? index + 1) : `(${index + 1})`;
      html += `<p class="subq">${label}${preview.global_numbering ? '.' : ''} ${inline(group.text)}</p>${optsHtml(group.options, group.layout)}`;
    });
    return html;
  }

  function renderSelected(syncEditor = true) {
    const module = state.analysis?.modules.find(item => item.number === state.selected);
    const raw = state.modules.find(item => item.number === state.selected);
    if (!module || !raw) return;
    els.previewTitle.textContent = kindName(module.kind, module.number);
    const code = module.code?.[0];
    els.previewMeta.textContent = `${kindSub(module.kind)} · 公式 ${module.formula_count}${code ? ` · 代码 ${code.line_count} 行` : ''}`;
    els.moduleStatus.className = `status-pill ${module.status}`;
    els.moduleStatus.textContent = module.status === 'ok' ? '正常' : module.status === 'warning' ? '有提醒' : '解析错误';
    const messages = [...module.errors.map(message => ({type: 'error', message})), ...module.warnings];
    els.issues.hidden = !messages.length;
    els.issues.innerHTML = messages.map(item => `<div class="issue ${item.type === 'formula' || item.type === 'code' ? 'warning' : item.type}">${item.type === 'error' ? '错误' : '提醒'}：${esc(item.message)}${item.sample ? ` · <code>${esc(item.sample)}</code>` : ''}</div>`).join('');
    els.preview.innerHTML = previewHtml(module);
    requestAnimationFrame(fitPreviewOptions);
    if (syncEditor) els.editor.value = raw.body.replace(/^\n+|\n+$/g, '');
    setAuthenticated(state.authenticated);
  }

  async function loadMd(name, markdown) {
    if (new TextEncoder().encode(markdown).length > MAX_MARKDOWN_BYTES) {
      toast('Markdown 不能超过 2 MiB');
      return;
    }
    const source = splitSource(markdown);
    if (!source.modules.length) {
      toast('没有识别到“## 第 n 题”模块');
      return;
    }
    state.filename = name || '未命名.md';
    state.preamble = source.preamble;
    state.modules = source.modules;
    state.originals = new Map(source.modules.map(module => [module.number, module.body]));
    state.selected = source.modules[0]?.number ?? null;
    els.editor.disabled = true;
    els.summary.textContent = '正在解析……';
    try {
      await analyze(true);
      toast(`已载入 ${state.modules.length} 个模块`);
    } catch (error) {
      if (error.name !== 'AbortError') {
        toast(error.message || '解析失败');
        els.summary.textContent = error.message || '解析失败';
      }
    }
  }

  function commitEditor() {
    const module = state.modules.find(item => item.number === state.selected);
    if (!module) return;
    module.body = `\n\n${els.editor.value.trimEnd()}\n\n`;
    els.liveState.textContent = '正在重新渲染…';
    clearTimeout(state.timer);
    state.timer = setTimeout(async () => {
      try {
        const updated = await analyze(false);
        if (!updated) return;
        renderSelected(false);
        renderList();
        els.liveState.textContent = '实时渲染：已更新';
      } catch (error) {
        if (error.name !== 'AbortError') {
          els.liveState.textContent = `实时渲染：${error.message || '失败'}`;
        }
      }
    }, 420);
  }

  els.loginButton.addEventListener('click', () => requestLogin());
  els.openBtn.addEventListener('click', () => els.fileInput.click());
  els.fileInput.addEventListener('change', async () => {
    const file = els.fileInput.files[0];
    if (!file) return;
    if (file.size > MAX_MARKDOWN_BYTES) {
      toast('Markdown 不能超过 2 MiB');
    } else {
      await loadMd(file.name, await file.text());
    }
    els.fileInput.value = '';
  });
  els.sampleBtn.addEventListener('click', async () => {
    const keys = ['2022j', '2023j', '2024j', '2025s'];
    const label = prompt('输入示例：2022j / 2023j / 2024j / 2025s', '2022j');
    if (!label) return;
    const key = label.toLowerCase().replace(/\s/g, '');
    if (!keys.includes(key)) { toast('示例名称不正确'); return; }
    try {
      const response = await apiFetch(`${API_ROOT}/samples/${key}`, {headers: {'Accept': 'application/json'}});
      const sample = await response.json();
      await loadMd(sample.name, sample.markdown);
    } catch (error) { toast(error.message || '示例加载失败'); }
  });
  els.editor.addEventListener('input', commitEditor);
  els.restoreBtn.addEventListener('click', async () => {
    const module = state.modules.find(item => item.number === state.selected);
    if (!module) return;
    module.body = state.originals.get(module.number);
    try {
      await analyze(false);
      renderSelected(true);
      renderList();
      toast('已恢复当前模块');
    } catch (error) { if (error.name !== 'AbortError') toast(error.message || '恢复失败'); }
  });
  document.querySelectorAll('.filter').forEach(button => button.addEventListener('click', () => {
    document.querySelectorAll('.filter').forEach(item => item.classList.remove('active'));
    button.classList.add('active');
    state.filter = button.dataset.filter;
    renderList();
  }));
  els.downloadMdBtn.addEventListener('click', () => {
    const blob = new Blob([buildMd()], {type: 'text/markdown;charset=utf-8'});
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = state.filename || 'CSP试卷.md';
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    toast('已生成修改后的 MD');
  });
  els.exportBtn.addEventListener('click', async () => {
    els.exportBtn.disabled = true;
    const label = els.exportBtn.textContent;
    els.exportBtn.textContent = '正在导出…';
    try {
      const response = await apiFetch(`${API_ROOT}/export`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({markdown: buildMd(), filename: state.filename, numbering: els.numbering.value})
      });
      const blob = await response.blob();
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = (state.filename || 'CSP试卷.md').replace(/\.md$/i, '') + '.docx';
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 1500);
      toast('Word 已导出');
    } catch (error) {
      toast(error.message || '导出失败');
    } finally {
      els.exportBtn.textContent = label;
      setAuthenticated(state.authenticated);
    }
  });
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's' && !els.downloadMdBtn.disabled) {
      event.preventDefault();
      els.downloadMdBtn.click();
    }
  });
  window.addEventListener('dataforge:auth-changed', () => {
    setAuthenticated(true);
    toast('已登录，可以继续使用当前内容');
  });

  setAuthenticated(false);
  checkAuthentication(true).then(async authenticated => {
    if (!authenticated) return;
    const demo = new URLSearchParams(location.search).get('demo');
    if (!demo) return;
    try {
      const response = await apiFetch(`${API_ROOT}/samples/${demo.toLowerCase()}`);
      const sample = await response.json();
      await loadMd(sample.name, sample.markdown);
    } catch (error) { toast(error.message || '示例加载失败'); }
  });
})();
