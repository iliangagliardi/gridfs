package com.mongodb.demo.gridfs.ingest;

import java.io.InputStream;

/**
 * Optical character recognition over image bytes, backed by Tesseract.
 *
 * <p>Separate from {@link ContentExtractor} because OCR has a completely
 * different cost profile: Tika parsing a DOCX is sub-millisecond, while OCR on
 * a scanned page is seconds of CPU. That difference is why OCR runs
 * automatically only for freshly uploaded images, and on explicit request for
 * everything already in the bucket.
 */
public interface OcrService {

    /** False when the tesseract binary is absent, so callers can degrade cleanly. */
    boolean isAvailable();

    /** Human-readable engine version for the UI badge, or null when unavailable. */
    String engineVersion();

    /**
     * True when this content type is worth running OCR over. Images always;
     * PDFs only when they carry no text layer (a scanned PDF), which the caller
     * decides from the Tika result.
     */
    boolean isOcrCandidate(String contentType);

    /**
     * Never throws. A failed OCR run returns an empty result rather than
     * breaking an upload or a button press.
     *
     * @param in       image bytes, positioned at the start
     * @param filename original name, used only for logging
     */
    OcrResult recognise(InputStream in, String filename);

    /**
     * @param text       recognised text, never null, empty when nothing was found
     * @param confident  false when the engine returned only noise
     * @param tookMillis wall-clock cost, surfaced in the UI because OCR is slow
     *                   enough that users deserve to see why
     * @param error      null on success
     */
    record OcrResult(String text, boolean confident, long tookMillis, String error) {
        public static OcrResult empty(String error) {
            return new OcrResult("", false, 0L, error);
        }
    }
}
