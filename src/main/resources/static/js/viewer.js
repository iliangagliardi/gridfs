/**
 * viewer.js — the file detail panel.
 *
 * Renders one of four stages depending on category:
 *   MEDIA    <video>/<audio> + jump controls + the GridFS chunk ribbon
 *   IMAGE    the image inline, plus a reader pane once there is text to show
 *   DOCUMENT the extracted text in a reader pane, with find-in-text
 *            (plus a native PDF iframe when the file is a PDF)
 *   OTHER    a download prompt
 *
 * Three actions sit in the header: Download, Edit metadata, and Run OCR.
 *
 * The chunk ribbon is the point of the media demo: because the server honours
 * Range, seeking to the middle of a file reads the chunk that covers that byte
 * offset rather than streaming from chunk 0. We compute the mapping client-side
 * from `length` and `chunkSize`, both of which are on StoredFile.
 */

import * as api from './api.js';
import {
  $, el, icon, num, bytesStr, relTime, fullTime, clock, parseClock,
  escapeHtml, pretty, couldOcr,
} from './util.js';
import {
  trapFocus, lockScroll, unlockScroll, toastOk, toastErr, toastApiError, chipEditor,
} from './ui.js';
import { getDeployment } from './stats.js';

/** Cells drawn in the ribbon. Real chunk counts can run to thousands. */
const MAX_CELLS = 96;

/**
 * Display names for ExtractionMethod. Spelled out rather than title-cased,
 * because pretty() would render TIKA_AND_OCR as "Tika And Ocr".
 */
const EXTRACTION_METHOD = {
  TIKA:         'Tika',
  OCR:          'OCR (Tesseract)',
  TIKA_AND_OCR: 'Tika + OCR',
  NONE:         'None',
};

let release = null;       // focus-trap teardown
let currentMedia = null;  // so closing can stop playback
let currentFile = null;   // the StoredFile currently on screen
let textPane = null;      // handle for injecting text into the stage
let editing = false;      // true while the metadata form is open
let ocrRunning = false;

/** Told to the host app whenever a file's metadata or text changed. */
let onChanged = () => {};

/* ── Chunk ribbon ────────────────────────────────────────────────────────── */

/**
 * Build the ribbon for a file.
 * @returns {{ node: HTMLElement, update: (byteOffset:number, label:string) => void }}
 */
function buildRibbon(file) {
  const chunkCount = file.chunkSize ? Math.ceil(file.length / file.chunkSize) : 0;
  const cells = Math.max(1, Math.min(chunkCount, MAX_CELLS));
  /** How many real chunks each drawn cell stands for. */
  const perCell = chunkCount / cells;

  const track = el('div', { class: 'ribbon__track' });
  const cellNodes = [];
  for (let i = 0; i < cells; i++) {
    const c = el('div', { class: 'ribbon__cell' });
    cellNodes.push(c);
    track.append(c);
  }

  const chunkNum = el('b', { text: '0' });
  const readout = el('div', { class: 'ribbon__readout' }, [
    'reading chunk ', chunkNum, ` of ${num(chunkCount)}`,
  ]);

  const node = el('div', { class: 'ribbon' }, [
    el('div', { class: 'ribbon__head' }, [
      el('span', { class: 'ribbon__title', text: 'GridFS chunk map' }),
      readout,
    ]),
    track,
    el('div', { class: 'ribbon__foot' }, [
      el('span', { text: `chunk size ${bytesStr(file.chunkSize)}` }),
      el('span', { text: `${num(chunkCount)} chunks · ${bytesStr(file.length)}` }),
    ]),
  ]);

  let lastChunk = -1;

  /**
   * Light the cell covering `byteOffset`.
   * @param {number} byteOffset
   * @param {string} label - e.g. "04:12", used in the spoken readout
   */
  function update(byteOffset, label) {
    if (!file.chunkSize) return;
    const chunk = Math.min(chunkCount - 1, Math.max(0, Math.floor(byteOffset / file.chunkSize)));
    if (chunk === lastChunk) return;
    lastChunk = chunk;

    const active = Math.min(cells - 1, Math.floor(chunk / perCell));
    cellNodes.forEach((c, i) => {
      c.classList.toggle('is-active', i === active);
      c.classList.toggle('is-read', i < active);
    });

    chunkNum.textContent = num(chunk);
    readout.lastChild.textContent = ` of ${num(chunkCount)}`;

    // Nudge the number so the change is legible during a live demo.
    chunkNum.classList.remove('is-hit');
    void chunkNum.offsetWidth; // restart the animation
    chunkNum.classList.add('is-hit');

    node.setAttribute(
      'aria-label',
      `Playback at ${label} reads GridFS chunk ${chunk} of ${chunkCount}`
    );
  }

  return { node, update, chunkCount };
}

/* ── Reader pane (find-in-document, and the OCR reveal target) ───────────── */

/**
 * A find bar over a scrollable reader.
 *
 * Returned as a handle rather than a bare node because OCR replaces its
 * contents in place later: that in-place swap is what makes the recognised
 * text visibly arrive during the demo.
 */
function buildTextPane() {
  const findInput = el('input', {
    class: 'find__input',
    type: 'search',
    placeholder: 'Find in this document…',
    'aria-label': 'Find in this document',
  });
  const count = el('span', { class: 'find__count', text: '' });
  const findBar = el('div', { class: 'find' }, [findInput, count]);

  const reader = el('div', {
    class: 'reader',
    tabindex: '0',
    role: 'region',
    'aria-label': 'Extracted text',
  });

  const node = el('div', {
    style: 'display:flex;flex-direction:column;gap:10px;flex:1;min-height:0',
  }, [findBar, reader]);

  let text = '';

  /** Client-side find: escape everything, then wrap matches in <mark>. */
  function find(term) {
    if (!term) {
      reader.textContent = text;
      count.textContent = text ? `${num(text.length)} characters` : '';
      return;
    }
    const safe = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(safe, 'gi');

    let hits = 0;
    reader.innerHTML = escapeHtml(text).replace(re, (m) => {
      hits++;
      return `<mark${hits === 1 ? ' class="is-current"' : ''}>${m}</mark>`;
    });

    count.textContent = hits ? `${num(hits)} match${hits === 1 ? '' : 'es'}` : 'No matches';
    reader.querySelector('mark')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  let findTimer;
  findInput.addEventListener('input', () => {
    clearTimeout(findTimer);
    findTimer = setTimeout(() => find(findInput.value.trim()), 140);
  });

  return {
    node,
    reader,

    /** Show a status line instead of document text (loading, or a failure). */
    setNotice(message) {
      text = '';
      findBar.hidden = true;
      reader.textContent = message;
    },

    /**
     * @param {string} value
     * @param {boolean} [reveal] - flash the pane, for text that just arrived
     */
    setText(value, reveal = false) {
      text = value || '';
      findBar.hidden = !text;
      findInput.value = '';
      reader.textContent = text;
      count.textContent = text ? `${num(text.length)} characters` : '';
      reader.scrollTop = 0;

      if (reveal) {
        reader.classList.remove('is-revealed');
        void reader.offsetWidth; // restart the animation
        reader.classList.add('is-revealed');
        node.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    },
  };
}

/** Fetch the extracted text into a pane, reporting failure in the pane itself. */
async function fillTextPane(pane, file, reveal = false) {
  pane.setNotice('Loading extracted text…');
  try {
    pane.setText(await api.getText(file.id), reveal);
  } catch {
    pane.setNotice('Could not load the extracted text for this file.');
  }
}

/* ── MEDIA stage ─────────────────────────────────────────────────────────── */

function mediaStage(file, stage) {
  const isVideo = (file.contentType || '').startsWith('video/');
  const player = el(isVideo ? 'video' : 'audio', {
    class: 'stage-media',
    src: api.contentUrl(file.id),
    controls: true,
    preload: 'metadata',
    playsinline: true,
  });
  currentMedia = player;

  const ribbon = buildRibbon(file);

  /** Map a playback position to a byte offset, then to a chunk. */
  const sync = () => {
    const duration = player.duration || (file.durationMillis ? file.durationMillis / 1000 : 0);
    if (!duration || !isFinite(duration)) return;
    const ratio = Math.min(1, Math.max(0, player.currentTime / duration));
    ribbon.update(ratio * file.length, clock(player.currentTime));
  };

  player.addEventListener('timeupdate', sync);
  player.addEventListener('seeked', sync);
  player.addEventListener('loadedmetadata', sync);

  /** Jump to a fraction of the file and report the chunk it lands in. */
  function jumpTo(fraction) {
    const duration = player.duration || (file.durationMillis ? file.durationMillis / 1000 : 0);
    if (!duration || !isFinite(duration)) return;
    player.currentTime = duration * fraction;
    sync();
  }

  const controls = el('div', { class: 'seek' }, [
    el('span', { class: 'seek__label', text: 'Jump to' }),
  ]);

  for (const pct of [0, 25, 50, 75]) {
    const b = el('button', { type: 'button', class: 'btn', text: `${pct}%` });
    b.addEventListener('click', () => jumpTo(pct / 100));
    controls.append(b);
  }

  // Explicit timecode entry, for "start it at 4:12" during a demo.
  const timeInput = el('input', {
    type: 'text',
    class: 'seek__time mono',
    placeholder: 'm:ss',
    'aria-label': 'Jump to a timecode, for example 4:12',
  });
  const go = el('button', { type: 'button', class: 'btn btn--primary', text: 'Go' });

  const seekToTyped = () => {
    const secs = parseClock(timeInput.value);
    if (secs == null) return;
    player.currentTime = secs;
    sync();
    player.play?.().catch(() => { /* autoplay may be blocked; seeking still worked */ });
  };
  go.addEventListener('click', seekToTyped);
  timeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); seekToTyped(); }
  });

  controls.append(timeInput, go);

  stage.append(
    player,
    controls,
    ribbon.node,
    el('p', {
      class: 'stage-note',
      text: 'The server answers each seek with a 206 Partial Content response, so playback starts from the chunk covering that byte offset instead of reading the file from the beginning.',
    })
  );
}

/* ── IMAGE stage ─────────────────────────────────────────────────────────── */

function imageStage(file, stage) {
  stage.append(el('img', {
    class: 'stage-img',
    src: api.contentUrl(file.id),
    alt: file.title || file.filename,
    loading: 'lazy',
  }));

  // Images normally carry no text. The pane is built anyway and kept hidden so
  // that a successful OCR run has somewhere to put its result immediately.
  textPane = buildTextPane();
  stage.append(textPane.node);

  if (file.textLength > 0) {
    fillTextPane(textPane, file);
  } else {
    textPane.node.hidden = true;
  }
}

/* ── DOCUMENT stage ──────────────────────────────────────────────────────── */

async function documentStage(file, stage) {
  const isPdf = (file.contentType || '').includes('pdf');

  // Browsers render PDFs natively, so offer the real thing alongside the text.
  if (isPdf) {
    stage.append(el('iframe', {
      class: 'stage-frame',
      src: api.contentUrl(file.id),
      title: `${file.filename} preview`,
    }));
  }

  textPane = buildTextPane();
  stage.append(textPane.node);

  const hasText = file.extractionState !== 'SKIPPED' && file.extractionState !== 'FAILED';
  if (!hasText) {
    textPane.setNotice(
      file.extractionState === 'FAILED'
        ? 'Extraction failed on this file. It is still stored and downloadable — and if it is a scanned PDF, Run OCR may recover the text.'
        : 'This file carries no text layer. If it is a scan, Run OCR will read the characters off the page.'
    );
    return;
  }

  await fillTextPane(textPane, file);
}

/* ── OTHER stage ─────────────────────────────────────────────────────────── */

function otherStage(file, stage) {
  const dl = el('a', {
    class: 'btn btn--primary',
    href: api.contentUrl(file.id, true),
    download: file.filename,
  }, [icon('i-download'), 'Download file']);

  stage.append(el('div', { class: 'empty' }, [
    el('p', { class: 'empty__title', text: 'No inline preview for this type' }),
    el('p', { class: 'empty__hint', text: `${file.contentType || 'Unknown type'} · ${bytesStr(file.length)}` }),
    el('div', { style: 'margin-top:16px' }, [dl]),
  ]));
}

/* ── Header actions ──────────────────────────────────────────────────────── */

/** Whether the Run OCR action should be offered for this file, right now. */
function ocrOffered(file) {
  const info = getDeployment();
  return Boolean(info?.ocrAvailable) && !file.ocrApplied && couldOcr(file);
}

/** A header button with an icon, a label that collapses on narrow screens. */
function actionButton(iconId, label, cls = 'btn') {
  return el('button', { type: 'button', class: cls, title: label }, [
    icon(iconId),
    el('span', { class: 'btn__label', text: label }),
  ]);
}

function renderActions(file) {
  const host = $('#viewer-actions');
  host.innerHTML = '';

  // Download. The card offers this too, but the detail panel is where a
  // presenter is standing when they decide they want the original bytes.
  const download = el('a', {
    class: 'btn',
    href: api.contentUrl(file.id, true),
    download: file.filename,
    title: `Download ${file.filename}`,
    'aria-label': `Download ${file.filename}`,
  }, [icon('i-download'), el('span', { class: 'btn__label', text: 'Download' })]);
  host.append(download);

  if (ocrOffered(file)) {
    const ocrBtn = actionButton('i-scan', 'Run OCR');
    ocrBtn.id = 'viewer-ocr';
    ocrBtn.addEventListener('click', () => runOcrFlow(ocrBtn));
    host.append(ocrBtn);
  }

  const editBtn = actionButton('i-edit', 'Edit', 'btn btn--primary');
  editBtn.id = 'viewer-edit';
  editBtn.addEventListener('click', () => openEditor());
  host.append(editBtn);
}

/* ── Metadata sidebar (read mode) ────────────────────────────────────────── */

function row(k, v, emphasis = false, mono = false) {
  if (v == null || v === '') return null;
  const cls = 'meta__v' + (emphasis ? ' meta__v--em' : '') + (mono ? ' meta__v--mono' : '');
  return el('div', { class: 'meta__row' }, [
    el('span', { class: 'meta__k', text: k }),
    el('span', { class: cls, text: String(v), title: String(v) }),
  ]);
}

function group(title, rows) {
  const kept = rows.filter(Boolean);
  if (!kept.length) return null;
  return el('div', { class: 'meta__group' }, [
    el('div', { class: 'meta__head', text: title }),
    ...kept,
  ]);
}

function renderMeta(file, host) {
  const chunks = file.chunkSize ? Math.ceil(file.length / file.chunkSize) : 0;

  host.innerHTML = '';

  const editBtn = el('button', {
    type: 'button', class: 'btn btn--sm', 'aria-label': 'Edit this file\'s metadata',
  }, [icon('i-edit'), 'Edit']);
  editBtn.addEventListener('click', () => openEditor());

  host.append(el('div', { class: 'meta__bar' }, [
    el('span', { class: 'eyebrow', text: 'Details' }),
    editBtn,
  ]));

  host.append(el('div', { class: 'meta__group' }, [
    el('div', { class: 'meta__head', text: 'Extraction' }),
    el('div', { style: 'display:flex;flex-wrap:wrap;gap:6px' }, [
      el('span', { class: `state state--${file.extractionState}`, text: file.extractionState || 'UNKNOWN' }),
      file.ocrApplied ? el('span', { class: 'state state--EXTRACTED', text: 'OCR APPLIED' }) : null,
    ]),
    row('Method', EXTRACTION_METHOD[file.extractionMethod] || 'None'),
    row('Characters indexed', num(file.textLength || 0), Boolean(file.textLength)),
  ]));

  host.append(group('Storage', [
    row('Size', bytesStr(file.length), true),
    row('Bytes', num(file.length)),
    row('Chunk size', bytesStr(file.chunkSize)),
    row('Chunks', num(chunks), true),
    row('Content type', file.contentType),
    row('Category', pretty(file.category)),
  ]));

  host.append(group('Content', [
    row('Pages', file.pageCount != null ? num(file.pageCount) : null),
    row('Title', file.title),
    row('Author', file.author),
    row('Duration', file.durationMillis != null ? clock(file.durationMillis / 1000) : null),
  ]));

  const provenance = group('Provenance', [
    row('Uploaded', fullTime(file.uploadDate)),
    row('When', relTime(file.uploadDate)),
    row('Uploaded by', file.uploadedBy),
  ]);
  if (provenance) {
    if (file.tags?.length) {
      provenance.append(el('div', { class: 'meta__tags' },
        file.tags.map((t) => el('span', { class: 'tag', text: t }))
      ));
    }
    host.append(provenance);
  }

  host.append(group('Identity', [
    row('File id', file.id, false, true),
    row('SHA-256', file.checksumSha256, false, true),
  ]));
}

/* ── Metadata sidebar (edit mode) ────────────────────────────────────────── */

/**
 * Turn the sidebar into a form over filename, title, author and tags.
 *
 * Only fields the user actually changed are sent. Per the contract an absent
 * key means "leave unchanged", so an untouched Author is omitted rather than
 * sent as "" — sending "" would blank it. Tags are the exception: they are sent
 * whenever they differ, including as [] to clear them, because [] is the only
 * way the API can empty them.
 */
function openEditor() {
  if (!currentFile || editing) return;
  editing = true;

  const file = currentFile;
  const host = $('#viewer-meta');
  host.innerHTML = '';

  const form = el('form', { class: 'meta__form', novalidate: true });

  const filename = el('input', {
    class: 'input', type: 'text', id: 'edit-filename',
    value: file.filename || '', required: true,
    'aria-describedby': 'edit-filename-error',
  });
  const filenameError = el('p', {
    class: 'field__error', id: 'edit-filename-error', role: 'alert', hidden: true,
  }, [icon('i-alert'), el('span', { text: 'Enter a filename.' })]);

  const title = el('input', { class: 'input', type: 'text', id: 'edit-title', value: file.title || '' });
  const author = el('input', { class: 'input', type: 'text', id: 'edit-author', value: file.author || '' });

  const tags = chipEditor({
    values: file.tags || [],
    placeholder: 'Add a tag, press Enter',
    label: 'Tags for this file',
    inputId: 'edit-tags',
  });

  const field = (labelText, forId, control, extra) => el('div', { class: 'field' }, [
    el('label', { class: 'field__label', for: forId, text: labelText }),
    control,
    extra,
  ]);

  const saveBtn = el('button', { type: 'submit', class: 'btn btn--primary' }, [icon('i-check'), 'Save']);
  const cancelBtn = el('button', { type: 'button', class: 'btn', text: 'Cancel' });

  form.append(
    el('div', { class: 'meta__bar' }, [el('span', { class: 'eyebrow', text: 'Edit metadata' })]),
    field('Filename', 'edit-filename', filename, filenameError),
    field('Title', 'edit-title', title),
    field('Author', 'edit-author', author),
    field('Tags', 'edit-tags', tags.node),
    el('p', {
      class: 'meta__note',
      text: 'Only the metadata sub-document is rewritten. The stored chunks, size and checksum are untouched.',
    }),
    el('div', { class: 'meta__formfoot' }, [cancelBtn, saveBtn]),
  );

  host.append(form);
  filename.focus();
  filename.select();

  function showError(show) {
    filenameError.hidden = !show;
    filename.setAttribute('aria-invalid', String(show));
    if (show) filename.focus();
  }

  filename.addEventListener('input', () => {
    if (filename.value.trim()) showError(false);
  });

  cancelBtn.addEventListener('click', () => closeEditor());

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    tags.commitPending();

    const nextName = filename.value.trim();
    if (!nextName) { showError(true); return; }

    // Build a minimal patch. Absent key = unchanged, per API-CONTRACT.md.
    const edit = {};
    if (nextName !== file.filename) edit.filename = nextName;
    if (title.value.trim() !== (file.title || '')) edit.title = title.value.trim();
    if (author.value.trim() !== (file.author || '')) edit.author = author.value.trim();

    const nextTags = tags.getValues();
    const prevTags = file.tags || [];
    const tagsChanged = nextTags.length !== prevTags.length
      || nextTags.some((t, i) => t !== prevTags[i]);
    if (tagsChanged) edit.tags = nextTags; // [] clears them, which is intended

    if (!Object.keys(edit).length) {
      closeEditor();
      return;
    }

    saveBtn.disabled = true;
    cancelBtn.disabled = true;
    saveBtn.textContent = 'Saving…';

    try {
      const updated = await api.patchMetadata(file.id, edit);
      editing = false;
      applyFile(updated);
      toastOk('Metadata saved', `${updated.filename} is up to date.`);
      onChanged(updated);
    } catch (err) {
      saveBtn.disabled = false;
      cancelBtn.disabled = false;
      saveBtn.innerHTML = '';
      saveBtn.append(icon('i-check'), 'Save');
      if (err?.status === 400) {
        showError(true);
        toastErr('Could not save', err.detail || 'The filename cannot be blank.');
      } else {
        toastApiError('Could not save the metadata', err);
      }
    }
  });
}

/** Leave edit mode without saving. */
function closeEditor() {
  if (!editing) return;
  editing = false;
  renderMeta(currentFile, $('#viewer-meta'));
  $('#viewer-edit')?.focus();
}

/* ── OCR ─────────────────────────────────────────────────────────────────── */

const OCR_STEPS = [
  'Reading the stored bytes back out of GridFS',
  'Rasterising the page for recognition',
  'Recognising characters',
  'Merging the text into the searchable index',
];

/**
 * Progress theatre for a slow, opaque server call.
 *
 * The server gives us no progress events, so this eases a bar towards 92% and
 * walks a named step list on the way. It never sits still and it never claims
 * to be finished: the last 8% is only closed by the real response.
 */
function ocrProgress(stage) {
  const fill = el('div', { class: 'bar__fill' });
  const step = el('span', { text: OCR_STEPS[0] });
  const pct = el('span', { text: '0%' });

  const card = el('div', { class: 'ocr', role: 'status', 'aria-live': 'polite' }, [
    el('div', { class: 'ocr__lead' }, [
      el('span', { class: 'spinner', 'aria-hidden': 'true' }),
      el('span', { class: 'ocr__title', text: 'Running OCR' }),
    ]),
    el('div', { class: 'bar' }, [fill]),
    el('div', { class: 'ocr__step' }, [step, pct]),
  ]);
  stage.prepend(card);
  card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });

  let value = 0;
  const timer = setInterval(() => {
    // Decreasing increments: fast at first, asymptotic towards the ceiling.
    value = Math.min(92, value + Math.max(0.4, (92 - value) * 0.045));
    fill.style.width = value + '%';
    pct.textContent = Math.round(value) + '%';
    step.textContent = OCR_STEPS[Math.min(OCR_STEPS.length - 1, Math.floor(value / 25))];
  }, 260);

  return {
    finish() {
      clearInterval(timer);
      fill.style.width = '100%';
      pct.textContent = '100%';
      step.textContent = 'Done';
      setTimeout(() => card.remove(), 900);
    },
    fail() {
      clearInterval(timer);
      card.remove();
    },
  };
}

/** Turn the documented OCR failure codes into something a presenter can read. */
function reportOcrFailure(err) {
  switch (err?.status) {
    case 409:
      toastErr('This file cannot be OCR\'d',
        err.detail || 'OCR only reads rasterised content — images, or a PDF with no text layer.');
      break;
    case 503:
      toastErr('OCR engine is not installed',
        err.detail || 'Install Tesseract on the server and restart it to enable this action.');
      break;
    case 404:
      toastErr('File not found', 'It may have been deleted while this panel was open.');
      break;
    default:
      toastApiError('OCR failed', err);
  }
}

async function runOcrFlow(button) {
  if (!currentFile || ocrRunning) return;
  const file = currentFile;
  ocrRunning = true;

  button.disabled = true;
  button.innerHTML = '';
  button.append(
    el('span', { class: 'spinner', 'aria-hidden': 'true' }),
    el('span', { class: 'btn__label', text: 'Running OCR…' })
  );
  button.setAttribute('aria-busy', 'true');

  const progress = ocrProgress($('#viewer-stage'));

  try {
    const updated = await api.runOcr(file.id);
    progress.finish();

    // The panel updates in place: new state, new character count, new method —
    // and then the recognised text itself arrives in the reader.
    applyFile(updated, { keepStage: true });
    // The OCR button has just been rebuilt away (ocrApplied is now true), so
    // move focus somewhere real rather than letting it fall back to <body>.
    $('#viewer-actions .btn')?.focus();

    if (textPane) {
      textPane.node.hidden = false;
      await fillTextPane(textPane, updated, true);
    }

    toastOk('OCR complete',
      `Recognised ${num(updated.textLength || 0)} characters and added them to the index.`);
    onChanged(updated);
  } catch (err) {
    progress.fail();
    reportOcrFailure(err);
    // Put the button back so the presenter can retry after fixing the server.
    renderActions(file);
    $('#viewer-ocr')?.focus();
  } finally {
    ocrRunning = false;
  }
}

/* ── Applying an updated file ────────────────────────────────────────────── */

/**
 * Adopt a fresh StoredFile: retitle the panel, rebuild the header actions and
 * repaint the sidebar. The stage is left alone by default because rebuilding it
 * would restart media playback and drop the reader's scroll position.
 */
function applyFile(file, { keepStage = true } = {}) {
  currentFile = file;
  $('#viewer-title').textContent = file.filename;
  renderActions(file);
  renderMeta(file, $('#viewer-meta'));
  if (!keepStage) renderStage(file);
}

function renderStage(file) {
  const stage = $('#viewer-stage');
  stage.innerHTML = '';
  textPane = null;

  switch (file.category) {
    case 'MEDIA':    mediaStage(file, stage); break;
    case 'IMAGE':    imageStage(file, stage); break;
    case 'DOCUMENT': return documentStage(file, stage);
    default:         otherStage(file, stage); break;
  }
  return Promise.resolve();
}

/* ── Open / close ────────────────────────────────────────────────────────── */

export function closeViewer() {
  const overlay = $('#viewer');
  if (overlay.hidden) return;

  // Stop playback and drop the connection rather than leaving a Range stream open.
  if (currentMedia) {
    currentMedia.pause?.();
    currentMedia.removeAttribute('src');
    currentMedia.load?.();
    currentMedia = null;
  }

  overlay.hidden = true;
  $('#viewer-stage').innerHTML = '';
  $('#viewer-meta').innerHTML = '';
  $('#viewer-actions').innerHTML = '';
  currentFile = null;
  textPane = null;
  editing = false;
  ocrRunning = false;
  release?.();
  release = null;
  unlockScroll();
}

/** Fetch a file and open the detail panel on it. */
export async function openViewer(id) {
  const overlay = $('#viewer');
  const stage = $('#viewer-stage');
  const meta = $('#viewer-meta');

  stage.innerHTML = '';
  meta.innerHTML = '';
  $('#viewer-actions').innerHTML = '';
  $('#viewer-title').textContent = 'Loading…';
  editing = false;
  ocrRunning = false;

  overlay.hidden = false;
  lockScroll();
  // Escape backs out of the editor first, and only closes the panel once there
  // is nothing unsaved to lose.
  release = trapFocus(overlay.querySelector('.modal'), () => {
    if (editing) closeEditor();
    else closeViewer();
  });

  let file;
  try {
    file = await api.getFile(id);
  } catch (err) {
    toastApiError('Could not open the file', err);
    closeViewer();
    return;
  }

  currentFile = file;
  $('#viewer-title').textContent = file.filename;
  renderActions(file);
  renderMeta(file, meta);
  await renderStage(file);
}

export function initViewer({ onFileChanged } = {}) {
  onChanged = onFileChanged || (() => {});

  $('#viewer-close').addEventListener('click', closeViewer);
  // Click the backdrop, not the modal, to dismiss.
  $('#viewer').addEventListener('mousedown', (e) => {
    if (e.target.id === 'viewer') closeViewer();
  });
}
