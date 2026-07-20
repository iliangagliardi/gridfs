# API contract — frozen. Do not change without updating every consumer.

Base package: `com.mongodb.demo.gridfs`
All JSON uses the exact field names of the records in `domain/`.

## Files

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| POST | `/api/files` | multipart: `file` (required), `tags` (optional CSV string), `uploadedBy` (optional) | `201` + `StoredFile` |
| GET | `/api/files` | `page` (default 0), `size` (default 20) | `PageResponse` |
| GET | `/api/files/{id}` | — | `StoredFile` or `404` |
| DELETE | `/api/files/{id}` | — | `204`, or `404` |
| GET | `/api/files/{id}/content` | `download` (bool, default false) | bytes; **must support `Range`** |
| GET | `/api/files/{id}/text` | — | `text/plain` extracted content |
| PATCH | `/api/files/{id}/metadata` | `MetadataEdit` JSON | updated `StoredFile`, or `404` |
| POST | `/api/files/{id}/ocr` | — | updated `StoredFile`; `404` unknown id; `409` when not OCR-able; `503` when tesseract is absent |

`MetadataEdit` = `{ "filename": string?, "title": string?, "author": string?, "tags": string[]? }`

- An **absent** key means "leave unchanged". An explicit `null` also means unchanged.
- `"tags": []` clears all tags — that is the only way to empty them.
- `"title": ""` / `"author": ""` **clear** those fields. Empty string is a real value;
  only `null`/absent means unchanged. (Verified against the running service.)
- Tags are normalised on write — trimmed, lowercased, de-duplicated — on **both** the
  upload and the PATCH path. This is load-bearing: the Atlas Search index maps
  `metadata.tags` as a `token` type with no lowercase normalizer, so `"MongoDB"` and
  `"mongodb"` would otherwise be different tokens and tag filtering would silently miss.
- `filename` must be non-blank if present; blank is `400`.
- `length`, `chunkSize`, `checksumSha256`, `uploadDate` are **not** editable. Sending them is ignored, not an error.
- Editing does **not** touch `fs.chunks`; only the `fs.files` metadata sub-document is rewritten.

`POST /ocr` merges recognised text into `metadata.extractedText`, sets `ocrApplied: true` and
`extractionMethod` to `OCR` (or `TIKA_AND_OCR` when a text layer was already present), and updates
`textLength`. It is idempotent in effect but re-runs the engine each call.

`PageResponse` = `{ "items": StoredFile[], "total": long, "page": int, "size": int }`

`/content` behaviour:
- No `Range` header → `200`, `Content-Length`, `Accept-Ranges: bytes`.
- With `Range: bytes=START-END` → `206 Partial Content`, `Content-Range: bytes START-END/TOTAL`.
  Open-ended (`bytes=START-`) serves at most `gridfs-demo.media-chunk-bytes` for media,
  or the remainder for non-media.
- Unsatisfiable range → `416` with `Content-Range: bytes */TOTAL`.
- `download=true` → `Content-Disposition: attachment`, else `inline`.

## Search

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| POST | `/api/search` | `SearchRequest` JSON | `SearchResponse` |
| GET | `/api/search/autocomplete` | `q`, `limit` (default 8) | `string[]` |

## Admin / stats

| Method | Path | Returns |
|---|---|---|
| GET | `/api/stats` | `FileStorageService.StorageStats` |
| GET | `/api/admin/info` | `{ searchMode, searchIndexName, indexReady, mongoVersion, atlas, bucket, database, ocrAvailable, ocrEngine }` |
| POST | `/api/admin/search-index` | `{ created: boolean, mode: string }` |

## Errors

Every failure returns `{ "error": "...", "detail": "...", "status": int }` with the matching HTTP code.
Handled centrally in `web/ApiExceptionHandler`.

## Pages (Thymeleaf)

- `GET /` → `templates/index.html`, the single-page UI. Everything else is `fetch()` against the API above.
