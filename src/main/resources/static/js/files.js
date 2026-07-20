/**
 * files.js — the stored-files grid: paged loading, per-card actions, and
 * delete behind an in-page confirmation dialog.
 */

import * as api from './api.js';
import {
  $, el, icon, num, bytesStr, relTime, categoryIcon,
} from './util.js';
import {
  confirmDialog, toastOk, toastApiError, skeletonCards, emptyState,
} from './ui.js';

const PAGE_SIZE = 20;

const state = {
  page: 0,
  total: 0,
  loaded: 0,
  loading: false,
};

let openViewer = () => {};
/** Told to the host app whenever the stored set changes. */
let onChanged = () => {};

/* ── Card ────────────────────────────────────────────────────────────────── */

/** Icon-only action button with a real accessible name. */
function action(iconId, label, handler, danger = false) {
  const btn = el('button', {
    type: 'button',
    class: 'icon-btn' + (danger ? ' is-danger' : ''),
    'aria-label': label,
    title: label,
  }, [icon(iconId)]);

  btn.addEventListener('click', (e) => {
    e.stopPropagation(); // never open the viewer from an action button
    handler();
  });
  return btn;
}

export function fileCard(f, isNew = false) {
  const chunks = f.chunkSize ? Math.ceil(f.length / f.chunkSize) : 0;

  const card = el('article', {
    class: 'card' + (isNew ? ' is-new' : ''),
    role: 'button',
    tabindex: '0',
    'data-id': f.id,
    'aria-label': `Open ${f.filename}`,
  }, [
    el('div', { class: 'card__top' }, [
      el('div', { class: 'card__icon', 'data-cat': f.category }, [icon(categoryIcon(f.category))]),
      el('div', { class: 'card__id' }, [
        el('h3', { class: 'card__name', text: f.filename, title: f.filename }),
        el('div', {
          class: 'card__sub',
          text: `${bytesStr(f.length)} · ${relTime(f.uploadDate)}`,
          title: `${num(chunks)} GridFS chunk${chunks === 1 ? '' : 's'}`,
        }),
      ]),
    ]),
  ]);

  if (f.tags?.length) {
    card.append(el('div', { class: 'card__tags' },
      f.tags.slice(0, 4).map((t) => el('span', { class: 'tag', text: t }))
    ));
  }

  const state_ = f.extractionState
    ? el('span', { class: `state state--${f.extractionState}`, text: f.extractionState })
    : el('span');

  card.append(el('div', { class: 'card__foot' }, [
    state_,
    el('div', { class: 'card__actions' }, [
      action('i-eye', `Preview ${f.filename}`, () => openViewer(f.id)),
      action('i-download', `Download ${f.filename}`, () => {
        // Anchor + download flag, so the server sets Content-Disposition.
        const a = el('a', { href: api.contentUrl(f.id, true), download: f.filename });
        document.body.append(a);
        a.click();
        a.remove();
      }),
      action('i-trash', `Delete ${f.filename}`, () => removeFile(f), true),
    ]),
  ]));

  const open = () => openViewer(f.id);
  card.addEventListener('click', open);
  card.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
  });

  return card;
}

/* ── Delete ──────────────────────────────────────────────────────────────── */

export async function removeFile(f) {
  const body = el('span', {}, [
    'This removes ',
    el('b', { text: f.filename }),
    ' and its chunks from GridFS. It cannot be undone.',
  ]);

  const ok = await confirmDialog({
    title: 'Delete this file?',
    body,
    confirmLabel: 'Delete file',
  });
  if (!ok) return;

  try {
    await api.deleteFile(f.id);
    const card = $(`.card[data-id="${CSS.escape(f.id)}"]`);
    if (card) {
      card.style.transition = 'opacity 180ms, transform 180ms';
      card.style.opacity = '0';
      card.style.transform = 'scale(.96)';
      setTimeout(() => card.remove(), 180);
    }
    toastOk('Deleted', `${f.filename} is gone.`);
    onChanged();
  } catch (err) {
    toastApiError('Could not delete the file', err);
  }
}

/* ── Grid ────────────────────────────────────────────────────────────────── */

function updateCount() {
  const label = $('#files-count');
  label.textContent = state.total
    ? `${num(state.loaded)} of ${num(state.total)}`
    : '';

  const more = $('#load-more');
  const end = $('#grid-end');
  const hasMore = state.loaded < state.total;

  more.hidden = !hasMore;
  end.hidden = hasMore || state.total === 0;
}

/**
 * Load one page into the grid.
 * @param {boolean} append - false replaces the grid (initial load / refresh)
 */
export async function loadFiles(append = false) {
  if (state.loading) return;
  state.loading = true;

  const grid = $('#file-grid');
  if (!append) {
    state.page = 0;
    state.loaded = 0;
    grid.innerHTML = '';
    grid.append(...skeletonCards(6));
  }

  const more = $('#load-more');
  more.disabled = true;

  try {
    const res = await api.listFiles(state.page, PAGE_SIZE);
    if (!append) grid.innerHTML = '';

    state.total = res.total;
    state.loaded += res.items.length;

    if (!state.total) {
      grid.innerHTML = '';
      const empty = emptyState(
        'No files stored yet',
        'Drop a PDF, an image or a video above. Text is extracted and indexed as it lands.'
      );
      empty.style.gridColumn = '1 / -1';
      grid.append(empty);
      updateCount();
      return;
    }

    for (const f of res.items) grid.append(fileCard(f));
    updateCount();
  } catch (err) {
    if (!append) {
      grid.innerHTML = '';
      const failed = emptyState('Could not load your files', 'Check that the server is running, then try again.');
      failed.style.gridColumn = '1 / -1';
      grid.append(failed);
    }
    toastApiError('Could not load files', err);
  } finally {
    state.loading = false;
    more.disabled = false;
  }
}

/**
 * Swap a card for a rebuilt one after its metadata changed.
 *
 * Called by the viewer once PATCH /metadata comes back, so the grid reflects a
 * rename or a new tag set without a full reload. A no-op when the file is not
 * currently on screen, which is the normal case for a file found by search.
 */
export function updateFileCard(f) {
  const existing = $(`.card[data-id="${CSS.escape(f.id)}"]`);
  if (!existing) return;
  existing.replaceWith(fileCard(f));
}

/** Slot a freshly uploaded file at the top of the grid with an entrance. */
export function prependFile(f) {
  const grid = $('#file-grid');
  const empty = grid.querySelector('.empty');
  if (empty) empty.remove();

  grid.prepend(fileCard(f, true));
  state.total += 1;
  state.loaded += 1;
  updateCount();
}

export function initFiles({ onOpenFile, onChange } = {}) {
  openViewer = onOpenFile || (() => {});
  onChanged = onChange || (() => {});

  $('#load-more').addEventListener('click', () => {
    state.page += 1;
    loadFiles(true);
  });

  loadFiles();
}
