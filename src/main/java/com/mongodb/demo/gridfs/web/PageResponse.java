package com.mongodb.demo.gridfs.web;

import java.util.List;

/**
 * Envelope for the paged listing endpoints.
 *
 * <p>Deliberately hand-rolled rather than leaking Spring Data's {@code Page},
 * whose JSON shape is both verbose and unstable across versions. The UI only
 * ever needs these four fields, and the field names are frozen by
 * {@code API-CONTRACT.md}.
 *
 * @param items the page of results
 * @param total total number of matching records across all pages
 * @param page  zero-based page index that was served
 * @param size  requested page size
 */
public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size
) {}
