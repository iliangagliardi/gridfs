/**
 * stats.js — the dashboard strip and the search-engine badge.
 *
 * The badge is the demo's opening talking point: it says, at a glance, whether
 * this deployment is serving $search from a real index or has degraded to the
 * regex fallback, and whether it is Atlas or on-prem.
 */

import * as api from './api.js';
import { $, el, num, bytes, CATEGORIES } from './util.js';

const CAT_LABEL = { DOCUMENT: 'Docs', MEDIA: 'Media', IMAGE: 'Images', OTHER: 'Other' };

/** Cache of the last admin/info payload, read by the viewer for chunk maths. */
export let deployment = null;

/* ── Stats strip ─────────────────────────────────────────────────────────── */

/** Write a value + optional unit into one stat tile. */
function setStat(key, value, unit) {
  const tile = $(`.stat[data-stat="${key}"] .stat__value`);
  if (!tile) return;
  tile.classList.remove('skeleton-text');
  tile.textContent = value;
  if (unit) tile.append(el('sub', { text: unit }));
}

/** Proportional bar + key for the category breakdown. */
function renderBreakdown(byCategory = {}) {
  const host = $('#stat-breakdown');
  if (!host) return;
  host.innerHTML = '';

  const entries = CATEGORIES
    .map((c) => [c, Number(byCategory[c] || 0)])
    .filter(([, n]) => n > 0);

  if (!entries.length) {
    host.append(el('span', { class: 'breakdown__key', text: 'Nothing stored yet' }));
    return;
  }

  const total = entries.reduce((s, [, n]) => s + n, 0);
  const bar = el('div', { class: 'breakdown__bar' });
  const keys = el('div', { class: 'breakdown__keys' });

  for (const [cat, n] of entries) {
    const seg = el('div', {
      class: 'breakdown__seg',
      'data-cat': cat,
      style: `flex-grow:${n}`,
      title: `${CAT_LABEL[cat]}: ${num(n)}`,
    });
    bar.append(seg);

    // Colour comes from the --cat-* tokens via [data-cat], so light and dark
    // both stay in the LeafyGreen palette without a second lookup table here.
    keys.append(el('span', { class: 'breakdown__key' }, [
      el('span', { class: 'breakdown__dot', 'data-cat': cat }),
      `${CAT_LABEL[cat]} ${num(n)}`,
    ]));
  }

  // Percentage of the largest bucket, as a quiet secondary fact
  host.append(bar, keys);
  host.title = `${num(total)} files across ${entries.length} categories`;
}

/** Fetch and paint /api/stats. Safe to call repeatedly. */
export async function refreshStats() {
  try {
    const s = await api.stats();

    setStat('fileCount', num(s.fileCount));

    const stored = bytes(s.totalBytes);
    setStat('totalBytes', stored.value, stored.unit);

    setStat('chunkCount', num(s.chunkCount));

    const text = bytes(s.indexedTextBytes);
    setStat('indexedTextBytes', text.value, text.unit);

    renderBreakdown(s.byCategory);
    return s;
  } catch (err) {
    // A failed stats call must not take the page down; degrade to dashes.
    for (const k of ['fileCount', 'totalBytes', 'chunkCount', 'indexedTextBytes']) {
      setStat(k, '—');
    }
    renderBreakdown({});
    return null;
  }
}

/* ── Engine badge ────────────────────────────────────────────────────────── */

/** Read the cached deployment info. Null until the first refreshEngine(). */
export function getDeployment() {
  return deployment;
}

/** Paint the OCR chip from /api/admin/info. */
function renderOcrBadge(info) {
  const badge = $('#ocr-badge');
  const modeEl = $('#ocr-mode');
  const metaEl = $('#ocr-meta');
  if (!badge) return;

  if (!info) {
    badge.className = 'chipstat is-off';
    modeEl.textContent = 'OCR';
    metaEl.textContent = 'Unknown';
    badge.title = 'Could not read deployment info.';
    return;
  }

  if (info.ocrAvailable) {
    badge.className = 'chipstat is-live';
    modeEl.textContent = 'OCR READY';
    metaEl.textContent = info.ocrEngine || 'Tesseract';
    badge.title = `Optical character recognition is available (${info.ocrEngine || 'Tesseract'}). `
      + 'Images and text-free PDFs can be run through it from the file detail panel.';
  } else {
    badge.className = 'chipstat is-off';
    modeEl.textContent = 'OCR OFF';
    metaEl.textContent = info.ocrEngine || 'Engine not installed';
    badge.title = 'No OCR engine on this server, so the Run OCR action is hidden.';
  }
}

/** Fetch /api/admin/info and render the search-mode and OCR badges. */
export async function refreshEngine() {
  const badge = $('#engine-badge');
  const modeEl = $('#engine-mode');
  const metaEl = $('#engine-meta');
  if (!badge) return null;

  try {
    const info = await api.adminInfo();
    deployment = info;

    const isAtlas = info.searchMode === 'ATLAS_SEARCH';
    const ready = Boolean(info.indexReady);

    // Live green only when $search is genuinely serving from a built index.
    badge.className = 'chipstat '
      + (isAtlas ? (ready ? 'is-live' : 'is-warn') : 'is-off');

    modeEl.textContent = isAtlas ? 'ATLAS SEARCH' : 'REGEX FALLBACK';

    // Three facts the client asks for, in one line: host, version, index state.
    const host = info.atlas ? 'Atlas' : 'Self-managed';
    const version = info.mongoVersion ? `MongoDB ${info.mongoVersion}` : 'MongoDB';
    const index = isAtlas
      ? (ready ? `${info.searchIndexName} ready` : `${info.searchIndexName} building`)
      : 'no search index';

    metaEl.textContent = `${host} · ${version} · ${index}`;

    badge.title = isAtlas
      ? `$search is serving queries from the "${info.searchIndexName}" index.`
      : 'No search node available, so queries fall back to regex matching.';

    // Footer provenance: which database and bucket this page is looking at.
    const footer = $('#footer-db');
    if (footer && info.database) {
      footer.textContent = `${info.database} · bucket "${info.bucket}"`;
    }

    renderOcrBadge(info);
    return info;
  } catch (err) {
    badge.className = 'chipstat is-off';
    modeEl.textContent = 'Unavailable';
    metaEl.textContent = 'Could not read deployment info';
    renderOcrBadge(null);
    return null;
  }
}

/** Refresh both halves of the dashboard together. */
export function refreshDashboard() {
  return Promise.all([refreshStats(), refreshEngine()]);
}
