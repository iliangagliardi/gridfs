/**
 * upload.js — dropzone, tag chips, queued uploads with real progress bars,
 * and the extraction receipt shown when each file lands.
 *
 * The receipt is the centrepiece of the upload story: it reports what Tika
 * actually pulled out of the file and how much of it went into the index.
 */

import * as api from './api.js';
import { $, el, num, bytesStr, escapeHtml } from './util.js';
import { toastOk, toastApiError, chipEditor } from './ui.js';

/** Tags applied to everything in the current upload batch. */
let tagInput = null;

/** Called with each newly stored file so the grid can animate it in. */
let onStored = () => {};

/* ── Tag chips ───────────────────────────────────────────────────────────── */

/**
 * Mount the shared chip editor over the placeholder markup, so the upload tags
 * and the metadata editor in the viewer are literally the same control.
 */
function initTags() {
  const placeholder = $('#tag-chips');
  tagInput = chipEditor({
    placeholder: 'Type a tag, press Enter',
    label: 'Add a tag to this upload',
    inputId: 'tag-input',
  });
  tagInput.node.id = 'tag-chips';
  placeholder.replaceWith(tagInput.node);
}

/* ── Extraction receipt ──────────────────────────────────────────────────── */

/**
 * Turn a StoredFile into a plain-language sentence about what was indexed.
 * This is the line the client should remember.
 */
function receiptHeadline(f) {
  const ext = (f.filename?.split('.').pop() || 'file').toUpperCase();
  switch (f.extractionState) {
    case 'EXTRACTED':
      return f.textLength > 0
        ? `Pulled <b>${num(f.textLength)}</b> characters out of your ${escapeHtml(ext)} and indexed them.`
        : `Stored. This ${escapeHtml(ext)} carried no text layer.`;
    case 'TRUNCATED':
      return `Pulled <b>${num(f.textLength)}</b> characters and indexed them. The text was clipped to stay under the BSON document limit.`;
    case 'SKIPPED':
      return `Stored as binary. No text layer to index in a ${escapeHtml(ext)}.`;
    case 'FAILED':
      return `Stored and downloadable, but text extraction failed on this ${escapeHtml(ext)}.`;
    default:
      return 'Stored.';
  }
}

/** A small fact pair: label + value in mono. */
function fact(label, value) {
  return el('span', { class: 'receipt__fact' }, [label, el('b', { text: value })]);
}

/** Build the receipt block appended under a completed queue item. */
function buildReceipt(f) {
  const wrap = el('div', { class: 'receipt' });

  wrap.append(el('p', { class: 'receipt__headline', html: receiptHeadline(f) }));
  wrap.append(el('span', {
    class: `state state--${f.extractionState}`,
    text: f.extractionState,
  }));

  if (f.textLength > 0) wrap.append(fact('characters', num(f.textLength)));
  if (f.extractionMethod && f.extractionMethod !== 'NONE') {
    wrap.append(fact('method', f.extractionMethod));
  }
  if (f.pageCount != null) wrap.append(fact('pages', num(f.pageCount)));
  if (f.contentType) wrap.append(fact('type', f.contentType));
  wrap.append(fact('size', bytesStr(f.length)));
  // Chunk count is derived the same way the server does it.
  const chunks = f.chunkSize ? Math.ceil(f.length / f.chunkSize) : 0;
  wrap.append(fact('chunks', num(chunks)));

  return wrap;
}

/* ── Queue ───────────────────────────────────────────────────────────────── */

/** Create the queue row for one file and return handles to update it. */
function queueItem(file) {
  const pct = el('span', { class: 'qitem__pct', text: '0%' });
  const fill = el('div', { class: 'bar__fill' });

  const item = el('li', { class: 'qitem' }, [
    el('div', { class: 'qitem__top' }, [
      el('span', { class: 'qitem__name', text: file.name, title: file.name }),
      pct,
    ]),
    el('div', { class: 'bar' }, [fill]),
  ]);

  $('#upload-queue').prepend(item);

  return {
    progress(p) {
      fill.style.width = p + '%';
      pct.textContent = p + '%';
    },
    done(stored) {
      pct.textContent = 'Stored';
      fill.style.width = '100%';
      item.append(buildReceipt(stored));
      // Receipts are the payoff; leave them on screen rather than auto-clearing.
    },
    error(message) {
      item.classList.add('is-error');
      pct.textContent = 'Failed';
      item.append(el('div', { class: 'receipt' }, [
        el('p', { class: 'receipt__headline', text: message }),
      ]));
    },
  };
}

/** Upload files one at a time so progress bars stay readable and ordered. */
async function uploadAll(fileList) {
  const files = Array.from(fileList);
  if (!files.length) return;

  // Commit a half-typed tag first, so it is not silently dropped from the batch.
  tagInput?.commitPending();
  const batchTags = tagInput ? tagInput.getValues() : [];

  for (const file of files) {
    const row = queueItem(file);
    try {
      const { promise } = api.uploadFile(file, { tags: batchTags }, (p) => row.progress(p));
      const stored = await promise;
      row.done(stored);
      toastOk('Uploaded', `${stored.filename} is stored and indexed.`);
      onStored(stored);
    } catch (err) {
      row.error(err?.detail || err?.message || 'Upload failed');
      toastApiError(`Could not upload ${file.name}`, err);
    }
  }
}

/* ── Dropzone ────────────────────────────────────────────────────────────── */

function initDropzone() {
  const zone = $('#dropzone');
  const input = $('#file-input');

  const open = () => input.click();

  $('#browse-btn').addEventListener('click', (e) => { e.stopPropagation(); open(); });
  zone.addEventListener('click', open);
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
  });

  input.addEventListener('change', () => {
    uploadAll(input.files);
    input.value = ''; // allow re-picking the same file
  });

  // dragenter/dragover must both preventDefault or the browser opens the file.
  let depth = 0;
  zone.addEventListener('dragenter', (e) => {
    e.preventDefault();
    depth++;
    zone.classList.add('is-over');
  });
  zone.addEventListener('dragover', (e) => { e.preventDefault(); });
  zone.addEventListener('dragleave', () => {
    // Counter guards against child-element dragleave noise.
    if (--depth <= 0) { depth = 0; zone.classList.remove('is-over'); }
  });
  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    depth = 0;
    zone.classList.remove('is-over');
    if (e.dataTransfer?.files?.length) uploadAll(e.dataTransfer.files);
  });

  // Dropping anywhere else should not navigate away from the demo.
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => e.preventDefault());
}

/* ── Init ────────────────────────────────────────────────────────────────── */

export function initUpload({ onFileStored } = {}) {
  onStored = onFileStored || (() => {});
  initTags();
  initDropzone();
}
