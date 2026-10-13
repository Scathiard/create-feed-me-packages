package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.MaterialVariant;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One unresolved native handoff. Stock remains in the locked cache, not in a second spendable box.
 * An inventory carrier is held here; a cache carrier still belongs to its original cell. */
public record ReturnEscrow(UUID id, CacheHandle handle, long revision, int slot, MaterialVariant variant,
                           int amount, ItemStack carrier, int inventorySlot, int cacheSlot,
                           String transport, String address, boolean rejected, UUID maidOwner) {
    public ReturnEscrow(UUID id, CacheHandle handle, long revision, int slot, MaterialVariant variant,
                        int amount, ItemStack carrier, int inventorySlot, int cacheSlot,
                        String transport, String address, boolean rejected) {
        this(id, handle, revision, slot, variant, amount, carrier, inventorySlot, cacheSlot, transport, address, rejected, null);
    }
    public boolean maidSource() { return maidOwner != null; }
    public ReturnEscrow {
        Objects.requireNonNull(id); Objects.requireNonNull(handle); Objects.requireNonNull(variant);
        Objects.requireNonNull(carrier); Objects.requireNonNull(transport); Objects.requireNonNull(address);
        if (!(variant instanceof ItemVariantKey || variant instanceof FluidVariantKey)
                || revision < 0 || slot < 0 || amount < 1
                || (variant instanceof ItemVariantKey item && amount > 9L * item.stackSize())
                || carrier.isEmpty() || carrier.getCount() != 1 || inventorySlot < -1 || maidOwner == null && inventorySlot > 40 || cacheSlot < -1
                || (inventorySlot >= 0) == (cacheSlot >= 0) || !Set.of("paper", "bee", "phantom").contains(transport)
                || address.isEmpty() || address.length() > 256)
            throw new IllegalArgumentException("Invalid return escrow");
        carrier = carrier.copy();
    }
    @Override public ItemStack carrier() { return carrier.copy(); }
    public boolean fluid() { return variant instanceof FluidVariantKey; }
    public String encodedVariant() {
        return variant instanceof FluidVariantKey key ? key.encoded() : ((ItemVariantKey) variant).encoded();
    }
    public ReturnEscrow asRejected() {
        return new ReturnEscrow(id, handle, revision, slot, variant, amount, carrier, inventorySlot, cacheSlot, transport, address, true, maidOwner);
    }
    /** Also used while loading: an orphan or changed source cannot unlock as empty stock. */
    public CacheRecord settled(CacheRecord before, HolderLookup.Provider registries) {
        if (before == null || !before.state().id().equals(handle.cacheId()) || before.state().revision() != revision
                || before.owner() != null && !before.owner().equals(handle.playerId()))
            throw new IllegalStateException("Return escrow lost its original cache");
        var cargo = fluid() ? before.fluids().state() : before.state();
        if (slot >= cargo.cells().size() || !variant.equals(cargo.cells().get(slot).filter()))
            throw new IllegalStateException("Return escrow lost its original cargo variant");
        var edit = before.state().edit();
        var fluidEdit = before.fluids().state().edit();
        if ((fluid() ? fluidEdit.extract(slot, amount) : edit.extract(slot, amount)) != amount)
            throw new IllegalStateException("Return escrow lost its cargo");
        if (cacheSlot >= 0 && (cacheSlot >= before.state().cells().size()
                || !ItemVariantKey.of(carrier, registries).equals(before.state().cells().get(cacheSlot).filter())
                || edit.extract(cacheSlot, 1) != 1)) throw new IllegalStateException("Return escrow lost its carrier");
        CacheRecord next;
        if (!fluid()) next = before.withState(edit.finish());
        else {
            var fluids = before.fluids().withState(fluidEdit.finish());
            next = cacheSlot >= 0 ? before.withStates(edit.finish(), fluids) : before.withFluids(fluids);
        }
        return next;
    }
    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag(); tag.putInt("version", 2); tag.putUUID("id", id);
        tag.putUUID("cache", handle.cacheId()); tag.putUUID("player", maidSource() ? maidOwner : handle.playerId());
        if (maidSource()) tag.putUUID("maid", handle.playerId());
        if (handle.networkId() != null) tag.putUUID("network", handle.networkId());
        tag.putLong("revision", revision); tag.putInt("slot", slot);
        // Existing item rows are unchanged. Fluid identity has its own additional column, never an item template.
        tag.putString(fluid() ? "fluid_variant" : "variant", encodedVariant());
        tag.putInt("amount", amount); tag.put("carrier", carrier.save(registries));
        tag.putInt("inventory_slot", inventorySlot); tag.putInt("cache_slot", cacheSlot);
        tag.putString("transport", transport); tag.putString("address", address); tag.putBoolean("rejected", rejected);
        return tag;
    }
    static ReturnEscrow load(CompoundTag tag, HolderLookup.Provider registries) {
        var allowed = Set.of("version", "id", "cache", "player", "network", "revision", "slot", "variant", "fluid_variant", "amount",
                "carrier", "inventory_slot", "cache_slot", "transport", "address", "rejected", "maid");
        if (!allowed.containsAll(tag.getAllKeys())) throw new IllegalArgumentException("Unknown return escrow fields");
        require(tag, "version", Tag.TAG_INT);
        if (tag.getInt("version") != 2) throw new IllegalArgumentException("Unknown return escrow version");
        for (String key : Set.of("id", "cache", "player")) require(tag, key, Tag.TAG_INT_ARRAY);
        if (tag.contains("network")) require(tag, "network", Tag.TAG_INT_ARRAY);
        if (tag.contains("maid")) require(tag, "maid", Tag.TAG_INT_ARRAY);
        for (String key : Set.of("slot", "amount", "inventory_slot", "cache_slot")) require(tag, key, Tag.TAG_INT);
        if (tag.contains("fluid_variant") == tag.contains("variant"))
            throw new IllegalArgumentException("Return escrow must name exactly one cargo kind");
        String variantField = tag.contains("fluid_variant") ? "fluid_variant" : "variant";
        for (String key : Set.of(variantField, "transport", "address")) require(tag, key, Tag.TAG_STRING);
        require(tag, "revision", Tag.TAG_LONG); require(tag, "carrier", Tag.TAG_COMPOUND); require(tag, "rejected", Tag.TAG_BYTE);
        if (tag.getByte("rejected") != 0 && tag.getByte("rejected") != 1) throw new IllegalArgumentException("Invalid rejection state");
        var carrier = ItemStack.parse(registries, tag.getCompound("carrier")).orElseThrow();
        ItemVariantKey.of(carrier, registries);
        return new ReturnEscrow(tag.getUUID("id"), new CacheHandle(tag.getUUID("cache"), tag.getUUID(tag.contains("maid") ? "maid" : "player"),
                tag.contains("network") ? tag.getUUID("network") : null), tag.getLong("revision"), tag.getInt("slot"),
                tag.contains("fluid_variant") ? FluidVariantKey.decode(tag.getString("fluid_variant"), registries)
                        : ItemVariantKey.decode(tag.getString("variant"), registries),
                tag.getInt("amount"),
                carrier, tag.getInt("inventory_slot"), tag.getInt("cache_slot"), tag.getString("transport"), tag.getString("address"),
                tag.getBoolean("rejected"), tag.contains("maid") ? tag.getUUID("player") : null);
    }
    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or mistyped return escrow field: " + key);
    }
}
