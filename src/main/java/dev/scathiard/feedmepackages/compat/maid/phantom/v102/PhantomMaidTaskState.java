package dev.scathiard.feedmepackages.compat.maid.phantom.v102;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Additive ownership metadata only; no native routing, inventory or ticking behavior. */
public final class PhantomMaidTaskState {
    public static final String KEY = "fmp_maid_task";
    private CompoundTag data = new CompoundTag();

    public void remember(UUID task, UUID maid, PhantomMaidTaskMarker.Direction direction) {
        String[] values = PhantomMaidTaskMarker.encode(task, maid, direction);
        data.putString(PhantomMaidTaskMarker.KEY_TASK, values[0]);
        data.putString(PhantomMaidTaskMarker.KEY_MAID, values[1]);
        data.putString(PhantomMaidTaskMarker.KEY_DIRECTION, values[2]);
    }
    public PhantomMaidTaskMarker.Decoded marker(UUID task) {
        var decoded = PhantomMaidTaskMarker.decode(data.getString(PhantomMaidTaskMarker.KEY_TASK),
                data.getString(PhantomMaidTaskMarker.KEY_MAID), data.getString(PhantomMaidTaskMarker.KEY_DIRECTION));
        if (data.getBoolean("malformed") || decoded.marked() && !decoded.taskId().equals(task))
            return new PhantomMaidTaskMarker.Decoded(PhantomMaidTaskMarker.State.CORRUPT, null, null, null);
        return decoded;
    }
    public void write(CompoundTag tag) { if (!data.isEmpty()) tag.put(KEY, data.copy()); }
    public void read(CompoundTag tag) {
        data = tag.contains(KEY, Tag.TAG_COMPOUND) ? tag.getCompound(KEY).copy() : new CompoundTag();
        if (tag.contains(KEY) && !tag.contains(KEY, Tag.TAG_COMPOUND)) data.putBoolean("malformed", true);
    }
    public void clear() { data = new CompoundTag(); }
}
