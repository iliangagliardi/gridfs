package com.mongodb.demo.gridfs.domain;

/** Outcome of the Tika text-extraction step for an uploaded file. */
public enum ExtractionState {
    /** Text pulled out and stored whole in metadata.extractedText. */
    EXTRACTED,
    /** Text pulled out but clipped to stay under the BSON document limit. */
    TRUNCATED,
    /** Binary/media type with no meaningful text layer. */
    SKIPPED,
    /** Parser blew up; the file is still stored and downloadable. */
    FAILED
}
