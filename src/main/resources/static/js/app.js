/**
 * app.js — entry point. Owns the theme and wires the feature modules together.
 *
 * Module map
 *   api.js     every server call, matching API-CONTRACT.md
 *   util.js    formatting, escaping, DOM helpers
 *   ui.js      toasts, confirm dialog, focus trap, skeletons
 *   stats.js   dashboard strip + engine badge
 *   upload.js  dropzone, tags, progress, extraction receipts
 *   search.js  search bar, autocomplete, facets, explain panel
 *   files.js   the stored-files grid
 *   viewer.js  the detail modal
 */

import { $ } from './util.js';
import { refreshDashboard } from './stats.js';
import { initUpload } from './upload.js';
import { initSearch, refreshSearch, updateSearchResult } from './search.js';
import { initFiles, prependFile, updateFileCard } from './files.js';
import { initViewer, openViewer } from './viewer.js';

/* ── Theme ───────────────────────────────────────────────────────────────── */

const THEME_KEY = 'gridfs-theme';

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  const btn = $('#theme-toggle');
  const next = theme === 'dark' ? 'light' : 'dark';
  btn.setAttribute('aria-label', `Switch to ${next} theme`);
  btn.title = `Switch to ${next} theme`;
}

function initTheme() {
  // Light is the default: it is what mongodb.com and Atlas look like, and the
  // client compares this page against them directly. An explicit choice wins.
  const saved = localStorage.getItem(THEME_KEY);
  applyTheme(saved === 'light' || saved === 'dark' ? saved : 'light');

  $('#theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    try { localStorage.setItem(THEME_KEY, next); } catch { /* private mode */ }
  });
}

/* ── Boot ────────────────────────────────────────────────────────────────── */

function boot() {
  initTheme();

  initViewer({
    // A metadata edit or an OCR run changes what the card and the result row
    // say, and OCR also moves the indexed-character count in the stats strip.
    onFileChanged: (file) => {
      updateFileCard(file);
      updateSearchResult(file);
      refreshDashboard();
    },
  });

  refreshDashboard();

  initFiles({
    onOpenFile: openViewer,
    // A delete changes the counts and can change facet counts too.
    onChange: () => { refreshDashboard(); refreshSearch(); },
  });

  initSearch({ onOpenFile: openViewer });

  initUpload({
    onFileStored: (stored) => {
      prependFile(stored);
      refreshDashboard();
      refreshSearch();
    },
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}
