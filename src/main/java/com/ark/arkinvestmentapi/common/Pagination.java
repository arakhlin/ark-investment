package com.ark.arkinvestmentapi.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class Pagination {
    private Pagination() {
    }

    public static PageRequest request(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be nonnegative and size between 1 and 100");
        return PageRequest.of(page, size, Sort.by("id"));
    }
}
