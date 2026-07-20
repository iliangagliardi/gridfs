package com.mongodb.demo.gridfs.web;

import com.mongodb.demo.gridfs.domain.SearchRequest;
import com.mongodb.demo.gridfs.domain.SearchResponse;
import com.mongodb.demo.gridfs.search.FileSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Search over the extracted text.
 *
 * <p>Search is a POST even though it reads nothing, because the request carries
 * a structured body — categories, tags, fuzzy flag, paging — that would be
 * unpleasant to encode as query parameters and would then be cached by
 * intermediaries the demo does not control.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final int MAX_AUTOCOMPLETE = 25;

    private final FileSearchService search;

    public SearchController(FileSearchService search) {
        this.search = search;
    }

    /**
     * The response reports which engine actually served the query, so the UI can
     * show an honest badge when a deployment has no search node and the request
     * silently degraded to the regex fallback.
     */
    @PostMapping
    public SearchResponse search(@RequestBody(required = false) SearchRequest request) {
        // A missing body means "browse everything" — the compact record
        // constructor already normalises nulls and paging bounds.
        SearchRequest effective = request != null
                ? request
                : new SearchRequest(null, null, null, false, 0, 20);
        return search.search(effective);
    }

    @GetMapping("/autocomplete")
    public List<String> autocomplete(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {

        if (q == null || q.isBlank()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 8 : Math.min(limit, MAX_AUTOCOMPLETE);
        return search.autocomplete(q.trim(), safeLimit);
    }
}
