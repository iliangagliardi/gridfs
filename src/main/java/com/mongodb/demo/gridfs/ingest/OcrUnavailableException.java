package com.mongodb.demo.gridfs.ingest;

/**
 * Thrown when OCR was requested but the Tesseract engine is not installed or
 * not reachable on this host.
 *
 * <p>A dedicated type rather than a bare {@code IllegalStateException}: the web
 * layer maps this to 503, and mapping the generic JDK exception instead would
 * silently turn <em>every</em> unrelated illegal-state bug anywhere in the app
 * into "OCR engine unavailable", hiding real 500s behind a misleading message.
 */
public class OcrUnavailableException extends IllegalStateException {

    public OcrUnavailableException(String message) {
        super(message);
    }
}
