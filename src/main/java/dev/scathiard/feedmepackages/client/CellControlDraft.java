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
        if (value.batch() != next.batch() || value.returnBatch() != next.returnBatch()) supplyDirty = true;
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
                if(value.batch()==submitted.batch() && value.returnBatch()==submitted.returnBatch()) supplyDirty=false;
            } else {
            boolean unchanged = submittedStock
                    ? value.lower() == submitted.lower() && value.upper() == submitted.upper()
                    : value.batch() == submitted.batch() && value.returnBatch() == submitted.returnBatch();
            if (unchanged) {
                if (submittedStock) stockDirty = false; else supplyDirty = false;
            }
            }
            pending = 0; submitted = null;
        }
        // Periodic inventory snapshots cannot pull an unsent or unacknowledged endpoint backwards.
        if (!stockDirty && !(pending != 0 && (submittedAll || submittedStock)))
            value = new CellSettings(current.lower(), current.upper(), value.batch(), current.pendingLimit(), value.returnBatch());
        if (!supplyDirty && !(pending != 0 && (submittedAll || !submittedStock)))
            value = new CellSettings(value.lower(), value.upper(), current.batch(), current.pendingLimit(), current.returnBatch());
    }

    /** Top-row batches may widen the interval, never contract it or toggle either direction off. */
    public static CellSettings adjust(CellSettings s, int field, int requested, int capacity) {
        int lower=s.lower(), upper=s.upper(), batch=s.batch(), returns=s.returnBatch();
        switch (field) {
            case 0 -> {
                batch=Math.max(1,requested);
                if(lower>0 && upper>=0) {
                    int room=capacity-lower;
                    if(room<1 || returns>room)return s;
                    batch=Math.min(batch,room);
                    upper=Math.max(upper,lower+Math.max(batch,returns));
                }
            }
            case 1 -> {
                returns=Math.max(1,requested);
                if(lower>0 && upper>=0) {
                    int room=upper-1;
                    if(room<1 || batch>room)return s;
                    returns=Math.min(returns,room);
                    lower=Math.min(lower,upper-Math.max(batch,returns));
                }
            }
            case 2 -> {
                lower=requested<=0?-1:Math.min(requested,capacity);
                if(lower>0 && upper>=0) {
                    int ceiling=upper-Math.max(batch,returns);
                    if(ceiling<1)return s;
                    lower=Math.min(lower,ceiling);
                }
            }
            case 3 -> {
                upper=requested<0?-1:Math.min(requested,capacity);
                if(upper>=0 && lower>0) {
                    long floor=(long)lower+Math.max(batch,returns);
                    if(floor>capacity)return s;
                    upper=Math.max(upper,(int)floor);
                }
            }
            default -> throw new IllegalArgumentException("Unknown cell control");
        }
        return new CellSettings(lower,upper,batch,s.pendingLimit(),returns);
    }

    /** Item scrolls align along their direction; a full stack step follows an aligned value. */
    public static int wheel(int value, int direction, int step, int min, int max, boolean unlimited) {
        return wheel(value, direction, step, min, max, unlimited, true);
    }
    /** Fluid wells keep their existing fixed-volume step, without item-stack alignment. */
    public static int wheel(int value, int direction, int step, int min, int max, boolean unlimited, boolean align) {
        if (step < 1 || min > max) throw new IllegalArgumentException("Invalid numeric well");
        if (direction == 0) return value;
        if (unlimited && value == -1) return direction < 0 ? max : -1;
        if (unlimited && value == max && direction > 0) return -1;
        long next = align
                ? (direction > 0 ? Math.floorDiv((long) value, step) + 1
                                 : Math.floorDiv((long) value - 1, step)) * step
                : (long) value + (direction > 0 ? step : -step);
        return (int) Math.clamp(next, min, max);
    }
}
