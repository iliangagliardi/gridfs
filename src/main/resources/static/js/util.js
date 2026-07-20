/**
 * util.js — formatting, escaping and small DOM helpers.
 * No state, no side effects. Everything here is pure except $/$$/el.
 */

/* ── DOM ─────────────────────────────────────────────────────────────────── */

export const $  = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/**
 * Build an element.
 * @param {string} tag
 * @param {object} [attrs] - `class`, `text`, `html`, dataset via data-*, else setAttribute
 * @param {Array<Node|string>} [children]
 */
export function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k === 'html') node.innerHTML = v;
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of [].concat(children)) {
    if (c == null) continue;
    node.append(c);
  }
  return node;
}

/** An <svg><use href="#id"> icon reference from the inline sprite. */
export function icon(id, cls = 'icon') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', cls);
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#' + id);
  svg.append(use);
  return svg;
}

/* ── Escaping ────────────────────────────────────────────────────────────── */

/** Escape text for safe interpolation into innerHTML. */
export function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Search snippets arrive containing <mark> tags from Atlas Search highlighting
 * and must be rendered as HTML.
 *
 * <p>Deliberately escapes ONLY angle brackets, not ampersands. The server has
 * already HTML-escaped the untrusted document text before wrapping it in
 * <mark>, so a full escapeHtml() here would escape its entities a second time
 * and render a literal "keeper&#39;s" on screen instead of "keeper's".
 *
 * <p>This stays XSS-safe: every < and > is neutralised first and only the
 * <mark> pair is re-admitted afterwards, so no other tag can survive regardless
 * of what the server sent. Leaving & alone cannot introduce a tag on its own.
 */
export function sanitizeSnippet(s) {
  return String(s ?? '')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/&lt;mark&gt;/g, '<mark>')
    .replace(/&lt;\/mark&gt;/g, '</mark>');
}

/* ── Numbers, bytes, time ────────────────────────────────────────────────── */

const NUM = new Intl.NumberFormat('en-US');

/** 18422 -> "18,422" */
export const num = (n) => NUM.format(Number(n) || 0);

/**
 * Human-readable byte size. Binary units, because GridFS chunk sizes are
 * powers of two and mixing decimal units here would misreport chunk maths.
 * Returns { value, unit } so the caller can typeset the unit separately.
 */
export function bytes(n) {
  const b = Number(n) || 0;
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0, v = b;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  const value = i === 0 ? String(v) : v.toFixed(v < 10 ? 1 : 0);
  return { value, unit: units[i] };
}

/** "2.4 MB" */
export function bytesStr(n) {
  const { value, unit } = bytes(n);
  return `${value} ${unit}`;
}

/**
 * Parse whatever the server sends for an Instant. Jackson emits an ISO-8601
 * string when JavaTimeModule is registered and a numeric epoch otherwise, so
 * accept both rather than assuming.
 */
export function toDate(v) {
  if (v == null) return null;
  if (typeof v === 'number') return new Date(v < 1e12 ? v * 1000 : v);
  const d = new Date(v);
  return isNaN(d.getTime()) ? null : d;
}

const REL_STEPS = [
  [60, 'second', 1],
  [3600, 'minute', 60],
  [86400, 'hour', 3600],
  [604800, 'day', 86400],
  [2629800, 'week', 604800],
  [31557600, 'month', 2629800],
  [Infinity, 'year', 31557600],
];

/** "2 hours ago" */
export function relTime(v) {
  const d = toDate(v);
  if (!d) return '—';
  const secs = (Date.now() - d.getTime()) / 1000;
  if (secs < 45) return 'just now';
  const abs = Math.abs(secs);
  for (const [limit, unit, div] of REL_STEPS) {
    if (abs < limit) {
      const n = Math.round(abs / div);
      return `${n} ${unit}${n === 1 ? '' : 's'} ago`;
    }
  }
  return d.toLocaleDateString();
}

/** Full timestamp for the metadata sidebar. */
export function fullTime(v) {
  const d = toDate(v);
  return d ? d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—';
}

/** 184000 ms -> "3:04" */
export function clock(ms) {
  const total = Math.max(0, Math.floor(Number(ms) || 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`;
}

/** "3:04" or "1:03:04" or "184" -> seconds. Returns null when unparseable. */
export function parseClock(str) {
  const parts = String(str).trim().split(':').map((p) => Number(p));
  if (parts.some((p) => isNaN(p))) return null;
  return parts.reduce((acc, p) => acc * 60 + p, 0);
}

/* ── Misc ────────────────────────────────────────────────────────────────── */

/** Trailing-edge debounce. */
export function debounce(fn, wait = 220) {
  let t;
  const wrapped = (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), wait);
  };
  wrapped.cancel = () => clearTimeout(t);
  return wrapped;
}

/** Sprite id for a FileCategory. */
export function categoryIcon(category) {
  switch (category) {
    case 'DOCUMENT': return 'i-doc';
    case 'IMAGE':    return 'i-image';
    case 'MEDIA':    return 'i-media';
    default:         return 'i-other';
  }
}

export const CATEGORIES = ['DOCUMENT', 'MEDIA', 'IMAGE', 'OTHER'];

/**
 * Whether this file is a candidate for OCR, judged from the file alone.
 *
 * Tesseract only helps with rasterised content: an image, or a PDF that came
 * back from Tika with no text layer. A PDF that already extracted cleanly does
 * not need it, and audio/video/archives never will. The caller must also check
 * `ocrApplied` and the deployment's `ocrAvailable` before offering the action —
 * this function deliberately answers only the "is it that kind of file" half.
 */
export function couldOcr(file) {
  if (!file) return false;
  if (file.category === 'IMAGE') return true;

  const isPdf = (file.contentType || '').includes('pdf');
  const hasText = Number(file.textLength) > 0;
  return isPdf && !hasText;
}

/** Title-case a SCREAMING_CASE enum for display. */
export const pretty = (s) =>
  String(s ?? '').toLowerCase().replace(/(^|_)(\w)/g, (_, p, c) => (p ? ' ' : '') + c.toUpperCase());
