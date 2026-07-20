package com.mongodb.demo.gridfs.domain;

import java.time.Instant;
import java.util.List;

/**
 * A file as seen by the API layer. Mirrors one document in the GridFS
 * {@code fs.files} collection, with our custom {@code metadata} sub-document
 * flattened into the top level for convenient JSON serialisation.
 *
 * <p>The GridFS files document we write looks like:
 * <pre>
 * {
 *   _id:        ObjectId,
 *   filename:   "quarterly-report.pdf",
 *   length:     284718,
 *   chunkSize:  261120,
 *   uploadDate: ISODate,
 *   metadata: {
 *     contentType:    "application/pdf",
 *     category:       "DOCUMENT" | "MEDIA" | "IMAGE" | "OTHER",
 *     tags:           ["finance", "q3"],
 *     extractedText:  "....",        // Tika output, truncated
 *     extractionState:"EXTRACTED" | "SKIPPED" | "FAILED" | "TRUNCATED",
 *     extractionMethod:"TIKA" | "OCR" | "TIKA_AND_OCR" | "NONE",
 *     ocrApplied:     false,
 *     textLength:     18422,
 *     pageCount:      12,            // nullable
 *     author:         "...",         // nullable
 *     title:          "...",         // nullable
 *     durationMillis: 184000,        // nullable, media only
 *     uploadedBy:     "demo",
 *     checksumSha256: "..."
 *   }
 * }
 * </pre>
 */
public record StoredFile(
        String id,
        String filename,
        long length,
        int chunkSize,
        Instant uploadDate,
        String contentType,
        FileCategory category,
        List<String> tags,
        ExtractionState extractionState,
        int textLength,
        Integer pageCount,
        String author,
        String title,
        Long durationMillis,
        String uploadedBy,
        String checksumSha256,
        /** How the extracted text was obtained. Never null; NONE when there is none. */
        ExtractionMethod extractionMethod,
        /** True once Tesseract has run over this file, so the UI can hide the OCR button. */
        boolean ocrApplied,
        /** Populated only by search responses; null elsewhere. */
        String snippet,
        /** Atlas Search relevance score; null for non-search listings. */
        Double score
) {
    /** Number of GridFS chunks this file occupies. */
    public long chunkCount() {
        return chunkSize == 0 ? 0 : (length + chunkSize - 1) / chunkSize;
    }
}
