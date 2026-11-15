package dev.scathiard.feedmepackages.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Each wire page fits the previous 144 KiB budget. Consumers only receive complete inventory snapshots. */
final class StockPages<T> {
    static final int SIZE = 128;
    private final List<T> pending = new ArrayList<>();
    private long next;

    static <T> List<T> split(int count, BiFunction<Integer, Integer, T> slice) {
        var result = new ArrayList<T>();
        for (int offset = 0;;) {
            int end = offset + Math.min(SIZE, count - offset);
            result.add(slice.apply(offset, end));
            if (end == count) return result;
            offset = end;
        }
    }

    T accept(T page, int offset, boolean last, int size, java.util.function.BiPredicate<T,T> matches,
             Function<List<T>,T> join) {
        if (offset == 0) clear();
        if (size > SIZE || !last && size != SIZE || offset != next
                || offset > 0 && (pending.isEmpty() || !matches.test(pending.getFirst(), page))) {
            clear();
            return null;
        }
        if (last && offset == 0) return page;
        pending.add(page);
        next += size;
        if (!last) return null;
        try { return join.apply(pending); }
        finally { clear(); }
    }

    void clear() { pending.clear(); next = 0; }

    static <A,T> List<A> collect(List<T> pages, Function<T,List<A>> field) {
        var values = new ArrayList<A>();
        for (T part : pages) values.addAll(field.apply(part));
        return values;
    }
}
