/**
 * ui.js — cross-cutting interface pieces: toasts, the confirmation dialog,
 * and the modal focus trap shared by every overlay.
 *
 * Note: this app never calls alert()/confirm()/prompt(). They block the page
 * and stall the browser automation used to test the demo.
 */

import { $, el } from './util.js';

/* ── Toasts ──────────────────────────────────────────────────────────────── */

const TOAST_MS = 4600;

/**
 * @param {'success'|'error'|'info'} kind
 * @param {string} title  - short, active voice ("Uploaded", "Delete failed")
 * @param {string} [body] - optional detail
 */
export function toast(kind, title, body = '') {
  const host = $('#toasts');
  if (!host) return;

  const msg = el('div', { class: 'toast__msg' }, [el('b', { text: title })]);
  if (body) msg.append(document.createTextNode(body));

  const node = el('div', { class: `toast toast--${kind}`, role: 'status' }, [msg]);
  host.append(node);

  const remove = () => {
    node.classList.add('is-leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
    // Fallback for reduced-motion, where the animation is ~0ms and may not fire
    setTimeout(() => node.remove(), 400);
  };
  const timer = setTimeout(remove, TOAST_MS);
  node.addEventListener('click', () => { clearTimeout(timer); remove(); });
}

export const toastOk    = (t, b) => toast('success', t, b);
export const toastErr   = (t, b) => toast('error', t, b);
export const toastInfo  = (t, b) => toast('info', t, b);

/** Render an ApiError consistently. */
export function toastApiError(prefix, err) {
  toastErr(prefix, err?.detail || err?.message || 'Unexpected error');
}

/* ── Focus trap + overlay plumbing ───────────────────────────────────────── */

const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])',
  'select:not([disabled])', 'textarea:not([disabled])',
  'video[controls]', 'audio[controls]', 'summary', '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Trap Tab inside `container` and route Escape to `onEscape`.
 * Returns a release() that restores focus to wherever it was.
 */
export function trapFocus(container, onEscape) {
  const previous = document.activeElement;

  const first = container.querySelector(FOCUSABLE);
  (first || container).focus?.();

  function onKey(e) {
    if (e.key === 'Escape') {
      e.preventDefault();
      onEscape?.();
      return;
    }
    if (e.key !== 'Tab') return;

    // Query live: modal contents change as media and text load in.
    const items = Array.from(container.querySelectorAll(FOCUSABLE))
      .filter((n) => n.offsetParent !== null || n === document.activeElement);
    if (!items.length) return;

    const firstEl = items[0];
    const lastEl = items[items.length - 1];

    if (e.shiftKey && document.activeElement === firstEl) {
      e.preventDefault();
      lastEl.focus();
    } else if (!e.shiftKey && document.activeElement === lastEl) {
      e.preventDefault();
      firstEl.focus();
    }
  }

  document.addEventListener('keydown', onKey, true);

  return function release() {
    document.removeEventListener('keydown', onKey, true);
    previous?.focus?.();
  };
}

/** Prevent the page behind an overlay from scrolling. */
let openOverlays = 0;
export function lockScroll() {
  if (openOverlays++ === 0) document.body.style.overflow = 'hidden';
}
export function unlockScroll() {
  if (--openOverlays <= 0) {
    openOverlays = 0;
    document.body.style.overflow = '';
  }
}

/* ── Confirmation dialog ─────────────────────────────────────────────────── */

/**
 * In-page replacement for confirm().
 * @returns {Promise<boolean>} true when the user confirms.
 */
export function confirmDialog({ title, body, confirmLabel = 'Delete', danger = true }) {
  return new Promise((resolve) => {
    const overlay = $('#confirm');
    const okBtn = $('#confirm-ok');
    const cancelBtn = $('#confirm-cancel');

    $('#confirm-title').textContent = title;
    $('#confirm-body').innerHTML = '';
    $('#confirm-body').append(body instanceof Node ? body : document.createTextNode(body));

    okBtn.textContent = confirmLabel;
    okBtn.className = danger ? 'btn btn--danger' : 'btn btn--primary';

    overlay.hidden = false;
    lockScroll();
    const release = trapFocus(overlay.querySelector('.modal'), () => close(false));
    // Land on the safe choice, not the destructive one.
    cancelBtn.focus();

    function close(result) {
      overlay.hidden = true;
      release();
      unlockScroll();
      okBtn.removeEventListener('click', onOk);
      cancelBtn.removeEventListener('click', onCancel);
      overlay.removeEventListener('mousedown', onBackdrop);
      resolve(result);
    }

    const onOk = () => close(true);
    const onCancel = () => close(false);
    const onBackdrop = (e) => { if (e.target === overlay) close(false); };

    okBtn.addEventListener('click', onOk);
    cancelBtn.addEventListener('click', onCancel);
    overlay.addEventListener('mousedown', onBackdrop);
  });
}

/* ── Skeletons ───────────────────────────────────────────────────────────── */

/** Placeholder cards shown while the first page of files is in flight. */
export function skeletonCards(n = 6) {
  return Array.from({ length: n }, () =>
    el('div', { class: 'card' }, [
      el('div', { class: 'card__top' }, [
        el('div', { class: 'card__icon skeleton' }),
        el('div', { class: 'card__id' }, [
          el('div', { class: 'skeleton', style: 'height:13px;width:80%' }),
          el('div', { class: 'skeleton', style: 'height:10px;width:45%;margin-top:8px' }),
        ]),
      ]),
      el('div', { class: 'skeleton', style: 'height:26px;width:100%' }),
    ])
  );
}

/* ── Chip editor ─────────────────────────────────────────────────────────── */

/**
 * A tag input that turns typed words into removable chips.
 *
 * Shared by the upload tag bar and the metadata editor in the viewer so the two
 * behave identically: Enter or comma commits, Backspace on an empty field pops
 * the last chip, a half-typed tag is committed on blur rather than lost, and
 * pasting "a, b, c" produces three chips.
 *
 * @param {{ values?: string[], placeholder?: string, label?: string, inputId?: string }} opts
 * @returns {{ node: HTMLElement, input: HTMLInputElement, getValues: () => string[],
 *             setValues: (v: string[]) => void, commitPending: () => void, focus: () => void }}
 */
export function chipEditor({ values = [], placeholder = 'Type a tag, press Enter',
                             label = 'Add a tag', inputId } = {}) {
  const tags = new Set(values.map((t) => String(t).trim().toLowerCase()).filter(Boolean));

  const input = el('input', {
    class: 'chips__input',
    type: 'text',
    placeholder,
    'aria-label': label,
    autocomplete: 'off',
    id: inputId,
  });

  const node = el('div', { class: 'chips' }, [input]);

  function render() {
    node.querySelectorAll('.chip').forEach((c) => c.remove());
    for (const t of tags) {
      const x = el('button', {
        type: 'button', class: 'chip__x', 'aria-label': `Remove tag ${t}`, text: '×',
      });
      x.addEventListener('click', () => { tags.delete(t); render(); input.focus(); });
      node.insertBefore(el('span', { class: 'chip' }, [t, x]), input);
    }
  }

  function add(raw) {
    const t = String(raw).trim().replace(/,+$/, '').toLowerCase();
    if (t) tags.add(t);
    render();
  }

  function commitPending() {
    if (input.value.trim()) { add(input.value); input.value = ''; }
  }

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      add(input.value);
      input.value = '';
    } else if (e.key === 'Backspace' && input.value === '' && tags.size) {
      const last = Array.from(tags).pop();
      tags.delete(last);
      render();
    }
  });

  input.addEventListener('blur', commitPending);

  input.addEventListener('paste', (e) => {
    const text = e.clipboardData?.getData('text') || '';
    if (text.includes(',')) {
      e.preventDefault();
      text.split(',').forEach(add);
      input.value = '';
    }
  });

  // Clicking the padding of the box should land in the field, as chip inputs do.
  node.addEventListener('mousedown', (e) => {
    if (e.target === node) { e.preventDefault(); input.focus(); }
  });

  render();

  return {
    node,
    input,
    getValues: () => Array.from(tags),
    setValues: (v) => {
      tags.clear();
      v.forEach((t) => { const s = String(t).trim().toLowerCase(); if (s) tags.add(s); });
      render();
    },
    commitPending,
    focus: () => input.focus(),
  };
}

/** Empty state with a call to action rather than a shrug. */
export function emptyState(title, hint) {
  return el('div', { class: 'empty' }, [
    el('p', { class: 'empty__title', text: title }),
    el('p', { class: 'empty__hint', text: hint }),
  ]);
}
