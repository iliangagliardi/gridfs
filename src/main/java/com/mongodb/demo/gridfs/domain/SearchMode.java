package com.mongodb.demo.gridfs.domain;

/**
 * Which query engine served a search request.
 *
 * <p>ATLAS_SEARCH uses the {@code $search} aggregation stage backed by a
 * mongot search index. It works both against MongoDB Atlas and against a local
 * deployment (the mongodb-atlas-local image, or MongoDB Community 8.2+ with
 * Search enabled) — this is the "on prem and on Atlas" story.
 *
 * <p>REGEX_FALLBACK is the degraded path for a plain community server with no
 * search node, so the demo never hard-fails in front of a client.
 */
public enum SearchMode {
    ATLAS_SEARCH,
    REGEX_FALLBACK
}
