package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.CellSettings;
import java.util.Objects;
import java.util.UUID;

/** Local settings draft, keyed by the real cell identity, never by its current screen position. */
public final class CellControlDraft {
    public record Context(UUID session, int slot, String filter, long filterRevision) {}
    private final Context context;
    private CellSettings value, submitted;
    private boolean stockDirty, supplyDirty, submittedStock, submittedAll;
    private int pending, due;
    public CellControlDraft(Context context, CellSettings current) { this.context = context; value = current; }
    public Context context() { return context; }
    public CellSettings value() { return value; }
    public boolean ready(int tick) { return pending == 0 && tick >= due && (stockDirty || supplyDirty); }
    public boolean stockNext() { return stockDirty; }
    public void changed(CellSettings next, int tick) {
        if (value.lower() != next.lower() || value.upper() != next.upper()) stockDirty = true;
        if (value.batch() != next.batch() || value.pendingLimit() != next.pendingLimit()) supplyDirty = true;
        value = next; due = tick + 4;
    }
    public void immediate() { due = 0; }
    public boolean dirty() { return stockDirty || supplyDirty; }
    public void sent(int sequence, boolean stock) { pending = sequence; submitted = value; submittedStock = stock; submittedAll=false; }
    public void sentAll(int sequence) { pending=sequence;submitted=value;submittedAll=true; }
    /** Settings commands are idempotent. A lost acknowledgement must not lock this cell forever. */
    public void timedOut(int sequence) { if(pending==sequence){pending=0;submitted=null;} }
    public void snapshot(CellSettings current, int acknowledged, boolean accepted) {
        if (pending != 0 && pending == acknowledged) {
            if (submittedAll) {
                if(value.lower()==submitted.lower() && value.upper()==submitted.upper()) stockDirty=false;
                if(value.batch()==submitted.batch() && value.pendingLimit()==submitted.pendingLimit()) supplyDirty=false;
            } else {
            boolean unchanged = submittedStock
                    ? value.lower() == submitted.lower() && value.upper() == submitted.upper()
                    : value.batch() == submitted.batch() && value.pendingLimit() == submitted.pendingLimit();
            if (unchanged) {
                if (submittedStock) stockDirty = false; else supplyDirty = false;
            }
            }
            pending = 0; submitted = null;
        }
        // Periodic inventory snapshots cannot pull an unsent or unacknowledged endpoint backwards.
        if (!stockDirty && !(pending != 0 && (submittedAll || submittedStock)))
            value = new CellSettings(current.lower(), current.upper(), value.batch(), value.pendingLimit());
        if (!supplyDirty && !(pending != 0 && (submittedAll || !submittedStock)))
            value = new CellSettings(value.lower(), value.upper(), current.batch(), current.pendingLimit());
    }

    /** Shift changes only the step, never rounds to a stack boundary. */
    public static int wheel(int value, int direction, int step, int min, int max, boolean unlimited) {
        if (direction == 0) return value;
        if (unlimited && value == -1) return direction < 0 ? max : -1;
        if (unlimited && value == max && direction > 0) return -1;
        return (int) Math.clamp((long) value + (direction > 0 ? step : -step), min, max);
    }
}
