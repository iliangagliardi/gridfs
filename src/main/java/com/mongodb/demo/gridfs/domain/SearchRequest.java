package com.mongodb.demo.gridfs.domain;

import java.util.List;

/**
 * @param query      user text; blank means "match everything" (browse mode)
 * @param categories restrict to these categories; empty means no restriction
 * @param tags       restrict to files carrying all of these tags
 * @param fuzzy      enable Atlas Search fuzzy matching (typo tolerance)
 * @param page       zero-based
 * @param size       page size
 */
public record SearchRequest(
        String query,
        List<FileCategory> categories,
        List<String> tags,
        boolean fuzzy,
        int page,
        int size
) {
    public SearchRequest {
        if (categories == null) categories = List.of();
        if (tags == null) tags = List.of();
        if (size <= 0 || size > 100) size = 20;
        if (page < 0) page = 0;
    }

    public boolean isBrowse() {
        return query == null || query.isBlank();
    }
}
