package dev.scathiard.feedmepackages.compat.fxntstorage;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Pure counting for the F-5 "who is lying" diagnostic: how much of a material one source really holds. */
public final class PreviewCounts {
    private PreviewCounts() {}

    public static <T> int sum(List<T> items, Predicate<T> accepts, ToIntFunction<T> amount) {
        int total = 0;
        if (items == null) return 0;
        for (T item : items) {
            if (item == null) continue;
            if (accepts.test(item)) total += Math.max(0, amount.applyAsInt(item));
        }
        return total;
    }
}