package com.mongodb.demo.gridfs.domain;

import java.util.List;
import java.util.Map;

/**
 * @param results     the page of hits
 * @param total       total matching files
 * @param mode        which engine actually served this request
 * @param tookMillis  server-side latency, shown in the UI
 * @param facets      category -> count, from the same aggregation
 * @param explain     the actual aggregation pipeline, pretty-printed, so the
 *                    demo can show the client what MongoDB really ran
 */
public record SearchResponse(
        List<StoredFile> results,
        long total,
        SearchMode mode,
        long tookMillis,
        Map<String, Long> facets,
        String explain
) {}
