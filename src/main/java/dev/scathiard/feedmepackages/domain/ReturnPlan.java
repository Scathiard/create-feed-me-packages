package dev.scathiard.feedmepackages.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * R-019: build the ordered list of ordinary items one return dispatch would put into its package.
 *
 * <p>The rules come straight from the 0.3.1 specification: only cells whose excess reaches their own per-cell minimum
 * take part; a participating cell contributes a whole number of those minima, so no package can ever receive a partial
 * amount below it; the order is the cache scan order the specification asks us to reuse; fluid is a separate concern and
 * never appears here.
 *
 * <p>A cell can never hold more than nine native stacks ({@code PACKAGE_SLOTS * stackSize}), so one line always fits a
 * single nine-slot package and lines are therefore never split across packages - which is what keeps every package's
 * per-cell minimum intact under Create's own packing.
 */
public final class ReturnPlan {
    private ReturnPlan() {}

    /** One cell as the scan sees it: {@code overage = amount - upper} (already clamped at zero by the caller). */
    public record Eligible(int slot, int overage, int batch) {}

    /** One line of the return list: how much of that cell this dispatch asks for, in item counts. */
    public record Line(int slot, int requested) {}

    public static List<Line> plan(List<Eligible> cells) {
        List<Line> lines = new ArrayList<>();
        if (cells == null) return lines;
        for (Eligible cell : cells) {
            if (cell == null || cell.slot() < 0) continue;
            int overage = cell.overage();
            int batch = cell.batch();
            // A cell that cannot make its own minimum is not part of this dispatch; it waits for a later one.
            if (overage <= 0 || batch < 1 || overage < batch) continue;
            int requested = overage - overage % batch;
            if (requested < batch) continue;
            lines.add(new Line(cell.slot(), requested));
        }
        return lines;
    }

    /** Total item count a plan requests, for accounting and for the package builder. */
    public static long total(List<Line> lines) {
        long sum = 0;
        for (Line line : lines) sum += line.requested();
        return sum;
    }
}
