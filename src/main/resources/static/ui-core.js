(function (window, document) {
  'use strict';

  const dialogState = new WeakMap();
  const busyState = new WeakMap();
  let lockedDialogs = 0;
  let toastTimer = null;

  const focusableSelector = [
    'a[href]', 'area[href]', 'button:not([disabled])', 'input:not([disabled])',
    'select:not([disabled])', 'textarea:not([disabled])',
    '[contenteditable="true"]', '[tabindex]:not([tabindex="-1"])'
  ].join(',');

  function asElement(dialog) {
    return dialog && dialog.nodeType === 1 ? dialog : null;
  }

  function focusables(dialog) {
    return Array.from(dialog.querySelectorAll(focusableSelector))
      .filter((element) => element.offsetParent !== null || element === document.activeElement);
  }

  function ensureDialog(dialog) {
    dialog = asElement(dialog);
    if (!dialog) return null;
    if (!dialog.getAttribute('role')) dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    if (dialogState.has(dialog)) return dialogState.get(dialog);

    const state = {trigger: null, previousOverflow: null};
    dialogState.set(dialog, state);
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog && dialog.dataset.closeOnBackdrop !== 'false') closeDialog(dialog);
    });
    dialog.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeDialog(dialog);
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusables(dialog);
      if (!items.length) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    });
    return state;
  }

  function openDialog(dialog, trigger) {
    dialog = asElement(dialog);
    if (!dialog) return;
    const state = ensureDialog(dialog);
    const alreadyOpen = !dialog.classList.contains('hidden') && !dialog.hidden;
    state.trigger = trigger || document.activeElement;
    if (dialog.classList.contains('hidden')) dialog.classList.remove('hidden');
    dialog.hidden = false;
    if (!alreadyOpen && lockedDialogs === 0) {
      state.previousOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    }
    if (!alreadyOpen) lockedDialogs += 1;
    const items = focusables(dialog);
    window.setTimeout(() => (items[0] || dialog).focus(), 0);
  }

  function closeDialog(dialog) {
    dialog = asElement(dialog);
    if (!dialog) return;
    const state = ensureDialog(dialog);
    const wasOpen = !dialog.classList.contains('hidden') && !dialog.hidden;
    dialog.classList.add('hidden');
    dialog.hidden = true;
    if (wasOpen && lockedDialogs > 0) lockedDialogs -= 1;
    if (lockedDialogs === 0) {
      document.body.style.overflow = state.previousOverflow || '';
    }
    if (state.trigger && typeof state.trigger.focus === 'function' && document.contains(state.trigger)) {
      window.setTimeout(() => state.trigger.focus(), 0);
    }
  }

  function toast(message, options) {
    options = options || {};
    let element = options.element || document.querySelector(options.selector || '#toast');
    if (!element) {
      element = document.createElement('div');
      element.id = 'toast';
      element.className = 'toast';
      element.setAttribute('role', 'status');
      document.body.appendChild(element);
    }
    element.textContent = message == null ? '' : String(message);
    element.classList.toggle('toast-error', options.variant === 'error' || options.type === 'error');
    element.classList.toggle('toast-success', options.variant === 'success' || options.type === 'success');
    element.classList.add('show');
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => element.classList.remove('show'), Number(options.duration) || 3500);
  }

  function setBusy(button, busy, label) {
    if (!button) return;
    if (busy) {
      if (!busyState.has(button)) busyState.set(button, {disabled: button.disabled, html: button.innerHTML});
      button.disabled = true;
      button.setAttribute('aria-busy', 'true');
      const labelElement = button.querySelector('span');
      if (labelElement && label != null) labelElement.textContent = label;
      else if (label != null) button.textContent = label;
    } else {
      const previous = busyState.get(button);
      if (previous) {
        button.innerHTML = previous.html;
        button.disabled = previous.disabled;
        busyState.delete(button);
      } else {
        button.disabled = false;
      }
      button.removeAttribute('aria-busy');
    }
  }

  function setupAccountMenu() {
    document.querySelectorAll('.account-actions').forEach((actions) => {
      if (actions.dataset.accountMenuReady === 'true') return;
      if (actions.id === 'anonymousActions' || actions.classList.contains('anonymous-actions')) return;
      const children = Array.from(actions.children);
      if (children.length < 2) return;
      const panel = document.createElement('div');
      panel.className = 'account-menu-panel';
      panel.id = `${actions.id || 'account-menu'}-panel-${Math.random().toString(36).slice(2, 7)}`;
      children.forEach((child) => panel.appendChild(child));
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'account-menu-toggle ghost-btn';
      toggle.textContent = '账号';
      toggle.setAttribute('aria-expanded', 'false');
      toggle.setAttribute('aria-controls', panel.id);
      toggle.setAttribute('aria-haspopup', 'true');
      actions.append(toggle, panel);
      actions.dataset.accountMenuReady = 'true';
      const close = (restoreFocus = false) => {
        actions.classList.remove('account-menu-open');
        toggle.setAttribute('aria-expanded', 'false');
        if (restoreFocus) toggle.focus();
      };
      toggle.addEventListener('click', () => {
        const expanded = actions.classList.toggle('account-menu-open');
        toggle.setAttribute('aria-expanded', String(expanded));
      });
      document.addEventListener('click', (event) => {
        if (!actions.contains(event.target)) close();
      });
      actions.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && actions.classList.contains('account-menu-open')) {
          event.preventDefault();
          close(true);
        }
      });
      const closeOnResize = () => {
        if (window.innerWidth > 980) close();
      };
      window.addEventListener('resize', closeOnResize, {passive: true});
    });
  }

  window.DataForgeUI = {openDialog, closeDialog, toast, setBusy};
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setupAccountMenu, {once: true});
  else setupAccountMenu();
})(window, document);
