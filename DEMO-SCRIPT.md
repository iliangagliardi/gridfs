# Demo run-sheet — MongoDB GridFS + Atlas Search

**Audience:** client technical + decision-maker mix
**Length:** 15–18 minutes of demo, plus Q&A (12–15 if you drop beats 6 and 7)
**One-line thesis:** *Your files, their extracted content, and the search index
over that content all live in MongoDB — one database, one backup, one security
model — and it behaves identically on a laptop and on Atlas.*

---

## Before they join (T-15 minutes)

Do all of this and leave it done. Nothing here should happen on camera.

> **Read this before you start the app — it is the easiest thing on this page
> to get caught by.** If you want to show the **Run OCR** button (beat 6), you
> must run with `gridfs-demo.auto-ocr-on-upload=false`.
>
> With the default (`true`), `scanned-invoice.png` is OCR'd during
> `upload-samples.sh` and arrives already searchable — so the button never
> renders, and the "search finds nothing" opening of that beat fails because it
> finds something. There is no way to un-OCR a file: you would have to delete
> it, restart with the flag, and re-upload. **Decide now, not at T-2 minutes.**
>
> `run.sh` rejects arguments it does not recognise, so you cannot pass the flag
> through it. Two ways that do work — pick one:
>
> ```bash
> # A. one-line edit, then run normally
> #    src/main/resources/application.yml → auto-ocr-on-upload: false
> ./run.sh
> ```
>
> ```bash
> # B. let run.sh bring the container up, Ctrl-C the app, relaunch it yourself
> ./run.sh
> # ^C once you see "Started GridFsDemoApplication" — the container stays up
> export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
> mvn spring-boot:run \
>   -Dspring-boot.run.arguments=--gridfs-demo.auto-ocr-on-upload=false
> ```
>
> Seed the corpus **after** the app is running with the flag, not before.
>
> If you would rather not bother, skip beat 6 or cut it down to "it was OCR'd on
> the way in — here is the text, and here is `extractionMethod: OCR`."

```bash
./run.sh                                  # wait for "Started GridFsDemoApplication"
curl -s http://localhost:8081/api/admin/info
```

Confirm the response shows `"searchMode":"ATLAS_SEARCH"` and
`"indexReady":true`. If not, create the index and wait for it:

```bash
curl -X POST http://localhost:8081/api/admin/search-index
```

Export the connection string once, so every `mongosh` moment below is a
copy-paste. **Note the port: 27018, not 27017.** The demo's MongoDB is
deliberately not on the default port, because a locally installed `mongod`
usually owns 27017 and you would silently be querying the wrong server in front
of the client:

```bash
export DEMO_URI="mongodb://gridfs:gridfs@localhost:27018/gridfs_demo?authSource=admin&directConnection=true"
mongosh "$DEMO_URI" --eval 'db.getName()'    # sanity check: prints gridfs_demo
```

Then seed the corpus:

```bash
./samples/generate-samples.sh
./samples/upload-samples.sh
```

Final checks:

- [ ] `/api/admin/info` → `ATLAS_SEARCH`, `indexReady: true`. **If it says
      `REGEX_FALLBACK`, stop and fix it.** The whole story collapses if the
      search panel shows a `$regex` pipeline.
- [ ] `/api/admin/info` → `"ocrAvailable": true` and an `ocrEngine` version
      string (`tesseract 5.5.2` here). If it is `false`,
      `brew install tesseract` and restart — or drop beat 6. The app is
      perfectly happy without it; only that beat depends on it.
- [ ] Search `TRILOBITE SEMAPHORE` once, yourself, and confirm you get **zero**
      results. That is the setup for beat 6. If you get a hit, auto-OCR was on
      when you seeded.
- [ ] Browser at <http://localhost:8081>, zoomed to ~125 %, one window, no tabs
      with your email in them. The UI defaults to light theme; if the room's
      projector is washing it out, the dark toggle is in the header — flip it
      before they join, not on camera.
- [ ] A **second** terminal, large font, ready for the `mongosh` moments.
- [ ] Keep `samples/out/lighthouse-survey.pdf` **out** of the seeded corpus if
      you want to upload it live — delete it from the UI after seeding, or run
      `upload-samples.sh` first and just re-upload it live as a second copy.
      Uploading something live is worth the fifteen seconds.
- [ ] Volume on, but muted until the audio moment.

---

## The narrative

### 0. Frame it (60 s, no clicks)

> "Most systems that do this have three pieces: an object store for the bytes,
> a database for the metadata, and a search cluster for the content. Three
> backups, three failure modes, three sets of credentials, and a permanent job
> keeping them consistent with each other. I'm going to show you the same
> capability with one."

Show the empty-ish UI. Don't explain the architecture yet — show it working
first, explain second.

---

### 1. Upload a PDF (90 s)

**Click:** drag `samples/out/lighthouse-survey.pdf` onto the upload area (or
Choose File → Upload). Add tags `report, survey, archive`.

**While it uploads, say:**

> "That's a multipart POST to `/api/files`. Behind it, the driver is splitting
> the file into one-megabyte chunks and writing them into a collection called
> `fs.chunks`, and writing one metadata document into `fs.files`. That's all
> GridFS is — a convention for storing a large object across ordinary MongoDB
> documents. It is not a bolt-on and it is not a separate engine."

> **Accuracy note for the presenter:** GridFS's *default* chunk size is 255 KB.
> This demo deliberately configures 1 MB (visible as "chunk size 1.0 MB" in the
> viewer) because larger chunks mean fewer round trips when streaming media. If
> someone asks, that is the honest answer — it is a tuning knob, not the
> default. Don't say 255 KB while the UI is showing 1.0 MB.

**Point at:** the new row in the list — filename, size, category `DOCUMENT`,
and the chunk count.

**The point to make:** nothing left MongoDB. There is no bucket, no volume, no
path on a disk somewhere.

> **Timing warning:** Atlas Search indexes asynchronously — mongot tails the
> oplog, so a file you upload live is searchable roughly a second later, not
> instantly. Measured at ~1s on this corpus. If you upload and immediately
> search, you may get zero hits and it will look broken. Talk through the
> extraction panel for a beat first; by the time you reach the search box it is
> indexed. This lag is inherent to the architecture, not a bug — and it is worth
> saying out loud if a technical audience notices it.

---

### 2. Show the extracted text landed in metadata (2 min)

This is the step that earns the rest of the demo. Don't rush it.

**Click:** the file row → expand it → the extracted-text view (or hit
`/api/files/{id}/text` in a browser tab).

**Say:**

> "On the way in, Apache Tika parsed the PDF and pulled the text out of every
> page. That text did not go to a search cluster. It went *into the same
> document* that describes the file."

**Then go to the terminal** — this is the moment that convinces engineers:

```bash
mongosh "$DEMO_URI" --eval '
  db.fs.files.findOne(
    { filename: "lighthouse-survey.pdf" },
    { filename:1, length:1, chunkSize:1,
      "metadata.contentType":1, "metadata.category":1, "metadata.tags":1,
      "metadata.extractionState":1, "metadata.extractionMethod":1,
      "metadata.ocrApplied":1, "metadata.textLength":1,
      "metadata.pageCount":1, "metadata.checksumSha256":1 })'
```

**Point at, in order:**

- `length` and `chunkSize` — "the bytes are over in `fs.chunks`, keyed by this
  document's `_id`."
- `metadata.extractionState: "EXTRACTED"` — "and if the parser had failed, this
  would say `FAILED`, the file would still be stored and downloadable, and the
  demo would carry on. It degrades, it doesn't break."
- `metadata.extractionMethod: "TIKA"` — "and this records *how* we got the text.
  Hold that thought; it will say something different in a few minutes." (Only
  worth saying if you are running beat 6.)
- `metadata.textLength` — "eighteen thousand characters of PDF, sitting in the
  metadata document."

Then show how big the text actually is, and pre-empt the obvious question:

```bash
mongosh "$DEMO_URI" --eval '
  db.fs.chunks.countDocuments({ files_id:
    db.fs.files.findOne({filename:"lighthouse-survey.pdf"})._id })'
```

> "One document per file, N chunks of payload. And yes — that metadata document
> is subject to MongoDB's 16 megabyte limit, which is exactly why the extracted
> text is capped at one megabyte by configuration. That cap is deliberate, not
> an accident."

---

### 3. Search a phrase that only exists in the middle of the PDF (2 min)

**Type into the search box:** `phosphorescent albatross`

**Say, before you hit enter:**

> "This phrase appears exactly once, on an interior page of that PDF, and
> nowhere else in the corpus. It is not in the filename, not in the tags, not
> in any of the other files."

**Hit enter.** One result.

**Point at:**

- **The highlighted snippet** — "that's the actual sentence, from the middle of
  the document, with the match highlighted. We read the whole file, not the
  first page."
- **`tookMillis`** — a single-digit or low-double-digit number.
- **The relevance score** — "this is ranked, not filtered. That's a Lucene
  score."
- **The facet counts** by category.

**Now show it isn't a party trick.** Search `roasting development time` — the
coffee note comes back and the sailing note does not. Search `sharding` — the
MongoDB note. Then toggle **fuzzy** on and search `albatros` (one 's') to show
typo tolerance.

**Then autocomplete:** type `light` slowly in the search box and let the
suggestions appear. "Edge-gram autocomplete, same index, no extra
infrastructure."

---

### 4. Open the query panel — show the real pipeline (2 min)

**Click:** the query / explain panel.

This is your strongest slide and it isn't a slide.

**Say:**

> "This is not a diagram of what we do. This is the aggregation pipeline that
> just ran, returned by the API on every single search response."

**Point at, line by line:**

- **`$search`** as the first stage — "a native aggregation stage. Not a call out
  to another system. There is no network hop to Elasticsearch here, because
  there is no Elasticsearch."
- The **compound query** — `text` over `metadata.extractedText` and `filename`,
  with the tag and category filters as `filter` clauses.
- **`highlight`** — where the snippet comes from.
- The `$facet` / `$project` stages after it — "and because it's just an
  aggregation, everything downstream of `$search` is ordinary MongoDB. Join it,
  group it, `$lookup` it into your own collections. Try that with a search
  cluster sitting off to one side."
- **`mode: ATLAS_SEARCH`** in the response — "and this is running on a container
  on my laptop. Same stage, same index definition, same code as Atlas."

---

### 5. Play the video and seek into the middle (3 min)

This is the section people remember.

**Click:** `seek-test-clip.mp4` → it plays inline.

**Say:**

> "Ninety seconds of video, about ten megabytes, stored the same way as the PDF
> — split across roughly forty chunks in `fs.chunks`."

**Now drag the scrubber to the middle, around 0:45.**

**Point at the number burned into the frame.**

> "That counter is rendered into the video itself. It says forty-five. The
> player asked for a byte range starting in the middle of the file, and it got
> back exactly the frames that live at that offset. Nothing streamed from the
> beginning."

**Point at the chunk readout in the UI** — the byte range served and which
chunks it came from.

**Then the terminal, to prove it at the protocol level:**

```bash
ID=$(curl -s "http://localhost:8081/api/files?size=50" \
     | tr ',' '\n' | grep -B2 seek-test-clip | grep -o '[0-9a-f]\{24\}' | head -1)

curl -s -D- -o /dev/null -r 5242880-5243391 \
  "http://localhost:8081/api/files/$ID/content"
```

**Point at:** `HTTP/1.1 206 Partial Content` and
`Content-Range: bytes 5242880-5243391/…`.

**The point to make:**

> "`fs.chunks` has a unique index on files_id plus chunk number, and chunks are
> fixed size. So a byte offset is arithmetic — divide by the chunk size and you
> have the chunk number. Serving that range was an indexed read of nine chunks
> and trimming the two ends. It costs the same in the middle of a two-hour
> video as it does at the start."

**Optional, if the room is engaged:** play `tone-sweep.mp3`, seek past 1:20, and
let them *hear* the pitch jump. It takes eight seconds and it lands.

---

### 6. Make a scanned image searchable — OCR (2–3 min)

*Requires having started with `--gridfs-demo.auto-ocr-on-upload=false`. See the
pre-flight note.*

This beat has a shape: **it fails, then it works.** Do not skip the failure —
it is what makes the second search mean anything.

**Type into the search box:** `TRILOBITE SEMAPHORE`

**Hit enter. Zero results.**

**Say:**

> "That phrase does exist in this corpus. It is on a scanned invoice. But the
> scan is a *picture* of a page — there is no text layer in it, so Tika read
> nothing on the way in and there is nothing to match."

**Click:** `scanned-invoice.png` to open the detail panel.

**Point at, in order:**

- `extractionState: SKIPPED` — "the parser was honest about finding nothing."
- `textLength: 0` — "zero characters. And yet you can see the words right there
  in the image."

**Now press Run OCR.** It takes a couple of seconds — say this while it runs:

> "That is Tesseract, running over the pixels. It is seconds of CPU, not
> milliseconds — which is exactly why it is a button and not something that
> happens on every read."

**Point at the panel updating:** the text appears, `extractionState` becomes
`EXTRACTED`, `extractionMethod` becomes `OCR`, and `textLength` is ~282.

**Search `TRILOBITE SEMAPHORE` again.** One result, with the phrase
highlighted in the snippet.

**The talking point — this is the whole beat:**

> "The searchable text you just matched was produced from pixels, and stored
> straight back into the same GridFS metadata document as the image it came
> from. No second system was involved. There is no OCR service with its own
> database, no queue writing results somewhere else, nothing to reconcile. One
> document still describes the file, carries its searchable content, and points
> at its bytes — the difference is that some of that content came from a
> recogniser instead of a parser."

> **Accuracy note for the presenter:** OCR that finds nothing does **not**
> overwrite existing text with noise — the service scores the result and
> discards low-confidence output rather than polluting the index. Run OCR on
> `mandelbrot.jpg` if someone wants to see it: `ocrApplied` becomes `true`,
> 0 characters written, state stays `SKIPPED`. That is a deliberate choice, and
> it is worth saying out loud, because "we OCR everything" usually means "our
> search index is full of garbage."

---

### 7. Edit the metadata, show the bytes never move (60 s)

Short beat, one clean point. Keep it moving.

**Click:** the **Edit** control in the detail panel of any file. Change the
**title** and add a **tag**. Save.

**Point at:** the row updating, and the new tag appearing as a working facet /
filter in search.

**Then the terminal:**

```bash
mongosh "$DEMO_URI" --eval '
  db.fs.files.findOne(
    { filename: "lighthouse-survey.pdf" },
    { filename:1, length:1, chunkSize:1,
      "metadata.title":1, "metadata.tags":1, "metadata.checksumSha256":1 })'
```

**Say:**

> "That was a `PATCH` against the metadata sub-document, and *only* the metadata
> sub-document. `length` is the same, the SHA-256 checksum is the same, and not
> one document in `fs.chunks` was touched. Renaming a file or retagging it costs
> a single small document update — the payload does not get rewritten, re-copied
> or re-uploaded to change a title."

**If a technical person is paying attention, add:**

> "Tags are normalised on write — trimmed, lowercased, de-duplicated. That is
> not tidiness. The search index maps tags as a `token` type with no lowercase
> normalizer, so `MongoDB` and `mongodb` would be two different tokens and tag
> filtering would silently miss half your files. Normalise at the boundary."

---

### 8. Delete a file, show the chunks go with it (90 s)

**Click:** delete on one of the sample files. Confirm.

**Before you click, run this and leave the number on screen:**

```bash
mongosh "$DEMO_URI" --eval '
  print("files:  " + db.fs.files.countDocuments({}));
  print("chunks: " + db.fs.chunks.countDocuments({}));'
```

**Delete, then run it again.**

**Point at:** both counts dropping — files by one, chunks by that file's chunk
count.

**Say:**

> "One `DELETE /api/files/{id}`, and the metadata document, every payload chunk,
> and the search index entry all go together. There is no orphaned blob in a
> bucket somewhere waiting for a reconciliation job to notice it. That class of
> bug does not exist here."

Optionally show `/api/stats` before and after for total bytes reclaimed.

---

### 9. Close (60 s)

> "Everything you just saw ran against a container on this laptop. The only
> thing that changes to run it against Atlas is the connection string —
> `MONGODB_URI` and a profile. Same index definition, same `$search` stage, same
> application code, zero lines different. That's your dev environment, your CI,
> your air-gapped on-prem deployment, and your managed cloud, all on one code
> path."

If you have connectivity and it is set up: flip to Atlas live. It is a
five-second change and it is the strongest possible ending.

```bash
export MONGODB_URI='mongodb+srv://…/gridfs_demo'
SPRING_PROFILES_ACTIVE=atlas ./run.sh
```

---

## "What if they ask…"

### "Why not just use S3?"

Fair question, and sometimes S3 is right. S3 wins on cost per terabyte at large
scale and on CDN integration. What it does not give you:

- **Transactional consistency between the file and its metadata.** With S3 you
  write the object, then write the row, and you own every failure between those
  two operations forever. Here it is one system.
- **Query-ability.** You cannot ask S3 "which files mention this phrase, tagged
  archive, uploaded this quarter, ranked by relevance." You need a second and
  third system for that. You just watched that be one aggregation pipeline.
- **One security and backup model.** One set of credentials, one audit trail,
  one point-in-time restore that is *internally consistent* — restore an S3
  bucket and a database to the same instant and see how you get on.

The honest framing: GridFS is the right answer when files are *part of your
data model* and you need to query across them. S3 is the right answer when
they are opaque bulk storage served straight to a CDN.

### "What about the 16 MB document limit?"

That limit is precisely why GridFS exists. A BSON document caps at 16 MB, so
anything larger cannot go in a field — GridFS splits it across many documents
in `fs.chunks` and there is no practical size ceiling.

Where the limit still applies here is the **extracted text**, because that
lives inside the `fs.files` document. Hence
`gridfs-demo.max-extracted-text-bytes: 1048576` — one megabyte, a wide margin
under 16. Exceed it and the state is recorded as `TRUNCATED`. If a client
genuinely needs the full text of enormous documents indexed, the text moves to
its own collection with its own search index — a small change, and the
architecture is unchanged.

*Rule of thumb to offer them:* under 16 MB and you want it inline, use a
BinData field. Over 16 MB, or you need range/seek access, use GridFS.

### "Why 255 KB chunks? What are the trade-offs?"

255 KB is the driver default and it is a deliberate compromise.

- **Smaller chunks:** finer-grained range reads, less over-read for a small
  slice, but more documents, more index entries, and more round trips for a
  full-file read.
- **Larger chunks:** fewer documents and faster sequential throughput, but every
  range read over-fetches more, and you burn more memory per chunk in the WiredTiger
  cache.

255 KB is chosen so a chunk document — payload plus BSON overhead — sits
comfortably inside a 256 KB boundary. Tune it upward for archival workloads
that are always read whole; leave it alone for media you seek into. It is a
per-bucket setting, so you can have both.

### "Atlas Search vs a `$text` index?"

`$text` is a legacy MongoDB feature and it is genuinely limited: one text index
per collection, no relevance tuning, no highlighting, no fuzzy matching, no
autocomplete, no faceting, and no control over analysis.

Atlas Search is Lucene. You get analyzers per field, edge-gram autocomplete,
fuzzy matching, highlighting, faceting, custom scoring, and synonyms — all as
an aggregation stage that composes with the rest of the pipeline. You watched
the app use highlighting, facets, autocomplete and fuzzy. `$text` does none of
those.

The one thing to be honest about: mongot is a separate process tailing the
oplog, so indexing is **eventually** consistent. A document is queryable within
a second or so, not the same instant it is written.

### "Can you shard GridFS?"

Yes, and it shards well.

- Shard `fs.chunks` on `{ files_id: 1, n: 1 }` — reads are always keyed by
  `files_id`, so every chunk lookup is a *targeted* query to one shard, not a
  scatter-gather.
- Or shard on `{ files_id: "hashed" }` when you would rather keep whole files
  on a single shard and distribute files evenly.
- Shard `fs.files` on `_id`.

The property that matters: because range reads are already expressed as chunk
lookups by `files_id` and `n`, sharding does not change the access pattern at
all. The seek demo you just saw behaves identically on a sharded cluster.

### "Does this really work the same on-prem?"

You just watched it. Everything in the demo ran against
`mongodb/mongodb-atlas-local` in Docker on this laptop — that image bundles
`mongod` and `mongot`, the same search process Atlas runs. Same `$search`
stage, same index definition JSON, same `createSearchIndex` call.

MongoDB Community 8.2 and later can also run Search on-prem directly. And if a
deployment genuinely has no search node, the app detects it and falls back to a
regex query rather than failing — the `mode` field on every search response
tells you honestly which engine served it. Show them that field.

### "How big can a file be?"

GridFS has no meaningful ceiling. The practical limits are your upload
timeout and `spring.servlet.multipart.max-file-size` — 512 MB in this demo's
default config, and configurable. Large uploads spool to disk past 2 MB rather
than buffering in heap, so a multi-gigabyte upload does not blow up the JVM.

### "What happens to a scanned PDF with no text layer?"

Tika extracts nothing on its own — `extractionState` is `SKIPPED` and the file
is still stored, downloadable and streamable, just not findable by content.
That is where OCR comes in, and you just showed it: Tesseract runs over the
pixels and the recognised text lands in the same metadata document. Images are
OCR'd automatically on upload; a PDF is OCR'd automatically only when Tika
found no text layer — i.e. exactly the scanned case. Video and audio never are.

If tesseract is not installed the app says so (`ocrAvailable: false`) and
everything else carries on. Do not oversell it: it is a genuinely optional
component.

### "Why put OCR output in the file's metadata rather than a separate store?"

Because it is the same argument as the rest of the demo, applied one level
deeper.

The alternative is an OCR service with its own database. Now you have a
document in `fs.files`, its bytes in `fs.chunks`, and its *text* in a third
place — and you own the consistency between them forever. Delete the file and
something has to remember to delete the text. Restore from backup and the two
have to be restored to the same instant. Re-run OCR with a better model and you
need a job to reconcile.

Here the text is a field on the document that already describes the file. The
delete you watched in beat 8 took the text with it because there was nothing
separate to take. And the search index is already over `fs.files`, so OCR
output is searchable by the same `$search` stage, with the same highlighting and
the same facets, with zero additional wiring.

The honest boundary: this holds while the text fits comfortably inside the
16 MB BSON document — hence the 1 MB cap. Past that, the text moves to its own
collection with its own search index, and you take on the consistency problem
deliberately rather than by accident.

### "What does OCR cost at scale? Is it fast enough to do on every upload?"

Be straight about this one, because the honest answer is more credible than the
optimistic one.

OCR is **seconds per page and CPU-bound** — you saw it take a couple of seconds
for a single page on this laptop. Tika parsing a DOCX is sub-millisecond by
comparison; these are not the same order of operation and it is not close.

In this demo OCR runs inline on the upload request, which is fine for a demo
and fine for a low-volume internal tool. **In production you would not block an
HTTP request on it.** You would accept the upload, store the bytes, return
`201` immediately, and queue the OCR — a worker pool, a change stream on
`fs.files`, whatever fits their stack — then update the metadata document when
it finishes. The file is stored and downloadable the whole time; only
searchability is deferred by a few seconds. That is the same eventual-consistency
shape as the Atlas Search indexing lag you already flagged, so it is not a new
concept to sell them.

The architecture does not change either way. What changes is who waits.

### "Which languages does it handle?"

Tesseract ships language packs; **only `eng` is installed here** (plus `osd`,
orientation and script detection, and `snum`). Adding others is
`brew install tesseract-lang` or dropping `.traineddata` files in, and then
telling the engine which language to use.

Do not claim multilingual support you have not demonstrated. If they need
French, German or CJK, the right answer is "Tesseract has packs for those, we
would need to configure and test them for your document mix" — accuracy varies
a lot by language and by scan quality, and over-promising here is how you get a
bad second meeting.

---

## If something goes wrong on stage

| Symptom | Say this | Do this |
|---|---|---|
| Search shows `REGEX_FALLBACK` | "The search node is still catching up — this is the fallback path I mentioned, and note that the app tells you which engine it used." | Carry on with the demo; fix at the break. It is a feature, present it as one. |
| A search returns nothing | "Indexing is eventually consistent — mongot tails the oplog." | Wait two seconds, search again. Have a pre-seeded query ready. |
| Upload hangs | — | Cut to a file already in the corpus. Never debug live. |
| Video will not seek | — | Fall back to the `curl -r` terminal command; the `206` and `Content-Range` headers make the point just as well. |
| No **Run OCR** button on the scan | — | Auto-OCR was on when you seeded, so it is already done. Pivot: show the extracted text and `extractionMethod: OCR` and say it happened on ingest. Do not try to fix it live. |
| `TRILOBITE SEMAPHORE` returns a hit *before* you run OCR | — | Same cause. Skip straight to the "it's in the same document" point; the failure-then-success shape is lost but the architecture point is not. |
| OCR runs and returns no text | "It scored that as low-confidence and refused to write noise into the search index — which is the behaviour you want." | Move on. This is a designed outcome, present it as one. |

**General rule:** never open an IDE, never read a stack trace out loud, and
never say "that's weird, it worked earlier." Move to the next beat.
