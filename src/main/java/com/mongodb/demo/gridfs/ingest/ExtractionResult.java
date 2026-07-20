package com.mongodb.demo.gridfs.ingest;

import com.mongodb.demo.gridfs.domain.ExtractionState;

/**
 * Outcome of running a file through Apache Tika.
 *
 * @param state       what happened
 * @param text        extracted plain text (never null; empty when skipped/failed)
 * @param pageCount   nullable, documents only
 * @param author      nullable
 * @param title       nullable
 * @param durationMillis nullable, media only
 * @param detectedContentType Tika's own verdict, which beats the browser's guess
 * @param error       nullable, populated when state == FAILED
 */
public record ExtractionResult(
        ExtractionState state,
        String text,
        Integer pageCount,
        String author,
        String title,
        Long durationMillis,
        String detectedContentType,
        String error
) {}
