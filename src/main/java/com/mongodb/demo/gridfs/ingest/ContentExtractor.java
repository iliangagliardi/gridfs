package com.mongodb.demo.gridfs.ingest;

import java.io.InputStream;

/** Pulls searchable plain text and metadata out of an uploaded byte stream. */
public interface ContentExtractor {

    /**
     * Never throws: an unparseable file still needs to land in GridFS, so
     * failures come back as {@link com.mongodb.demo.gridfs.domain.ExtractionState#FAILED}.
     *
     * @param in                stream positioned at the start of the file
     * @param filename          original filename, used as a detection hint
     * @param declaredContentType content type claimed by the client, may be null
     */
    ExtractionResult extract(InputStream in, String filename, String declaredContentType);
}
