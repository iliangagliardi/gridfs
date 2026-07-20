/**
 * api.js — the only place that talks to the server.
 * Every call here mirrors API-CONTRACT.md exactly. If the contract moves,
 * this file is the single edit.
 */

/** Errors carry the server's `{ error, detail, status }` shape when present. */
export class ApiError extends Error {
  constructor(message, status, detail) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
  }
}

/** Read the standard error envelope, falling back to the status line. */
async function fail(res) {
  let body = null;
  try { body = await res.json(); } catch { /* non-JSON error page */ }
  throw new ApiError(
    body?.error || `Request failed (${res.status})`,
    body?.status ?? res.status,
    body?.detail || ''
  );
}

async function json(res) {
  if (!res.ok) await fail(res);
  return res.status === 204 ? null : res.json();
}

/* ── Files ───────────────────────────────────────────────────────────────── */

/** GET /api/files -> PageResponse { items, total, page, size } */
export async function listFiles(page = 0, size = 20) {
  return json(await fetch(`/api/files?page=${page}&size=${size}`));
}

/** GET /api/files/{id} -> StoredFile */
export async function getFile(id) {
  return json(await fetch(`/api/files/${encodeURIComponent(id)}`));
}

/** DELETE /api/files/{id} -> 204 */
export async function deleteFile(id) {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await fail(res);
}

/**
 * PATCH /api/files/{id}/metadata -> updated StoredFile
 *
 * Per the contract, an absent key means "leave unchanged" and an explicit null
 * means the same. `tags: []` is the only way to clear tags, so callers must send
 * the array deliberately. Keys the server does not accept (length, chunkSize,
 * checksumSha256, uploadDate) are ignored rather than rejected, but we never
 * send them.
 *
 * @param {string} id
 * @param {{ filename?: string, title?: string, author?: string, tags?: string[] }} edit
 */
export async function patchMetadata(id, edit) {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}/metadata`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(edit),
  });
  return json(res);
}

/**
 * POST /api/files/{id}/ocr -> updated StoredFile
 *
 * Slow by nature: Tesseract runs synchronously on the server. The documented
 * failures are 404 (unknown id), 409 (file type cannot be OCR'd) and 503 (the
 * engine is not installed); all three surface as an ApiError carrying .status,
 * which the viewer turns into a specific message.
 */
export async function runOcr(id) {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}/ocr`, { method: 'POST' });
  return json(res);
}

/** GET /api/files/{id}/text -> text/plain extracted content */
export async function getText(id) {
  const res = await fetch(`/api/files/${encodeURIComponent(id)}/text`);
  if (!res.ok) await fail(res);
  return res.text();
}

/** URL for the raw bytes. Range-capable, so it feeds <video>/<audio>/<iframe>. */
export function contentUrl(id, download = false) {
  return `/api/files/${encodeURIComponent(id)}/content${download ? '?download=true' : ''}`;
}

/**
 * POST /api/files (multipart) with real upload progress.
 *
 * fetch() cannot report request-body progress, so this is deliberately XHR:
 * the per-file progress bar is driven by upload.onprogress.
 *
 * @param {File} file
 * @param {{ tags?: string[], uploadedBy?: string }} opts
 * @param {(pct:number, loaded:number, total:number) => void} onProgress
 * @returns {{ promise: Promise<StoredFile>, abort: () => void }}
 */
export function uploadFile(file, opts = {}, onProgress = () => {}) {
  const xhr = new XMLHttpRequest();

  const promise = new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('file', file);
    // `tags` is a CSV string per the contract, not repeated fields.
    if (opts.tags?.length) form.append('tags', opts.tags.join(','));
    if (opts.uploadedBy) form.append('uploadedBy', opts.uploadedBy);

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable) {
        onProgress(Math.round((e.loaded / e.total) * 100), e.loaded, e.total);
      }
    });

    xhr.addEventListener('load', () => {
      let body = null;
      try { body = JSON.parse(xhr.responseText); } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress(100, file.size, file.size);
        resolve(body);
      } else {
        reject(new ApiError(
          body?.error || `Upload failed (${xhr.status})`,
          body?.status ?? xhr.status,
          body?.detail || ''
        ));
      }
    });

    xhr.addEventListener('error',   () => reject(new ApiError('Network error during upload', 0, '')));
    xhr.addEventListener('abort',   () => reject(new ApiError('Upload cancelled', 0, '')));
    xhr.addEventListener('timeout', () => reject(new ApiError('Upload timed out', 0, '')));

    xhr.open('POST', '/api/files');
    xhr.send(form);
  });

  return { promise, abort: () => xhr.abort() };
}

/* ── Search ──────────────────────────────────────────────────────────────── */

/**
 * POST /api/search -> SearchResponse
 * Body matches the SearchRequest record field-for-field.
 */
export async function search({ query = '', categories = [], tags = [], fuzzy = false, page = 0, size = 20 } = {}) {
  const res = await fetch('/api/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, categories, tags, fuzzy, page, size }),
  });
  return json(res);
}

/** GET /api/search/autocomplete -> string[] */
export async function autocomplete(q, limit = 8, signal) {
  const res = await fetch(
    `/api/search/autocomplete?q=${encodeURIComponent(q)}&limit=${limit}`,
    { signal }
  );
  return json(res);
}

/* ── Admin / stats ───────────────────────────────────────────────────────── */

/** GET /api/stats -> StorageStats { fileCount, totalBytes, chunkCount, indexedTextBytes, byCategory } */
export async function stats() {
  return json(await fetch('/api/stats'));
}

/**
 * GET /api/admin/info
 * -> { searchMode, searchIndexName, indexReady, mongoVersion, atlas, bucket,
 *      database, ocrAvailable, ocrEngine }
 */
export async function adminInfo() {
  return json(await fetch('/api/admin/info'));
}
