package com.mongodb.demo.gridfs.ingest;

/**
 * Thrown when OCR was requested for a file whose type carries no recognisable
 * pixels — a video, an audio track, or a plain text document.
 *
 * <p>Extends {@link UnsupportedOperationException} so the existing 409 mapping
 * keeps working, while giving the condition a name the storage layer can throw
 * deliberately instead of reaching for a generic JDK exception.
 */
public class NotOcrableException extends UnsupportedOperationException {

    public NotOcrableException(String message) {
        super(message);
    }
}
