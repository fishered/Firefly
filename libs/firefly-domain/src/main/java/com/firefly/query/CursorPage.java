package com.firefly.query;

import java.util.List;

public record CursorPage<T>(List<T> items, PageCursor nextCursor) {
    public CursorPage {
        items = List.copyOf(items);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }

    public static <T> CursorPage<T> of(List<T> fetched, int limit,
                                       java.util.function.Function<T, PageCursor> cursorFactory) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        PageCursor next = hasMore && !items.isEmpty() ? cursorFactory.apply(items.getLast()) : null;
        return new CursorPage<>(items, next);
    }
}
