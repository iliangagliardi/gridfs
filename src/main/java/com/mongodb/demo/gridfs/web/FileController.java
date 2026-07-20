package com.mongodb.demo.gridfs.web;

import com.mongodb.demo.gridfs.domain.StoredFile;
import com.mongodb.demo.gridfs.storage.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * CRUD over the GridFS bucket. Byte streaming lives next door in
 * {@link StreamController} because Range handling is involved enough that
 * mixing it in here would bury the plain REST surface.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FileStorageService storage;

    public FileController(FileStorageService storage) {
        this.storage = storage;
    }

    /**
     * Multipart upload. {@code tags} arrives as one CSV string rather than
     * repeated form fields because the UI binds it to a single free-text input;
     * normalising to lowercase here keeps tag filtering in search case-agnostic
     * without needing a collation.
     */
    @PostMapping
    public ResponseEntity<StoredFile> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uploaded file is empty. Pick a file with at least one byte.");
        }

        StoredFile stored = storage.store(file, parseTags(tags), uploadedBy);
        return ResponseEntity
                .created(URI.create("/api/files/" + stored.id()))
                .body(stored);
    }

    /** Newest first, per the storage contract. */
    @GetMapping
    public PageResponse<StoredFile> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return new PageResponse<>(storage.listAll(safePage, safeSize), storage.count(), safePage, safeSize);
    }

    @GetMapping("/{id}")
    public StoredFile get(@PathVariable String id) {
        return storage.findById(id).orElseThrow(() -> notFound(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!storage.delete(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The Tika output, served as plain text so the browser can show it raw.
     * Files whose extraction was skipped or failed still return 200 with an
     * empty body — the {@code extractionState} on the record already tells the
     * UI why, and a 404 here would be a lie about the file's existence.
     */
    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> text(@PathVariable String id) {
        // 404 semantics come from the file itself, not from the text lookup: an
        // empty Optional from extractedText() only means "no text was extracted",
        // which is a legitimate 200 with an empty body.
        storage.findById(id).orElseThrow(() -> notFound(id));

        String extracted = storage.extractedText(id).orElse("");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(extracted);
    }

    /**
     * Partial metadata edit.
     *
     * <p>The contract distinguishes three shapes for each key: absent, explicit
     * {@code null}, and (for {@code tags}) an empty array. A plain record bound
     * by Jackson cannot tell an absent key from an explicit {@code null} — both
     * arrive as a {@code null} component — and it does not need to, because the
     * contract deliberately gives them the <em>same</em> meaning: "leave
     * unchanged". The only distinction that has to survive binding is
     * {@code null} versus {@code []} for {@code tags}, and that one does survive:
     * Jackson leaves an omitted array component null and materialises
     * {@code "tags": []} as an empty {@code List}, so
     * {@link FileStorageService.MetadataEdit#tags()} being empty-but-non-null is
     * an unambiguous "clear all tags". That is exactly why the contract collapses
     * absent and null together rather than assigning them different semantics —
     * doing otherwise would force a JsonNullable-style wrapper on every field for
     * no behavioural gain.
     */
    @PatchMapping("/{id}/metadata")
    public StoredFile updateMetadata(@PathVariable String id,
                                     @RequestBody(required = false) FileStorageService.MetadataEdit edit) {

        FileStorageService.MetadataEdit safeEdit =
                edit == null ? new FileStorageService.MetadataEdit(null, null, null, null) : edit;
        validateFilename(safeEdit.filename());

        return storage.updateMetadata(id, safeEdit).orElseThrow(() -> notFound(id));
    }

    /**
     * Re-runs OCR over an already-stored file.
     *
     * <p><strong>This intentionally blocks a request thread</strong> for as long
     * as Tesseract takes — seconds per page, tens of seconds for a long scanned
     * PDF. That is acceptable here because the demo has a single operator
     * pressing a button and the visible latency is part of the point: it shows
     * what OCR actually costs. In production this would be wrong — a handful of
     * concurrent presses would starve the servlet pool — and the work belongs on
     * a queue with the endpoint returning {@code 202 Accepted} plus a job id.
     *
     * <p>Failure modes are distinguished rather than lumped into a 500: a file
     * that simply is not OCR-able is a client-side mistake about <em>this</em>
     * resource ({@code 409}), whereas a missing tesseract binary is an
     * environment problem the caller can retry after fixing ({@code 503}). Both
     * are translated in {@link ApiExceptionHandler}.
     */
    @PostMapping("/{id}/ocr")
    public StoredFile ocr(@PathVariable String id) {
        return storage.runOcr(id).orElseThrow(() -> notFound(id));
    }

    /**
     * A filename is only validated when the caller actually sends one, because
     * null means "unchanged". Path separators and NUL are rejected outright:
     * nothing downstream ever resolves a stored filename against a filesystem,
     * but letting {@code ../} shaped values into the metadata invites the first
     * consumer that does to become a traversal bug.
     */
    private static void validateFilename(String filename) {
        if (filename == null) {
            return;
        }
        if (filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filename must not be blank. Omit the key entirely to leave it unchanged.");
        }
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filename must not contain path separators or NUL characters.");
        }
    }

    /** Splits a CSV tag string, trimming, lowercasing and dropping duplicates and blanks. */
    static List<String> parseTags(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String tag = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!tag.isEmpty()) {
                unique.add(tag);
            }
        }
        return new ArrayList<>(unique);
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No file with id " + id);
    }
}
