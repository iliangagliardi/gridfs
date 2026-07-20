package com.mongodb.demo.gridfs.search;

import com.mongodb.demo.gridfs.domain.SearchRequest;
import com.mongodb.demo.gridfs.domain.SearchResponse;
import com.mongodb.demo.gridfs.domain.SearchMode;

import java.util.List;

/** Full-text search over the extracted content held in GridFS file metadata. */
public interface FileSearchService {

    SearchResponse search(SearchRequest request);

    /** Type-ahead suggestions for the search box. */
    List<String> autocomplete(String prefix, int limit);

    /** Which engine this deployment resolved to, for the UI badge. */
    SearchMode activeMode();

    /**
     * Creates the Atlas Search index if the deployment supports it and it is
     * not already there. Safe to call repeatedly.
     *
     * @return true when an index exists (pre-existing or freshly created)
     */
    boolean ensureSearchIndex();
}
