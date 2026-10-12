package com.jengabank.payments.common;

import java.util.List;

/** Every list endpoint returns this: { items, nextCursor }. */
public record Page<T>(List<T> items, String nextCursor) {

    /** Pass limit + 1 rows; this trims the extra one and builds the cursor from the last kept row. */
    public static <T> Page<T> of(List<T> rows, int limit, java.util.function.Function<T, String> cursorOf) {
        if (rows.size() > limit) {
            List<T> kept = rows.subList(0, limit);
            return new Page<>(List.copyOf(kept), Cursors.encode(cursorOf.apply(kept.get(limit - 1))));
        }
        return new Page<>(List.copyOf(rows), null);
    }
}
