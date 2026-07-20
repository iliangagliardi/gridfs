package com.mongodb.demo.gridfs.storage;

import com.mongodb.MongoGridFSException;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSUploadStream;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.demo.gridfs.config.DemoProperties;
import com.mongodb.demo.gridfs.domain.ExtractionMethod;
import com.mongodb.demo.gridfs.domain.ExtractionState;
import com.mongodb.demo.gridfs.domain.FileCategory;
import com.mongodb.demo.gridfs.domain.StoredFile;
import com.mongodb.demo.gridfs.ingest.ContentExtractor;
import com.mongodb.demo.gridfs.ingest.ExtractionResult;
import com.mongodb.demo.gridfs.ingest.NotOcrableException;
import com.mongodb.demo.gridfs.ingest.OcrService;
import com.mongodb.demo.gridfs.ingest.OcrUnavailableException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GridFS-backed implementation of {@link FileStorageService}.
 *
 * <p>Three different views of the same data are used deliberately, because each
 * is the right tool for one job:
 * <ul>
 *   <li>the raw {@link GridFSBucket} for writes, deletes and — crucially —
 *       seekable reads, which is the one thing the higher-level abstractions
 *       cannot express;</li>
 *   <li>{@link MongoTemplate} against {@code fs.files} for listing, counting and
 *       stats, so we can apply a projection and an aggregation that the GridFS
 *       APIs do not expose;</li>
 *   <li>{@link ContentExtractor} on the way in, so the searchable text lands in
 *       the same document as the file metadata and no second collection is
 *       needed;</li>
 *   <li>{@link OcrService} for the files Tika cannot help with at all — scans and
 *       photographs, where the text exists only as pixels. It is kept a separate
 *       dependency rather than folded into the extractor because its cost profile
 *       is different by orders of magnitude, and that difference drives when we
 *       are willing to run it.</li>
 * </ul>
 */
@Service
public class GridFsFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(GridFsFileStorageService.class);

    /**
     * Chunk size for every file we write, overriding the GridFS default of 255 KB.
     *
     * <p>The trade-off: a chunk is the atomic unit of a GridFS read. Bigger chunks
     * mean fewer documents, fewer round-trips and better throughput on a long
     * sequential read of a large video — a 500 MB file is 500 chunks at 1 MB
     * versus roughly 2,000 at the default. The cost is read amplification on a
     * seek: jumping to byte 700,001 still pulls the whole 1 MB chunk that
     * contains it, so a scattered-access workload wastes more bandwidth per hit.
     *
     * <p>For a media-streaming demo the access pattern is overwhelmingly
     * sequential-after-seek, so the larger chunk wins. Note this stays well under
     * the 16 MB BSON document limit that caps chunk size in the first place.
     */
    private static final int CHUNK_SIZE_BYTES = 1024 * 1024;

    /**
     * Fields excluded from every listing query. {@code metadata.extractedText}
     * can be a full megabyte per document (see
     * {@code gridfs-demo.max-extracted-text-bytes}); shipping it on a 20-row page
     * would mean 20 MB over the wire to render a table that never displays it.
     * Only {@code GET /api/files/{id}/text} reads that field.
     */
    private static final String EXTRACTED_TEXT_FIELD = "metadata.extractedText";

    private static final String DEFAULT_FILENAME = "unnamed";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final String DEFAULT_UPLOADED_BY = "demo";

    private final GridFSBucket bucket;
    private final MongoTemplate mongoTemplate;
    private final ContentExtractor contentExtractor;
    private final OcrService ocrService;
    private final DemoProperties demoProperties;
    private final String filesCollection;
    private final String chunksCollection;

    public GridFsFileStorageService(GridFSBucket bucket,
                                    MongoTemplate mongoTemplate,
                                    ContentExtractor contentExtractor,
                                    OcrService ocrService,
                                    DemoProperties demoProperties,
                                    @Value("${spring.data.mongodb.gridfs.bucket:fs}") String bucketName) {
        this.bucket = bucket;
        this.mongoTemplate = mongoTemplate;
        this.contentExtractor = contentExtractor;
        this.ocrService = ocrService;
        this.demoProperties = demoProperties;
        this.filesCollection = bucketName + ".files";
        this.chunksCollection = bucketName + ".chunks";
    }

    // ------------------------------------------------------------------
    // Indexes
    // ------------------------------------------------------------------

    /**
     * GridFS creates its own {@code {filename: 1, uploadDate: 1}} index on
     * {@code fs.files}, which does not help us: we sort by {@code uploadDate}
     * alone, descending, and that compound index cannot serve it (the leading
     * field is wrong). Without a dedicated index every page of the listing is a
     * collection scan plus an in-memory sort, which fails outright past 32 MB.
     *
     * <p>The category and tags indexes back the facet filters and the regex
     * fallback search path used when Atlas Search is unavailable.
     *
     * <p>Runs after startup rather than in a constructor so a Mongo that is slow
     * to accept connections cannot deadlock context refresh, and index creation
     * is idempotent — {@code createIndex} on an identical spec is a no-op.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try {
            var indexOps = mongoTemplate.indexOps(filesCollection);
            indexOps.createIndex(new Index().on("uploadDate", Sort.Direction.DESC).named("uploadDate_desc"));
            indexOps.createIndex(new Index().on("metadata.category", Sort.Direction.ASC).named("metadata_category"));
            // Multikey index: MongoDB indexes each element of the tags array.
            indexOps.createIndex(new Index().on("metadata.tags", Sort.Direction.ASC).named("metadata_tags"));
            log.info("GridFS indexes ensured on {}", filesCollection);
        } catch (RuntimeException e) {
            // A demo that cannot build an index should still start and serve.
            log.warn("Could not ensure indexes on {}: {}", filesCollection, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Store
    // ------------------------------------------------------------------

    /**
     * Streams the upload into GridFS in two passes over the multipart body.
     *
     * <p>We need the bytes twice — once for Tika, once for GridFS — and a file
     * may be 500 MB, so buffering into a {@code byte[]} is not an option. Spring's
     * {@code MultipartFile.getInputStream()} is safely repeatable here: with the
     * default {@code StandardServletMultipartResolver} it delegates to
     * {@code Part.getInputStream()}, and Tomcat's {@code DiskFileItem} returns a
     * fresh stream every call — {@code Files.newInputStream} over the spooled
     * temp file when the part exceeded {@code spring.servlet.multipart.file-size-threshold}
     * (2 MB here), or a new {@code ByteArrayInputStream} over the cached bytes
     * when it did not. Neither path re-reads the socket, so no spooling of our
     * own is required.
     *
     * <p>Extraction runs first so that the {@code fs.files} document is written
     * exactly once, complete, at the end of the upload — there is never a window
     * where a file exists with half its metadata.
     */
    @Override
    public StoredFile store(MultipartFile file, List<String> tags, String uploadedBy) {
        String filename = safeFilename(file.getOriginalFilename());
        String declaredContentType = file.getContentType();

        // Pass 1: Tika. Contractually never throws; a failure comes back as FAILED.
        ExtractionResult extraction;
        try (InputStream in = file.getInputStream()) {
            extraction = contentExtractor.extract(in, filename, declaredContentType);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read upload for extraction: " + filename, e);
        }

        // Tika's sniffed type beats the browser's guess: browsers routinely send
        // application/octet-stream, or a type derived purely from the extension.
        String contentType = firstNonBlank(extraction.detectedContentType(), declaredContentType, DEFAULT_CONTENT_TYPE);
        FileCategory category = FileCategory.fromContentType(contentType);

        String text = extraction.text() == null ? "" : extraction.text();
        ExtractionState state = extraction.state() == null ? ExtractionState.SKIPPED : extraction.state();

        // Defensive clip. The extractor is expected to honour the limit already,
        // but extractedText lives inside the fs.files document and a document
        // over 16 MB is rejected outright by the server — better a truncated
        // search index than a failed upload.
        String clipped = clipToUtf8Bytes(text, demoProperties.maxExtractedTextBytes());
        if (clipped.length() != text.length()) {
            text = clipped;
            state = ExtractionState.TRUNCATED;
        }

        ExtractionMethod method = text.isEmpty() ? ExtractionMethod.NONE : ExtractionMethod.TIKA;
        boolean ocrApplied = false;

        // Pass 1b (conditional): OCR on the way in, so a scanned page is
        // searchable the moment it lands rather than after someone notices the
        // search misses it and presses a button.
        if (shouldAutoOcr(category, contentType, text)) {
            TextMerge merged = ocrInto(file, filename, text, state, method);
            text = merged.text();
            state = merged.state();
            method = merged.method();
            ocrApplied = true;
        }

        Document metadata = new Document()
                .append("contentType", contentType)
                .append("category", category.name())
                .append("tags", normaliseTags(tags))
                .append("extractedText", text)
                .append("extractionState", state.name())
                .append("extractionMethod", method.name())
                .append("ocrApplied", ocrApplied)
                .append("textLength", text.length())
                .append("pageCount", extraction.pageCount())
                .append("author", extraction.author())
                .append("title", extraction.title())
                .append("durationMillis", extraction.durationMillis())
                .append("uploadedBy", firstNonBlank(uploadedBy, DEFAULT_UPLOADED_BY))
                .append("checksumSha256", null);

        GridFSUploadOptions options = new GridFSUploadOptions()
                .chunkSizeBytes(CHUNK_SIZE_BYTES)
                .metadata(metadata);

        // Pass 2: the actual upload, with the checksum computed from the same
        // bytes as they fly past — no third read of the file.
        MessageDigest digest = sha256();
        GridFSUploadStream upload = bucket.openUploadStream(filename, options);
        // The id is assigned when the stream is opened, not at close, so we can
        // capture it up front and leave nothing after close() that could throw.
        ObjectId id = upload.getObjectId();
        boolean committed = false;

        try (InputStream raw = file.getInputStream();
             DigestInputStream digesting = new DigestInputStream(raw, digest)) {

            byte[] buffer = new byte[CHUNK_SIZE_BYTES];
            int read;
            while ((read = digesting.read(buffer)) != -1) {
                upload.write(buffer, 0, read);
            }

            // The driver serialises `metadata` into the fs.files document inside
            // close(), after the last chunk is written, so mutating the same
            // Document instance here lands the checksum in the stored document.
            // That is what lets us hash while streaming instead of re-reading.
            metadata.put("checksumSha256", HexFormat.of().formatHex(digest.digest()));

            upload.close();   // flushes the final chunk, then inserts fs.files
            committed = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store " + filename + " in GridFS", e);
        } finally {
            if (!committed) {
                // No fs.files document was written, but chunks may have been.
                // abort() deletes them, so a failed upload leaves nothing behind.
                // It must not be called after a successful close().
                try {
                    upload.abort();
                } catch (RuntimeException suppressed) {
                    log.warn("Could not abort failed upload of {}: {}", filename, suppressed.toString());
                }
            }
        }

        if (state == ExtractionState.FAILED) {
            log.warn("Stored {} ({}) but extraction failed: {}", filename, id.toHexString(), extraction.error());
        } else {
            log.debug("Stored {} ({}) category={} state={} textLength={}",
                    filename, id.toHexString(), category, state, text.length());
        }

        // Read back so length, chunkSize and uploadDate come from the server's
        // own document rather than being guessed client-side.
        return findById(id.toHexString())
                .orElseThrow(() -> new IllegalStateException("Stored file vanished: " + id.toHexString()));
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Override
    public Optional<StoredFile> findById(String id) {
        return toObjectId(id).flatMap(oid -> {
            Query query = Query.query(Criteria.where("_id").is(oid));
            query.fields().exclude(EXTRACTED_TEXT_FIELD);
            Document doc = mongoTemplate.findOne(query, Document.class, filesCollection);
            return Optional.ofNullable(doc).map(StoredFileMapper::fromDocument);
        });
    }

    @Override
    public List<StoredFile> listAll(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);

        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "uploadDate"))
                .skip((long) safePage * safeSize)
                .limit(safeSize);
        // See EXTRACTED_TEXT_FIELD: never ship the extracted text to a list view.
        query.fields().exclude(EXTRACTED_TEXT_FIELD);

        return mongoTemplate.find(query, Document.class, filesCollection).stream()
                .map(StoredFileMapper::fromDocument)
                .toList();
    }

    @Override
    public long count() {
        // Exact rather than estimated: the UI pages off this number, and a stale
        // estimate would produce an empty last page.
        return mongoTemplate.getCollection(filesCollection).countDocuments();
    }

    /**
     * Opens a read stream seeked to {@code startByte}.
     *
     * <p>This is the point of the whole demo. {@link GridFSDownloadStream#skip(long)}
     * is not the naive {@code InputStream.skip} that reads and discards bytes.
     * The driver keeps the file's {@code chunkSize} and its current chunk index,
     * and {@code skip} recomputes that index as
     * {@code floor((position + n) / chunkSize)}, sets the offset within the chunk
     * to {@code (position + n) % chunkSize}, and discards the open cursor over
     * {@code fs.chunks}. The next {@code read()} therefore issues a fresh query
     * filtered on {@code {files_id: <id>, n: {$gte: <newChunkIndex>}}}.
     *
     * <p>Concretely: seeking to 400 MB into a 500 MB video costs one indexed
     * lookup and one chunk fetch, not 400 MB of transfer. That is what makes
     * scrubbing a video in the browser feel instant, and it is why this method
     * uses the raw {@code GridFSDownloadStream} rather than the plain
     * {@code InputStream} that {@code GridFsTemplate} hands back.
     *
     * <p>The caller owns the returned stream and must close it.
     *
     * @throws MongoGridFSException if the id is malformed or no such file exists
     */
    @Override
    public InputStream openStream(String id, long startByte) {
        ObjectId oid = toObjectId(id)
                .orElseThrow(() -> new MongoGridFSException("No file with id " + id));

        GridFSDownloadStream stream = bucket.openDownloadStream(oid);
        try {
            if (startByte > 0) {
                // skip() fully honours the request in one call unless it hits
                // EOF, but loop anyway so we never hand back a mis-positioned
                // stream if that ever changes.
                long remaining = startByte;
                while (remaining > 0) {
                    long skipped = stream.skip(remaining);
                    if (skipped <= 0) break;   // clamped at end of file
                    remaining -= skipped;
                }
            }
            return stream;
        } catch (RuntimeException e) {
            stream.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Delegates to the bucket so both {@code fs.files} and every matching
     * {@code fs.chunks} document go. The driver deletes the files document
     * first, then the chunks: there is no multi-document transaction, but that
     * ordering means a crash in between leaves orphan chunks rather than a file
     * that appears to exist and cannot be read.
     */
    @Override
    public boolean delete(String id) {
        Optional<ObjectId> oid = toObjectId(id);
        if (oid.isEmpty()) return false;
        try {
            bucket.delete(oid.get());
            return true;
        } catch (MongoGridFSException e) {
            // Thrown when no files document matched. A delete of something that
            // is already gone is a 404, not a 500.
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Extracted text
    // ------------------------------------------------------------------

    /**
     * The one query in this class that deliberately asks for
     * {@link #EXTRACTED_TEXT_FIELD}, with an inclusion projection so the server
     * sends that field and nothing else. Reading the whole document would drag
     * every other field along for no reason, and reading it via
     * {@link #findById} is impossible by construction — that method projects the
     * text away.
     *
     * <p>Empty Optional and {@code Optional.of("")} mean different things and the
     * controller depends on the difference: empty is "no such file" and becomes a
     * 404, while an empty string is "this file exists and genuinely has no text"
     * (an image, a video, a failed parse) and becomes a 200 with an empty body.
     * A 404 there would be a lie about the file's existence.
     */
    @Override
    public Optional<String> extractedText(String id) {
        return toObjectId(id).flatMap(oid -> {
            Query query = Query.query(Criteria.where("_id").is(oid));
            query.fields().include(EXTRACTED_TEXT_FIELD);
            Document doc = mongoTemplate.findOne(query, Document.class, filesCollection);
            if (doc == null) {
                return Optional.empty();
            }
            Document md = doc.get("metadata", Document.class);
            String text = md == null ? null : md.getString("extractedText");
            return Optional.of(text == null ? "" : text);
        });
    }

    // ------------------------------------------------------------------
    // Metadata editing
    // ------------------------------------------------------------------

    /**
     * A single {@code $set} touching only the fields the caller actually
     * supplied, rather than reading the document, mutating it in Java and writing
     * it back whole. Read-modify-write would silently clobber anything that
     * changed in between — most realistically an OCR run started from another tab,
     * which rewrites {@code extractedText}, {@code textLength} and
     * {@code ocrApplied} on the same sub-document. {@code $set} on named paths
     * leaves every unnamed field exactly as the server has it.
     *
     * <p>Note that {@code filename} is a top-level field of the {@code fs.files}
     * document, not part of our {@code metadata} sub-document: it belongs to the
     * GridFS spec itself, which is also why the driver indexes it. Writing it to
     * {@code metadata.filename} would create a second, invisible name that
     * nothing reads.
     *
     * <p>An edit where every field is null is a legal no-op. We skip the update
     * entirely in that case — an {@code Update} with no operations is not a valid
     * command document — but still re-read so an unknown id is reported honestly.
     */
    @Override
    public Optional<StoredFile> updateMetadata(String id, MetadataEdit edit) {
        Optional<ObjectId> oid = toObjectId(id);
        if (oid.isEmpty()) {
            return Optional.empty();
        }
        if (edit == null) {
            return findById(id);
        }

        Update update = new Update();
        boolean any = false;

        // Blank filenames are rejected with a 400 upstream; ignoring one here as
        // well means a malformed direct call cannot leave a file unnamed.
        if (StringUtils.hasText(edit.filename())) {
            update.set("filename", safeFilename(edit.filename()));
            any = true;
        }
        // Empty string is a real value here — it is how the UI clears a title or
        // an author. Only null means "leave alone".
        if (edit.title() != null) {
            update.set("metadata.title", edit.title());
            any = true;
        }
        if (edit.author() != null) {
            update.set("metadata.author", edit.author());
            any = true;
        }
        if (edit.tags() != null) {
            // Same normalisation as the upload path, and that is load-bearing
            // rather than tidiness: the Atlas Search index maps
            // metadata.tags as a `token` type with no lowercase normalizer, so
            // the stored bytes are compared literally. A tag typed as "Finance"
            // in the edit form would then be a different token from the
            // "finance" applied at upload, and the facet filter would silently
            // return nothing for one of them. Normalising in one shared helper
            // is what keeps both write paths producing the same tokens.
            // An empty list is written through as an empty array, which is the
            // only way the API can clear tags.
            update.set("metadata.tags", normaliseTags(edit.tags()));
            any = true;
        }

        if (any) {
            var result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(oid.get())), update, filesCollection);
            if (result.getMatchedCount() == 0) {
                return Optional.empty();
            }
        }

        // Re-read rather than returning a locally patched record: the caller
        // should see exactly what is now stored, including the normalised tags
        // it did not send.
        return findById(id);
    }

    // ------------------------------------------------------------------
    // OCR
    // ------------------------------------------------------------------

    /**
     * Order of checks matters. An unknown id is a 404 and must win over both the
     * "engine missing" 503 and the "wrong file type" 409 — otherwise a typo in a
     * URL would be reported as a server capability problem.
     *
     * <p>The bytes are streamed straight out of GridFS rather than buffered:
     * {@link #openStream} hands back a stream the OCR engine consumes as it goes,
     * so a 30 MB scan never becomes a 30 MB array on the heap.
     */
    @Override
    public Optional<StoredFile> runOcr(String id) {
        Optional<ObjectId> oid = toObjectId(id);
        if (oid.isEmpty()) {
            return Optional.empty();
        }

        Query query = Query.query(Criteria.where("_id").is(oid.get()));
        query.fields().include("filename")
                .include("metadata.contentType")
                .include("metadata.extractedText")
                .include("metadata.extractionState")
                .include("metadata.extractionMethod");
        Document doc = mongoTemplate.findOne(query, Document.class, filesCollection);
        if (doc == null) {
            return Optional.empty();
        }

        Document md = doc.get("metadata", Document.class);
        if (md == null) {
            md = new Document();
        }
        String filename = firstNonBlank(doc.getString("filename"), DEFAULT_FILENAME);
        String contentType = md.getString("contentType");

        if (!ocrService.isAvailable()) {
            // Mapped to 503 by the exception handler: the request was reasonable,
            // this deployment simply has no tesseract binary.
            throw new OcrUnavailableException("OCR is unavailable: no tesseract engine on this host");
        }
        if (!ocrService.isOcrCandidate(contentType)) {
            // Mapped to 409. Running OCR over an MP4 or a spreadsheet is not a
            // transient failure, so it is a conflict with the file's nature
            // rather than an error the caller can retry.
            throw new NotOcrableException(
                    "Not an OCR candidate: " + filename + " (" + contentType + ")");
        }

        String existingText = md.getString("extractedText");
        if (existingText == null) {
            existingText = "";
        }
        ExtractionState existingState = parseState(md.getString("extractionState"));
        ExtractionMethod existingMethod = parseMethod(md.getString("extractionMethod"), existingText);

        OcrService.OcrResult result;
        try (InputStream in = openStream(id, 0)) {
            result = ocrService.recognise(in, filename);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + filename + " for OCR", e);
        }

        TextMerge merged = mergeOcr(existingText, existingState, existingMethod, result, filename);

        Update update = new Update()
                .set("metadata.extractedText", merged.text())
                .set("metadata.extractionState", merged.state().name())
                .set("metadata.extractionMethod", merged.method().name())
                .set("metadata.textLength", merged.text().length())
                // Set unconditionally, even when nothing was recognised. See
                // mergeOcr: "we looked and there is nothing there" is a real
                // answer and the user should not be invited to pay for it twice.
                .set("metadata.ocrApplied", true);

        var updateResult = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(oid.get())), update, filesCollection);
        if (updateResult.getMatchedCount() == 0) {
            // Deleted between our read and our write. Nothing to report.
            return Optional.empty();
        }

        return findById(id);
    }

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    /**
     * One aggregation over {@code fs.files} produces everything except the chunk
     * count: grouping by category gives the per-category breakdown, and summing
     * the group totals in Java gives file count, bytes and indexed text bytes
     * without a second pass over the collection.
     *
     * <p>An empty collection yields no groups at all, so every total falls out as
     * zero and the map is empty — never null.
     */
    @Override
    public StorageStats stats() {
        List<Document> pipeline = List.of(new Document("$group", new Document()
                .append("_id", "$metadata.category")
                .append("files", new Document("$sum", 1))
                .append("bytes", new Document("$sum", "$length"))
                .append("textBytes", new Document("$sum", "$metadata.textLength"))));

        long fileCount = 0;
        long totalBytes = 0;
        long indexedTextBytes = 0;
        Map<String, Long> byCategory = new LinkedHashMap<>();

        for (Document group : mongoTemplate.getCollection(filesCollection).aggregate(pipeline)) {
            long files = numberOf(group.get("files"));
            fileCount += files;
            totalBytes += numberOf(group.get("bytes"));
            indexedTextBytes += numberOf(group.get("textBytes"));

            Object key = group.get("_id");
            byCategory.merge(key == null ? FileCategory.OTHER.name() : key.toString(), files, Long::sum);
        }

        return new StorageStats(fileCount, totalBytes, chunkCount(), indexedTextBytes, byCategory);
    }

    /**
     * Estimated rather than exact, and intentionally so: {@code fs.chunks} holds
     * one document per megabyte of stored data, and an exact
     * {@code countDocuments} is a full index scan that grows with the corpus.
     * {@code estimatedDocumentCount} reads the collection's cached metadata in
     * effectively constant time. This number feeds a dashboard tile, so a count
     * that lags an in-flight upload by a moment costs nothing.
     */
    private long chunkCount() {
        try {
            return mongoTemplate.getCollection(chunksCollection).estimatedDocumentCount();
        } catch (RuntimeException e) {
            log.debug("Could not estimate chunk count: {}", e.toString());
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // OCR helpers
    // ------------------------------------------------------------------

    /** The outcome of folding OCR output into whatever text already existed. */
    private record TextMerge(String text, ExtractionState state, ExtractionMethod method) {}

    /**
     * Decides whether an upload is worth OCR-ing before it is written.
     *
     * <p>Images: always. There is no text layer to parse, so OCR is the only way
     * an image ever becomes searchable, and a photo of a whiteboard is exactly
     * the case this demo wants to show off.
     *
     * <p>PDFs: only when Tika came back with nothing. A born-digital PDF already
     * has a perfect text layer and re-reading it through an OCR engine costs
     * seconds of CPU to produce a strictly worse copy of text we already hold. A
     * PDF with no text at all is a scan, and for that OCR is the whole story.
     *
     * <p>Video and audio: never. Tesseract reads pixels, not frames, and running
     * it over a 500 MB MP4 would burn minutes to recognise nothing.
     *
     * <p>Cost note: this is synchronous and adds the OCR run to the upload's own
     * latency — seconds for a dense scan. {@link OcrService} enforces its own
     * timeout so the ceiling is bounded and an upload cannot hang indefinitely on
     * a pathological image, but the user does wait. That is the deliberate trade:
     * a slower upload once, against a file that is silently unsearchable forever.
     */
    private boolean shouldAutoOcr(FileCategory category, String contentType, String tikaText) {
        // Presenter switch. With auto-OCR on, an uploaded scan is searchable
        // immediately — which is the right product behaviour, but it also means
        // the on-demand "Run OCR" button never appears for a freshly seeded
        // corpus, so that half of the feature cannot be shown. Turning this off
        // (gridfs-demo.auto-ocr-on-upload=false) leaves images un-OCR'd on
        // upload so the manual path can be demonstrated deliberately.
        if (!demoProperties.autoOcrOnUpload()) {
            return false;
        }
        if (!ocrService.isAvailable() || !ocrService.isOcrCandidate(contentType)) {
            return false;
        }
        if (category == FileCategory.IMAGE) {
            return true;
        }
        if (category == FileCategory.MEDIA) {
            return false;
        }
        // Documents: only the ones Tika could make nothing of.
        return !StringUtils.hasText(tikaText);
    }

    /** Runs OCR over the upload's bytes — a third pass over the multipart body. */
    private TextMerge ocrInto(MultipartFile file,
                              String filename,
                              String existingText,
                              ExtractionState existingState,
                              ExtractionMethod existingMethod) {
        OcrService.OcrResult result;
        try (InputStream in = file.getInputStream()) {
            result = ocrService.recognise(in, filename);
        } catch (IOException e) {
            // The bytes still have to reach GridFS. A failed OCR read must not
            // cost the user their upload, so it degrades to "no text found".
            log.warn("Could not read {} for OCR on upload: {}", filename, e.toString());
            result = OcrService.OcrResult.empty(e.toString());
        }
        return mergeOcr(existingText, existingState, existingMethod, result, filename);
    }

    /**
     * Folds recognised text into whatever Tika already produced.
     *
     * <p>Two judgement calls are encoded here.
     *
     * <p><b>Empty or unconfident OCR still counts as applied.</b> The caller sets
     * {@code ocrApplied = true} regardless of what comes back. It would be
     * tempting to leave the flag false so the user can retry, but the flag does
     * not mean "we found text" — it means "this engine has looked at this file".
     * Leaving it false would keep offering a button that will do the same slow
     * work and reach the same conclusion, and the honest answer to "OCR this" on
     * a photo of a sunset is "there is no text here", which the user can only
     * learn if we record that we looked.
     *
     * <p><b>An unconfident result never overwrites good text.</b> When the engine
     * reports low confidence it is returning character soup, and appending soup
     * to a clean Tika extraction actively damages search: the noise is indexed,
     * matches nonsense queries, and dilutes the relevance of the real text. So a
     * result that is blank or unconfident is discarded and the existing text is
     * left exactly as it was. The state then reflects what we know: text we
     * already had stays in its existing state, while a file that had none and
     * gained none is SKIPPED — or FAILED when the engine itself errored, which is
     * a different thing from a page that legitimately holds no words.
     *
     * <p>The merged text is clipped to the same byte budget as the upload path.
     * {@code extractedText} lives inside the {@code fs.files} document, so
     * appending to an already-large extraction is precisely the operation that
     * could push it past the server's 16 MB document limit and make the update
     * fail outright. Better a truncated index than a rejected write.
     */
    private TextMerge mergeOcr(String existingText,
                               ExtractionState existingState,
                               ExtractionMethod existingMethod,
                               OcrService.OcrResult result,
                               String filename) {
        String ocrText = result == null || result.text() == null ? "" : result.text().strip();
        boolean usable = !ocrText.isEmpty() && result.confident();

        if (!usable) {
            if (StringUtils.hasText(existingText)) {
                log.debug("OCR added nothing to {} (confident={}), keeping the existing text",
                        filename, result != null && result.confident());
                return new TextMerge(existingText, existingState, existingMethod);
            }
            boolean errored = result != null && result.error() != null;
            ExtractionState state = errored ? ExtractionState.FAILED : ExtractionState.SKIPPED;
            log.debug("OCR found no text in {} (state={})", filename, state);
            return new TextMerge("", state, ExtractionMethod.NONE);
        }

        boolean hadText = StringUtils.hasText(existingText);
        // A newline, not a space: the two bodies of text are unrelated runs and
        // joining them without a break could fuse the last word of one to the
        // first of the other into a token that matches neither.
        String combined = hadText ? existingText + "\n" + ocrText : ocrText;
        ExtractionMethod method = hadText ? ExtractionMethod.TIKA_AND_OCR : ExtractionMethod.OCR;

        String clipped = clipToUtf8Bytes(combined, demoProperties.maxExtractedTextBytes());
        ExtractionState state = clipped.length() == combined.length()
                ? ExtractionState.EXTRACTED
                : ExtractionState.TRUNCATED;

        log.debug("OCR merged {} chars into {} ({} ms, method={}, state={})",
                ocrText.length(), filename, result.tookMillis(), method, state);
        return new TextMerge(clipped, state, method);
    }

    private static ExtractionState parseState(String stored) {
        if (stored == null) return ExtractionState.SKIPPED;
        try {
            return ExtractionState.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return ExtractionState.SKIPPED;
        }
    }

    /**
     * Mirrors the mapper's derivation for documents written before
     * {@code extractionMethod} existed: any text they carry came from Tika,
     * because nothing else could have put it there.
     */
    private static ExtractionMethod parseMethod(String stored, String existingText) {
        if (stored != null) {
            try {
                return ExtractionMethod.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through to derivation
            }
        }
        return StringUtils.hasText(existingText) ? ExtractionMethod.TIKA : ExtractionMethod.NONE;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A user-supplied id reaches us straight off the URL path, so
     * {@code new ObjectId(...)} on it would throw {@link IllegalArgumentException}
     * for anything that is not 24 hex characters. Callers get an empty Optional
     * instead and turn it into a clean 404.
     */
    private static Optional<ObjectId> toObjectId(String id) {
        if (!StringUtils.hasText(id) || !ObjectId.isValid(id)) {
            return Optional.empty();
        }
        return Optional.of(new ObjectId(id));
    }

    /**
     * Browsers may send a full client-side path as the filename (historically
     * Internet Explorer, and some upload widgets still do). Keep only the last
     * segment so the stored name is a plain filename.
     */
    private static String safeFilename(String original) {
        if (!StringUtils.hasText(original)) return DEFAULT_FILENAME;
        String cleaned = StringUtils.getFilename(original.replace('\\', '/'));
        return StringUtils.hasText(cleaned) ? cleaned : DEFAULT_FILENAME;
    }

    /**
     * Trimmed, lowercased, blank-free and de-duplicated, preserving the order
     * given.
     *
     * <p>The lowercasing is the important part and it is not cosmetic. The Atlas
     * Search index maps {@code metadata.tags} as a {@code token} field with no
     * lowercase normalizer, so tags are matched byte-for-byte: "Finance" and
     * "finance" are two distinct tokens and a facet filter on one will silently
     * return nothing for files tagged with the other. Every write path — upload
     * and metadata edit alike — funnels through this one method so the stored
     * tokens can never disagree. {@code Locale.ROOT} rather than the default
     * locale because the JVM's locale is an accident of the host, and Turkish
     * would otherwise map "I" to a dotless i and produce a token no query can
     * reproduce.
     */
    private static List<String> normaliseTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (tag == null) continue;
            String normalised = tag.trim().toLowerCase(Locale.ROOT);
            if (!normalised.isEmpty() && !out.contains(normalised)) {
                out.add(normalised);
            }
        }
        return out;
    }

    /**
     * Clips the text so its UTF-8 encoding fits {@code maxBytes}. Truncating by
     * characters would be wrong — the limit is a BSON byte budget, and one
     * character can cost up to four bytes. We cut the encoded bytes and then walk
     * backwards off any UTF-8 continuation byte ({@code 10xxxxxx}), so the result
     * never ends mid-character and always decodes cleanly.
     */
    private static String clipToUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;

        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static long numberOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) return candidate;
        }
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
