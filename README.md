# MongoDB GridFS + Atlas Search demo

A single Spring Boot application that shows the complete lifecycle of a binary
object in MongoDB: **upload → extract text → index → search → stream with
seeking → delete**, with nothing but MongoDB behind it. No object store, no
separate search cluster, no second set of credentials.

Java 21 · Spring Boot 3.5.16 · Apache Tika 3.3.1 · MongoDB GridFS · Atlas Search

---

## What the demo actually shows

| | |
|---|---|
| **Files live in MongoDB** | GridFS splits every upload across `fs.files` + `fs.chunks`. No filesystem, no bucket. |
| **Text extraction on ingest** | Tika parses PDF/DOCX/XLSX/PPTX/HTML/EPUB and the result is written into the same `fs.files` document as the file it came from. |
| **Real `$search`** | Full-text relevance ranking, fuzzy matching, autocomplete and facets over the extracted text, using the `$search` aggregation stage. |
| **The same code on-prem and on Atlas** | `mongodb-atlas-local` gives you a genuine search node on a laptop. Point `MONGODB_URI` at Atlas and nothing else changes. |
| **Byte-range streaming** | HTTP `Range` requests map onto GridFS chunk reads, so seeking into the middle of a large video does not stream the file from the beginning. |
| **OCR over pixels** | Tesseract turns a scanned page into text and writes it back into the *same* `fs.files` metadata document, so it becomes searchable with no second system involved. |
| **Editable metadata** | `PATCH /api/files/{id}/metadata` rewrites only the metadata sub-document — `fs.chunks`, `length` and the checksum are untouched. |
| **Honest degradation** | If no search node is present the app falls back to a regex query and says so, rather than failing in front of an audience. If tesseract is absent, OCR reports itself unavailable and nothing else changes. |

---

## Architecture

### Storage layout

```
                    ┌───────────────────────────────────────────┐
   POST /api/files  │                 App                       │
   (multipart)      │                                           │
   ────────────────▶│  GridFsTemplate ──▶ split into chunks     │
                    │  Apache Tika    ──▶ extract text          │
                    │  Tesseract      ──▶ OCR (images, scans)   │
                    └──────────────┬────────────────────────────┘
                                   │
        ┌──────────────────────────┴──────────────────────────┐
        ▼                                                     ▼
┌──────────────────────────────────┐          ┌──────────────────────────────┐
│  fs.files    (one doc per file)  │          │  fs.chunks  (the payload)    │
│                                  │          │                              │
│  _id        ObjectId ◀───────────┼──────────┼── files_id  ObjectId         │
│  filename   "survey.pdf"         │          │   n         0,1,2,3,…        │
│  length     284718               │          │   data      BinData (255 KB) │
│  chunkSize  261120               │          │                              │
│  uploadDate ISODate              │          │  unique idx {files_id:1, n:1}│
│  metadata: {                     │          └──────────────────────────────┘
│    contentType   "application/pdf"           
│    category      DOCUMENT|MEDIA|IMAGE|OTHER   ▲
│    tags          ["finance","q3"]             │  Range: bytes=5242880-7340031
│    extractedText "…Tika output…"  ◀── indexed │  ⇒ read chunks 20..28, trim
│    extractionState EXTRACTED|TRUNCATED|       │     the two end chunks
│                    SKIPPED|FAILED             │  ⇒ 206 Partial Content
│    extractionMethod TIKA|OCR|                 │
│                    TIKA_AND_OCR|NONE          │
│    ocrApplied    false                        │
│    textLength    18422                        │
│    pageCount     12        (nullable)         │
│    author        "…"       (nullable)         │
│    title         "…"       (nullable)         │
│    durationMillis 184000   (nullable, media)  │
│    uploadedBy    "demo"                       │
│    checksumSha256 "…"                         │
│  }                                            │
└───────────────────────────┬───────────────────┘
                            │
                            ▼
              ┌───────────────────────────────┐
              │  Atlas Search index           │
              │  "gridfs_content" on fs.files │
              │                               │
              │  filename          string +   │
              │                    autocomplete + token
              │  metadata.extractedText  string
              │  metadata.title    string + autocomplete
              │  metadata.author   string
              │  metadata.category token      │
              │  metadata.tags     token      │
              │  metadata.contentType token   │
              │  uploadDate        date       │
              │  length            number     │
              └───────────────────────────────┘
```

The point worth making out loud: **the extracted text lives inside the
`fs.files` document, not in a side collection.** One document describes the
file, carries its searchable content, and points at its bytes. One backup, one
replication stream, one access-control model.

### Why `mongodb-atlas-local` and not `mongo`

`$search` is served by **mongot**, a separate search process that tails the
oplog. The plain `mongo` image does not ship it. `mongodb/mongodb-atlas-local`
bundles `mongod` + `mongot` and exposes the local Atlas Search admin API, so
`$search` and `createSearchIndex` work exactly as they do on Atlas. That is why
`docker-compose.yml` and `run.sh` both use that image.

---

## Prerequisites

| Tool | Why | Install |
|---|---|---|
| JDK 21 | build + run | `brew install openjdk@21` |
| Maven 3.9+ | build | `brew install maven` |
| Docker | local MongoDB with search | Docker Desktop or OrbStack |
| ffmpeg *(optional)* | generates the sample video/audio/images | `brew install ffmpeg` |
| tesseract *(optional)* | OCR over images and scanned PDFs | `brew install tesseract` |

**tesseract is genuinely optional.** With it absent, the app starts and runs
normally: `GET /api/admin/info` reports `"ocrAvailable": false` and
`"ocrEngine": null`, the **Run OCR** button is hidden in the UI, and every other
feature — upload, Tika extraction, `$search`, Range streaming, delete — is
unaffected. Installed here it reports `tesseract 5.5.2` with the `eng`, `osd`
and `snum` language data. See [OCR](#ocr) below.

There is no Maven wrapper (`./mvnw`) in this repo — use a `mvn` on your `PATH`,
or the Docker path below, which needs neither Java nor Maven installed locally.

On this machine the JDK lives at
`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; `run.sh`
exports that automatically.

---

## Quick start

### Option A — `./run.sh` (fastest inner loop)

```bash
./run.sh
```

It will, in order: export `JAVA_HOME`, check the Docker daemon is up, reuse an
existing `gridfs-mongo` container (or start it if stopped, or create it if
absent), wait for the container's healthcheck to go **healthy** — which only
happens once *both* `mongod` and `mongot` are answering — then run
`mvn spring-boot:run`.

It is safe to re-run at any time. Data survives. To start from an empty
database:

```bash
./run.sh --reset      # removes the container; the named volume is recreated
```

Then open <http://localhost:8081>.

### Option B — Docker Compose (nothing installed but Docker)

```bash
docker compose up --build
```

Brings up `mongodb` (atlas-local, named volumes for `/data/db` and
`/data/configdb`) and `app` (built from the `Dockerfile`, waiting on
`service_healthy`). Ports **8081** (host) → 8080 (container) and **27018** (host) → 27017 (container) are published.

```bash
docker compose logs -f app     # follow the app
docker compose down            # stop, keep data
docker compose down -v         # stop and delete the volumes
```

The first build downloads the whole Tika dependency tree. Expect several
minutes and a large image — see the note at the top of the `Dockerfile`.

### Load the sample corpus

```bash
./samples/generate-samples.sh    # writes samples/out/
./samples/upload-samples.sh      # POSTs all of it to /api/files with tags
```

`generate-samples.sh` degrades gracefully: each format is attempted with the
tools present and skipped with a message if they are not. On a stock macOS box
with ffmpeg installed you get:

| File | Generator | Purpose in the demo |
|---|---|---|
| `mongodb-sharding.md`, `coffee-roasting.txt`, `sailing-offshore.md`, `gridfs-architecture-notes.txt` | shell | Three unrelated topics, so search relevance is visibly meaningful |
| `lighthouse-survey.pdf` | `cupsfilter` (macOS built-in) | Multi-page. Contains **PHOSPHORESCENT ALBATROSS** on an *interior* page only |
| `quarterly-storage-review.docx` | `python-docx`, else a minimal OOXML zip via python3 stdlib | Contains **SEDIMENTARY BOOKKEEPING** and nothing else does |
| `seek-test-clip.mp4` | `ffmpeg` | 90 s, ~10 MB, ~40 GridFS chunks, with a burned-in running second counter |
| `tone-sweep.mp3` | `ffmpeg` | 120 s, pitch steps at 0:40 and 1:20 so a seek is *audible* |
| `colour-bars.png`, `mandelbrot.jpg` | `ffmpeg` | Image category |
| `scanned-invoice.png` | `cupsfilter` + `sips` (macOS built-ins) | The OCR asset. A page rendered to pixels, so it has **no text layer**. The marker phrase **TRILOBITE SEMAPHORE** exists only as pixels — searching it returns nothing until OCR runs |

`upload-samples.sh` respects `BASE_URL` (default `http://localhost:8081`) and
`UPLOADED_BY` (default `demo`). It has no de-duplication — running it twice
uploads two copies of everything.

---

## Pointing it at MongoDB Atlas

Nothing in the application code changes. Only the connection string does.

### 1. Set the URI and run with the `atlas` profile

```bash
export MONGODB_URI='mongodb+srv://USER:PASS@cluster0.abcde.mongodb.net/gridfs_demo?retryWrites=true&w=majority'
SPRING_PROFILES_ACTIVE=atlas ./run.sh
```

or against a built jar:

```bash
mvn -DskipTests package
java -jar target/gridfs-demo-1.0.0.jar --spring.profiles.active=atlas
```

`src/main/resources/application-atlas.yml` deliberately gives `MONGODB_URI` no
default, so starting the `atlas` profile without it is a hard startup failure
rather than a silent fallback to localhost. It also pins
`gridfs-demo.search-mode=ATLAS_SEARCH` (no probing, no silent regex fallback in
front of a client) and turns Thymeleaf caching on.

Make sure your client IP is on the Atlas **Network Access** allowlist and the
database user has `readWrite` on `gridfs_demo`.

### 2. Create the search index

The index definition is checked in at
`src/main/resources/atlas/gridfs_content.index.json`. It is a `dynamic: false`
mapping over `fs.files` covering `filename` (string + autocomplete + token),
`uploadDate`, `length`, and `metadata.{extractedText, title, author, category,
tags, contentType}`.

**Via the app** — easiest, and works on both Atlas and atlas-local:

```bash
curl -X POST http://localhost:8081/api/admin/search-index
# → { "created": true, "mode": "ATLAS_SEARCH" }
```

**Via `mongosh`:**

```bash
mongosh "$MONGODB_URI" --eval '
  db.fs.files.createSearchIndex(
    "gridfs_content",
    JSON.parse(cat("src/main/resources/atlas/gridfs_content.index.json")).definition
  )'
```

Or paste the `definition` object straight in:

```javascript
db.fs.files.createSearchIndex("gridfs_content", {
  mappings: {
    dynamic: false,
    fields: {
      filename: [
        { type: "string", analyzer: "lucene.standard" },
        { type: "autocomplete", tokenization: "edgeGram", minGrams: 2, maxGrams: 15 },
        { type: "token" }
      ],
      uploadDate: { type: "date" },
      length: { type: "number" },
      metadata: {
        type: "document",
        dynamic: false,
        fields: {
          extractedText: { type: "string", analyzer: "lucene.standard" },
          title: [
            { type: "string", analyzer: "lucene.standard" },
            { type: "autocomplete", tokenization: "edgeGram", minGrams: 2, maxGrams: 15 }
          ],
          author:      { type: "string", analyzer: "lucene.standard" },
          category:    { type: "token" },
          tags:        { type: "token" },
          contentType: { type: "token" }
        }
      }
    }
  }
})
```

**Via the Atlas UI:** *Atlas Search → Create Search Index → JSON Editor →*
database `gridfs_demo`, collection `fs.files`, index name `gridfs_content`,
then paste the `definition` object from the JSON file.

Check readiness:

```bash
curl -s http://localhost:8081/api/admin/info
mongosh "$MONGODB_URI" --eval 'db.fs.files.getSearchIndexes()'   # status: READY
```

Index builds are asynchronous. `queryable: false` / `status: PENDING` means
give it a minute — see [Troubleshooting](#troubleshooting).

---

## API reference

Derived from [`API-CONTRACT.md`](API-CONTRACT.md). Base package
`com.mongodb.demo.gridfs`. All JSON uses the exact field names of the records
in `domain/`.

### Files

| Method | Path | Body / params | Returns |
|---|---|---|---|
| `POST` | `/api/files` | multipart: `file` **(required)**, `tags` (CSV string, optional), `uploadedBy` (optional) | `201` + `StoredFile` |
| `GET` | `/api/files` | `page` (default `0`), `size` (default `20`) | `PageResponse` |
| `GET` | `/api/files/{id}` | — | `StoredFile`, or `404` |
| `DELETE` | `/api/files/{id}` | — | `204`, or `404` |
| `GET` | `/api/files/{id}/content` | `download` (bool, default `false`) | bytes; **supports `Range`** |
| `GET` | `/api/files/{id}/text` | — | `text/plain` extracted content |
| `PATCH` | `/api/files/{id}/metadata` | `MetadataEdit` JSON | updated `StoredFile`, or `404` |
| `POST` | `/api/files/{id}/ocr` | — | updated `StoredFile`; `404` unknown id; `409` not OCR-able; `503` no engine |

`PageResponse` = `{ "items": StoredFile[], "total": long, "page": int, "size": int }`

#### `PATCH /api/files/{id}/metadata`

`MetadataEdit` = `{ "filename": string?, "title": string?, "author": string?, "tags": string[]? }`

Only those four fields are editable. The semantics are precise and worth
reading twice — absent and empty are *not* the same thing:

| Sent | Effect |
|---|---|
| key absent | leave unchanged |
| `"title": null` | leave unchanged — an explicit `null` is the same as absent |
| `"title": ""` | **clears** the field. Empty string is a real value |
| `"author": ""` | **clears** the field |
| `"tags": ["A", " a ", "B"]` | stored as `["a","b"]` — trimmed, lowercased, de-duplicated |
| `"tags": []` | **clears** all tags. This is the only way to empty them |
| `"filename": ""` or whitespace | `400` |
| `filename` containing `/`, `\` or NUL | `400` |
| `length`, `chunkSize`, `checksumSha256`, `uploadDate` | ignored, not an error |

Tag normalisation happens on **both** the upload and the PATCH path, and it is
load-bearing rather than cosmetic: the Atlas Search index maps `metadata.tags`
as a `token` type with no lowercase normalizer, so `"MongoDB"` and `"mongodb"`
would be different tokens and tag filtering would silently miss.

Editing rewrites **only** the `fs.files` metadata sub-document. `fs.chunks`,
`length` and `checksumSha256` are untouched — the bytes are never rewritten to
change a title.

```bash
curl -s -X PATCH "http://localhost:8081/api/files/$ID/metadata" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Lighthouse survey 2026","tags":["report","archive"]}'

# clear the author, leave everything else alone
curl -s -X PATCH "http://localhost:8081/api/files/$ID/metadata" \
  -H 'Content-Type: application/json' -d '{"author":""}'
```

#### `POST /api/files/{id}/ocr`

Runs Tesseract over the file's bytes on demand and merges the recognised text
into `metadata.extractedText`. It sets `ocrApplied: true`, sets
`extractionMethod` to `OCR` (or `TIKA_AND_OCR` when a text layer was already
present) and updates `textLength`. Effectively idempotent, but it re-runs the
engine on every call.

| Status | When |
|---|---|
| `200` | ran; returns the updated `StoredFile` |
| `404` | unknown id |
| `409` | the file type cannot be OCR'd — e.g. `video/mp4`, with `detail` reading `Not an OCR candidate: …` |
| `503` | the `tesseract` binary is not on `PATH` |

```bash
curl -s -X POST "http://localhost:8081/api/files/$ID/ocr"
```

### Search

| Method | Path | Body / params | Returns |
|---|---|---|---|
| `POST` | `/api/search` | `SearchRequest` JSON | `SearchResponse` |
| `GET` | `/api/search/autocomplete` | `q`, `limit` (default `8`) | `string[]` |

### Admin / stats

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/stats` | `FileStorageService.StorageStats` |
| `GET` | `/api/admin/info` | `{ searchMode, searchIndexName, indexReady, mongoVersion, atlas, bucket, database, ocrAvailable, ocrEngine }` |
| `POST` | `/api/admin/search-index` | `{ created: boolean, mode: string }` |

`ocrAvailable` is `false` and `ocrEngine` is `null` when the `tesseract` binary
is not on `PATH`; otherwise `ocrEngine` is the version string, e.g.
`"tesseract 5.5.2"`. The UI uses this to decide whether to render the **Run
OCR** button at all.

### Pages

`GET /` → the single-page Thymeleaf UI (`templates/index.html`). Everything
else in the UI is `fetch()` against the API above. The UI is styled with
MongoDB's LeafyGreen design system (see [`DESIGN-TOKENS.md`](DESIGN-TOKENS.md));
light theme is the default, with a dark toggle. The file **detail** panel (the
viewer modal) carries a **Download** button, an **Edit** control for the
editable metadata fields, and a **Run OCR** button that appears only for an
OCR-able file that has not been OCR'd yet, and only when the engine is
available.

### Errors

Every failure returns `{ "error": "…", "detail": "…", "status": int }` with the
matching HTTP status, handled centrally in `web/ApiExceptionHandler`.

### Payload shapes

`StoredFile` — the `fs.files` document with `metadata` flattened up:

```json
{
  "id": "665f1c…",
  "filename": "lighthouse-survey.pdf",
  "length": 27134,
  "chunkSize": 261120,
  "uploadDate": "2026-07-19T09:58:12Z",
  "contentType": "application/pdf",
  "category": "DOCUMENT",
  "tags": ["report", "survey", "pdf", "archive"],
  "extractionState": "EXTRACTED",
  "extractionMethod": "TIKA",
  "ocrApplied": false,
  "textLength": 18422,
  "pageCount": 9,
  "author": null,
  "title": null,
  "durationMillis": null,
  "uploadedBy": "demo",
  "checksumSha256": "…",
  "snippet": null,
  "score": null
}
```

`extractionMethod` is never null — it is `NONE` when there is no extracted
text. `ocrApplied` is `true` once Tesseract has run over the file, whether or
not it found anything, and it is what hides the **Run OCR** button.

`snippet` and `score` are populated only on search responses and are `null`
everywhere else. `StoredFile.chunkCount()` derives the number of `fs.chunks`
documents as `ceil(length / chunkSize)`.

`SearchRequest`:

```json
{
  "query": "phosphorescent albatross",
  "categories": ["DOCUMENT"],
  "tags": ["archive"],
  "fuzzy": true,
  "page": 0,
  "size": 20
}
```

A blank or absent `query` means browse-everything. `categories` and `tags`
default to empty (no restriction); `size` outside `1..100` is coerced to `20`
and a negative `page` to `0`.

`SearchResponse`:

```json
{
  "results": [ /* StoredFile[] with snippet + score */ ],
  "total": 3,
  "mode": "ATLAS_SEARCH",
  "tookMillis": 14,
  "facets": { "DOCUMENT": 6, "MEDIA": 2, "IMAGE": 2 },
  "explain": "[ { \"$search\": { … } }, … ]"
}
```

`explain` is the pretty-printed aggregation pipeline that actually ran — that
is the field the UI's query panel renders, and it is the most persuasive thing
in the demo.

### Worked examples

```bash
# Upload with tags
curl -X POST http://localhost:8081/api/files \
  -F "file=@samples/out/lighthouse-survey.pdf" \
  -F "tags=report,survey,archive" \
  -F "uploadedBy=demo"

# List
curl -s "http://localhost:8081/api/files?page=0&size=5"

# Full-text search with fuzzy matching
curl -s -X POST http://localhost:8081/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"phosphorescent albatross","fuzzy":true,"page":0,"size":10}'

# Autocomplete
curl -s "http://localhost:8081/api/search/autocomplete?q=light&limit=8"

# Extracted text only
curl -s "http://localhost:8081/api/files/$ID/text"

# Delete (also removes every fs.chunks document for the file)
curl -i -X DELETE "http://localhost:8081/api/files/$ID"
```

---

## How Range / seek streaming works

`GET /api/files/{id}/content` implements HTTP byte ranges over GridFS.

**No `Range` header** → `200 OK`, with `Content-Length` and
`Accept-Ranges: bytes`. That header is what makes a browser `<video>` element
willing to seek at all.

**`Range: bytes=START-END`** → `206 Partial Content` with
`Content-Range: bytes START-END/TOTAL`.

**Open-ended `Range: bytes=START-`** → serves at most
`gridfs-demo.media-chunk-bytes` for `MEDIA` files, or the remainder of the file
for everything else. Capping the slice is what keeps a seek responsive: the
player gets a small, fast first response at the new position instead of the
server committing to stream the rest of a 10 MB file.

**Unsatisfiable range** → `416 Range Not Satisfiable` with
`Content-Range: bytes */TOTAL`.

**`?download=true`** → `Content-Disposition: attachment`; otherwise `inline`.

Why this is cheap in MongoDB: `fs.chunks` is keyed `{ files_id: 1, n: 1 }` and
chunks are fixed size, so a byte offset is pure arithmetic —
`n = floor(offset / chunkSize)`. Serving `bytes=5242880-7340031` from a
261120-byte-chunk file is an indexed read of chunks 20 through 28, trimming the
first and last. Nothing is read from the start of the file, and nothing is
buffered whole in heap. `server.tomcat.max-swallow-size: -1` and the compression
MIME allowlist in `application.yml` exist so Tomcat does not interfere with
partial responses.

Try it by hand:

```bash
curl -s -D- -o /dev/null -r 5242880-5243391 \
  "http://localhost:8081/api/files/$ID/content"
# HTTP/1.1 206 Partial Content
# Content-Range: bytes 5242880-5243391/10354727
```

---

## OCR

Tika reads text *layers*. A scanned page has none — it is a picture of text.
OCR closes that gap: Tesseract is invoked directly as a subprocess over the
file's bytes, and the recognised text is merged into `metadata.extractedText`
**in the same `fs.files` document**, where the Atlas Search index already looks.
No second store, no sidecar collection, no sync job.

`brew install tesseract`. On this machine that gives `tesseract 5.5.2` with the
`eng`, `osd` and `snum` language data. Without it the app runs unchanged and
reports `"ocrAvailable": false` / `"ocrEngine": null`.

### When it runs automatically

Controlled by `gridfs-demo.auto-ocr-on-upload` (default `true`):

| Category | Auto-OCR on upload |
|---|---|
| `IMAGE` | always |
| `DOCUMENT` (PDF) | only when Tika found **no** text layer — i.e. a scanned PDF |
| `MEDIA` | never |

### When it runs on demand

`POST /api/files/{id}/ocr`, or the **Run OCR** button in the detail panel. That
button appears only for a file that is an OCR candidate, has not been OCR'd
yet, and only when the engine is available. It is the path for everything
already sitting in the bucket from before OCR existed.

With `auto-ocr-on-upload=false`, an uploaded image lands as
`extractionState: SKIPPED`, `extractionMethod: NONE`, `ocrApplied: false`; the
on-demand call then moves it to `EXTRACTED` / `OCR` — 282 characters for the
`scanned-invoice.png` sample.

### Why a bad OCR run does not overwrite good text

OCR over something with no readable text — a photo, a fractal, a test pattern —
produces confident-looking garbage. The service scores the result and
**discards low-confidence output rather than writing it**. Such a run still
sets `ocrApplied: true` (so the button does not keep offering itself) but
leaves `extractedText` exactly as it was.

Verified: running OCR over `mandelbrot.jpg` yields `extractionState: SKIPPED`,
0 characters, `ocrApplied: true`.

That is a deliberate trade. Polluting a search index with recognition noise is
worse than having no text for that file: noise ranks against real documents
forever, and nobody goes back to clean it up.

---

## Configuration reference

Every `gridfs-demo.*` property, from `application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `gridfs-demo.max-extracted-text-bytes` | `1048576` (1 MB) | Ceiling on Tika output stored in `metadata.extractedText`. The text lives inside the `fs.files` document, which is bound by the 16 MB BSON limit; 1 MB clips well below it and leaves room for the rest of the document. Hitting the cap sets `extractionState = TRUNCATED`. |
| `gridfs-demo.search-index-name` | `gridfs_content` | Name of the Atlas Search index over `fs.files`. Must match whatever you created in Atlas / `mongosh`. |
| `gridfs-demo.search-mode` | `AUTO` | `AUTO` probes for `$search` and falls back to regex if absent. Force with `ATLAS_SEARCH` or `REGEX_FALLBACK`. Surfaced as `SearchResponse.mode` on every response. |
| `gridfs-demo.media-chunk-bytes` | `2097152` (2 MB) | Maximum bytes returned for an open-ended `Range: bytes=START-` on a `MEDIA` file. The `atlas` profile lowers this to 1 MB for faster post-seek first paint over a network. |
| `gridfs-demo.auto-ocr-on-upload` | `true` | Whether an upload that is an OCR candidate gets OCR'd immediately (IMAGE always; PDF only when Tika found no text layer; MEDIA never). Requires `tesseract`; ignored without it. **The trade-off, honestly:** `true` is the better product behaviour — a scan is searchable the moment it lands, and nobody has to know a button exists. But it is also why the on-demand button is invisible on a freshly seeded corpus: everything arrives already OCR'd. Set it to `false` when you want to *demonstrate* on-demand OCR. Existing files are unaffected either way — the button always works for anything not yet OCR'd. |

Related Spring properties that matter here:

| Property | Default | Meaning |
|---|---|---|
| `spring.data.mongodb.uri` | `${MONGODB_URI:mongodb://gridfs:gridfs@localhost:27018/gridfs_demo?authSource=admin&authMechanism=SCRAM-SHA-256&directConnection=true}` | Connection string. `MONGODB_URI` is the override you actually use. |
| `spring.data.mongodb.gridfs.bucket` | `fs` | Bucket name → collections `fs.files` and `fs.chunks`. |
| `spring.servlet.multipart.max-file-size` | `512MB` (`256MB` under `atlas`) | Per-file upload ceiling. |
| `spring.servlet.multipart.max-request-size` | `512MB` (`256MB` under `atlas`) | Whole-request ceiling. |
| `spring.servlet.multipart.file-size-threshold` | `2MB` | Above this, the upload spools to disk and streams into GridFS instead of buffering in heap. |
| `server.port` | `${PORT:8081}` | HTTP port. |
| `server.tomcat.max-swallow-size` | `-1` | Prevents Tomcat truncating large request bodies. |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | Actuator surface; `/actuator/health` is what the compose healthcheck and `upload-samples.sh` probe. |

Environment variables honoured by the ops layer: `MONGODB_URI`,
`SPRING_PROFILES_ACTIVE`, `PORT`, `BASE_URL` and `UPLOADED_BY`
(`upload-samples.sh`), `HEALTH_TIMEOUT` (`run.sh`), and — in the `atlas`
profile only — `GRIDFS_BUCKET`, `SEARCH_MODE`, `SEARCH_INDEX_NAME`,
`MAX_FILE_SIZE`, `MAX_REQUEST_SIZE`, `MAX_EXTRACTED_TEXT_BYTES`,
`MEDIA_CHUNK_BYTES`.

---

## Troubleshooting

### Search returns `"mode": "REGEX_FALLBACK"`

The app could not use `$search`. In order of likelihood:

1. **You are on a plain `mongo` container.** No mongot, no `$search`. Use
   `mongodb/mongodb-atlas-local` — that is the whole reason `run.sh` and
   `docker-compose.yml` specify it.
2. **The index does not exist yet.** `curl -X POST
   http://localhost:8081/api/admin/search-index`, then re-check
   `GET /api/admin/info`.
3. **`search-mode` is pinned.** Check you have not set `SEARCH_MODE` or
   `gridfs-demo.search-mode=REGEX_FALLBACK`.

Regex fallback is a correctness feature, not a bug — but you do not want it
live in front of a client, which is why the `atlas` profile pins
`ATLAS_SEARCH`.

### Search index exists but returns nothing

Index builds are asynchronous and mongot tails the oplog. A file uploaded two
seconds ago may not be queryable yet.

```bash
curl -s http://localhost:8081/api/admin/info          # indexReady
mongosh "mongodb://gridfs:gridfs@localhost:27018/gridfs_demo?authSource=admin&authMechanism=SCRAM-SHA-256&directConnection=true" \
  --eval 'db.fs.files.getSearchIndexes()'             # status / queryable
```

Wait for `status: READY` and `queryable: true`. If it sits at `PENDING` for
minutes on the local image, `docker logs gridfs-mongo` and look for mongot
errors. Also confirm the index name matches
`gridfs-demo.search-index-name` — a typo produces an empty result set with no
error.

Remember the mapping is `dynamic: false`: only the fields listed in
`gridfs_content.index.json` are searchable. Adding a new `metadata.*` field
means updating the index definition too.

### The container never goes healthy

```bash
docker inspect -f '{{.State.Health.Status}}' gridfs-mongo
docker logs --tail 100 gridfs-mongo
```

First start pulls a ~1 GB image and initialises a replica set plus mongot;
60–90 s is normal. `run.sh` waits up to 180 s — raise it with
`HEALTH_TIMEOUT=300 ./run.sh`.

### App cannot reach MongoDB from a container

Under Compose the app connects to `mongodb://gridfs:gridfs@mongodb:27017/gridfs_demo?authSource=admin&authMechanism=SCRAM-SHA-256&directConnection=true`.
The `directConnection=true` and the `hostname: mongodb` on the mongodb service
are both load-bearing: the single-node replica set advertises its own hostname
in `rs.conf()`, and without both of those the driver discovers a hostname it
cannot resolve across the container boundary.

### Compose and `run.sh` fight over the container

Both use the container name `gridfs-mongo`, deliberately, so they share one
database. But they configure it differently (Compose adds the network and the
named volumes), so `docker compose up` will *recreate* a container that
`run.sh` created, and vice versa. Pick one path per session. If Compose
complains that the name is in use:

```bash
docker rm -f gridfs-mongo && docker compose up --build
```

Data written via `run.sh`'s `gridfs-mongo-data` volume is not the same volume as
Compose's `mongodb-data`. Switching paths gives you an empty database — that is
expected, not a bug.

### `extractionState: FAILED` or `SKIPPED`

`SKIPPED` is normal and correct for images, audio and video — there is no text
layer. `FAILED` means Tika threw; the file is still stored, downloadable and
streamable, only unsearchable by content.

Common causes: a scanned/image-only PDF (no text layer — Tika itself does no
OCR; that is what [OCR](#ocr) is for, either automatically on upload or via the
**Run OCR** button), an encrypted or password-protected PDF, or a corrupt
archive-format document. Raise the log level to see the parser exception:

```yaml
logging:
  level:
    org.apache.tika: DEBUG
    org.apache.pdfbox: WARN
```

`application.yml` deliberately keeps PDFBox and FontBox at `ERROR`, because
they are extremely noisy about malformed fonts in otherwise fine PDFs.

`TRUNCATED` means the document was bigger than
`gridfs-demo.max-extracted-text-bytes` and the tail was clipped. The clip
protects the 16 MB BSON limit. Raise the cap if you must, but keep a wide
margin.

### The "Run OCR" button is not there

It is deliberately conditional. Three reasons, in order of likelihood:

1. **The file has already been OCR'd.** `ocrApplied: true` hides the button —
   including after a run that found nothing. If
   `gridfs-demo.auto-ocr-on-upload` is `true` (the default), every image was
   OCR'd on the way in and the button will never appear on a freshly seeded
   corpus. To demonstrate the on-demand path, restart with the flag off and
   re-seed. Note that `run.sh` rejects unrecognised arguments, so either edit
   `auto-ocr-on-upload: false` into `application.yml`, or launch the app
   directly:

   ```bash
   mvn spring-boot:run \
     -Dspring-boot.run.arguments=--gridfs-demo.auto-ocr-on-upload=false
   ```
2. **No engine.** `curl -s http://localhost:8081/api/admin/info` — if
   `ocrAvailable` is `false`, install it with `brew install tesseract` and
   restart the app. Calling `POST /api/files/{id}/ocr` directly in this state
   returns `503`.
3. **The file is not an OCR candidate.** Video, audio and text-layer documents
   are not. `POST /api/files/{id}/ocr` on one returns `409` with
   `Not an OCR candidate: …`.

### OCR ran but no text appeared

Two possibilities, and the first is usually the true one.

**There is genuinely no readable text.** A fractal, a colour-bar test pattern
or a photograph of a landscape has nothing to recognise. `mandelbrot.jpg` is
the reference case: `extractionState` stays `SKIPPED`, `textLength` is 0, and
`ocrApplied` is `true`.

**The confidence heuristic discarded the output.** OCR over a low-contrast,
skewed or very low-resolution image returns plausible-looking noise. The
service scores each run and refuses to write low-confidence text over what is
already there. That is intentional — see [OCR](#ocr). The file is marked as
OCR'd either way, so the button will not offer itself again.

### Upload rejected — `413` / `MaxUploadSizeExceededException`

Limits are `spring.servlet.multipart.max-file-size` and `max-request-size`
(512 MB by default, 256 MB under the `atlas` profile):

```bash
MAX_FILE_SIZE=1GB MAX_REQUEST_SIZE=1GB SPRING_PROFILES_ACTIVE=atlas ./run.sh
```

or, on the default profile, override on the command line:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments=--spring.servlet.multipart.max-file-size=1GB
```

GridFS itself has no file-size ceiling worth worrying about. If you are
uploading through a reverse proxy, that proxy has its own body limit
(`client_max_body_size` in nginx) and it will reject the request before Spring
ever sees it.

### Port 8081 or 27018 already in use

```bash
lsof -nP -iTCP:8081 -sTCP:LISTEN
PORT=9090 ./run.sh
```

`run.sh` detects a busy 27018 and assumes it is your own MongoDB rather than
clobbering it. Under Compose, edit the `ports:` mappings.

### Why 27018 and not 27017?

Because a locally installed `mongod` (Homebrew `mongodb-community`) usually owns
27017, and this failure is genuinely nasty: Docker publishes the container on the
same port, host connections resolve to whichever bound first, and the app then
talks to *your* MongoDB instead of the demo's. It does not present as a
connection error — it presents as `Unauthorized`, or as an empty file list, or
as `REGEX_FALLBACK` because your local server has no search node. Keeping the
demo on 27018 removes the ambiguity entirely.

### Search shows REGEX_FALLBACK instead of ATLAS_SEARCH

Three causes, in order of likelihood:

1. **MongoDB was not up when the app started.** The probe runs at startup, and a
   deployment that is unreachable looks identical to one with no search node.
   The app re-probes every 5s for 3 minutes and promotes itself automatically —
   check `/api/admin/info` again before doing anything else, or force it with
   `curl -XPOST http://localhost:8081/api/admin/search-index`.
2. **You are pointed at a plain community server** with no `mongot`. Only Atlas,
   the `mongodb-atlas-local` image, or Community 8.2+ with Search can serve
   `$search`. Fallback is working as designed here.
3. **The index exists but is still building.** `indexReady` will be `false`
   while `searchMode` is already `ATLAS_SEARCH`. Wait a few seconds.

Do not present the demo while this reads `REGEX_FALLBACK` — the "show the real
pipeline" beat will display a `$regex` query rather than `$search`.

### Video will not seek in the browser

Check the response actually carries `Accept-Ranges: bytes`:

```bash
curl -sI "http://localhost:8081/api/files/$ID/content" | grep -i accept-ranges
```

If it is missing, something in front of the app (a proxy, or compression
applied to a binary type) is stripping it. The `mime-types` allowlist under
`server.compression` in `application.yml` exists precisely to keep media out of
the compressor.

---

## Repository layout

```
├── Dockerfile                  multi-stage build (Maven+JDK21 → JRE21, non-root)
├── docker-compose.yml          atlas-local + app, healthcheck-gated startup
├── run.sh                      one-command local launcher (idempotent)
├── samples/
│   ├── generate-samples.sh     builds the demo corpus, degrades gracefully
│   ├── upload-samples.sh       POSTs samples/out/ into /api/files with tags
│   └── out/                    generated, git-ignored
├── src/main/resources/
│   ├── application.yml         defaults (local)
│   ├── application-atlas.yml   overrides for MongoDB Atlas
│   └── atlas/gridfs_content.index.json   the Atlas Search index definition
├── API-CONTRACT.md             the frozen API contract
├── DESIGN-TOKENS.md            LeafyGreen design tokens used by the UI
└── DEMO-SCRIPT.md              presenter's run-sheet for the client meeting
```
