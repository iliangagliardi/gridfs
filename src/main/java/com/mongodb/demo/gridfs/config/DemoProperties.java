package com.mongodb.demo.gridfs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables under the {@code gridfs-demo.*} prefix. */
@ConfigurationProperties(prefix = "gridfs-demo")
public record DemoProperties(
        int maxExtractedTextBytes,
        String searchIndexName,
        String searchMode,
        int mediaChunkBytes,
        /**
         * When true (default) an uploaded image is OCR'd on the way in, so it is
         * searchable immediately. Set false to leave images un-OCR'd on upload,
         * which is what makes the on-demand "Run OCR" button demonstrable on a
         * freshly seeded corpus.
         */
        Boolean autoOcrOnUpload
) {
    public DemoProperties {
        if (maxExtractedTextBytes <= 0) maxExtractedTextBytes = 1_048_576;
        if (searchIndexName == null || searchIndexName.isBlank()) searchIndexName = "gridfs_content";
        if (searchMode == null || searchMode.isBlank()) searchMode = "AUTO";
        if (mediaChunkBytes <= 0) mediaChunkBytes = 2_097_152;
        if (autoOcrOnUpload == null) autoOcrOnUpload = Boolean.TRUE;
    }
}
