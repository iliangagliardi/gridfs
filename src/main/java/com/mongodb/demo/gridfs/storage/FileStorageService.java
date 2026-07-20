package com.mongodb.demo.gridfs.storage;

import com.mongodb.demo.gridfs.domain.StoredFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** All GridFS reads and writes go through here. */
public interface FileStorageService {

    /** Stream the upload into GridFS, extracting text into metadata as it goes. */
    StoredFile store(MultipartFile file, List<String> tags, String uploadedBy);

    Optional<StoredFile> findById(String id);

    /** Newest first. */
    List<StoredFile> listAll(int page, int size);

    long count();

    /**
     * Opens a read stream over the file's bytes, seeked to {@code startByte}.
     *
     * <p>This is the interesting bit for the media demo: GridFS can skip
     * straight to the chunk containing {@code startByte} instead of reading the
     * file from the beginning, so seeking into the middle of a 500&nbsp;MB video
     * costs one chunk read, not a full scan.
     *
     * @param startByte absolute byte offset to begin at, 0 for the whole file
     */
    InputStream openStream(String id, long startByte);

    /** Removes the files document and every one of its chunks. */
    boolean delete(String id);

    /**
     * Reads the full extracted text. Kept off {@link StoredFile} on purpose:
     * it can be a megabyte per file and every listing projects it away.
     */
    Optional<String> extractedText(String id);

    /**
     * Applies a partial metadata edit. Only the fields present in
     * {@code edit} are written; nulls mean "leave alone".
     *
     * <p>Deliberately narrow. Editing {@code length}, {@code chunkSize} or the
     * checksum would make the metadata disagree with the bytes actually stored
     * in {@code fs.chunks}, so those are not editable at all.
     *
     * @return the updated file, or empty when the id is unknown
     */
    Optional<StoredFile> updateMetadata(String id, MetadataEdit edit);

    /**
     * Runs OCR over an already-stored file and merges the recognised text into
     * {@code metadata.extractedText}, so it becomes searchable.
     *
     * <p>Synchronous and potentially slow (seconds per page) — the caller is a
     * user-initiated button, not the upload path.
     *
     * @return the updated file, or empty when the id is unknown
     * @throws com.mongodb.demo.gridfs.ingest.NotOcrableException     when the
     *         file's type carries no recognisable pixels (mapped to 409)
     * @throws com.mongodb.demo.gridfs.ingest.OcrUnavailableException when the
     *         Tesseract engine is not installed on this host (mapped to 503)
     */
    Optional<StoredFile> runOcr(String id);

    /**
     * Editable metadata fields. A null field means "unchanged"; an empty list
     * for tags means "clear the tags", which is why tags cannot be null-vs-empty
     * ambiguous — see the controller for how that distinction is preserved.
     */
    record MetadataEdit(
            String filename,
            String title,
            String author,
            List<String> tags
    ) {}

    /** Aggregate stats for the dashboard header. */
    StorageStats stats();

    record StorageStats(
            long fileCount,
            long totalBytes,
            long chunkCount,
            long indexedTextBytes,
            java.util.Map<String, Long> byCategory
    ) {}
}
