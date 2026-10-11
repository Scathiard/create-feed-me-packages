package dev.scathiard.feedmepackages.compat.maid;

import com.simibubi.create.Create;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.logistics.LogisticsEndpoint;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.*;

/** Ordinary physical pendants only. A maid never claims or degrades a player's personal key. */
public final class MaidAccess {
    private MaidAccess() {}
    public static final String ADDRESS_PREFIX = "FMP-Maid/";
    private static AccessGate.Result unavailable(AccessGate.Status status) {
        return new AccessGate.Result(status, null, List.of());
    }
    public static AccessGate.Result resolve(LivingEntity maid) {
        if (!MaidCompatibility.enabled() || !MaidCompatibility.isMaid(maid)
                || !(maid instanceof TamableAnimal pet) || !pet.isTame() || pet.getOwnerUUID() == null
                || !(maid.level() instanceof ServerLevel level) || !maid.isAlive() || maid.isRemoved()
                || level.getEntity(maid.getUUID()) != maid) return unavailable(AccessGate.Status.NOT_WORN);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Maid access outside server thread");
        var handler = CuriosApi.getCuriosInventory(maid).orElse(null);
        if (handler == null) return unavailable(AccessGate.Status.NOT_WORN);
        var found = handler.findCurios(s -> s.getItem() instanceof PendantItem).stream()
                .filter(entry -> !entry.slotContext().cosmetic()).toList();
        if (found.isEmpty()) return unavailable(AccessGate.Status.NOT_WORN);
        if (found.size() != 1) return unavailable(AccessGate.Status.MULTIPLE_TERMINALS);
        var entry = found.getFirst(); var context = entry.slotContext(); var stack = entry.stack();
        if (!context.identifier().equals("necklace") || !handler.isSlotActive("necklace", context.index())
               ) return unavailable(AccessGate.Status.NOT_WORN);
        if (stack.getCount() != 1 || stack.getOrDefault(FmpRegistries.PREVIEW.get(), false))
            return unavailable(AccessGate.Status.INVALID_IDENTITY);
        var ledger = CacheLedger.get(level.getServer());
        if (!ledger.problem().isEmpty()) return unavailable(AccessGate.Status.STORAGE_LOCKED);
        UUID id = stack.get(FmpRegistries.IDENTITY.get());
        if (id == null) { id = ledger.createOrdinary(); stack.set(FmpRegistries.IDENTITY.get(), id); }
        var record = ledger.find(id);
        if (!ledger.ordinaryEntry(id, stack.get(FmpRegistries.RETIREMENT_RECEIPT.get()))) return unavailable(AccessGate.Status.INVALID_IDENTITY);
        var handle = new CacheHandle(id, maid.getUUID(), stack.get(FmpRegistries.NETWORK.get()));
        var hold = ledger.returnEscrow(id);
        if (hold != null) return unavailable(hold.rejected() ? AccessGate.Status.RETURN_REFUND : AccessGate.Status.RETURN_HELD);
        return new AccessGate.Result(AccessGate.Status.ACTIVE, handle,
                List.of(new AccessGate.Terminal(context.index(), stack, id, handle.networkId(), false)));
    }
    public static String address(LivingEntity maid) { return ADDRESS_PREFIX + maid.getUUID(); }
    public static UUID recipient(String address) {
        if (address == null || !address.startsWith(ADDRESS_PREFIX)) return null;
        try {
            UUID id = UUID.fromString(address.substring(ADDRESS_PREFIX.length()));
            return address.equals(ADDRESS_PREFIX + id) ? id : null;
        } catch (IllegalArgumentException malformed) { return null; }
    }
    public static LivingEntity find(MinecraftServer server, String address) {
        UUID id = recipient(address); if (id == null || !server.isSameThread()) return null;
        for (ServerLevel level : server.getAllLevels())
            if (level.getEntity(id) instanceof LivingEntity maid && resolve(maid).active()) return maid;
        return null;
    }
    public static LogisticsEndpoint endpoint(LivingEntity maid) {
        return new LogisticsEndpoint() {
            public LivingEntity entity() { return maid; }
            public AccessGate.Result access() { return resolve(maid); }
            public String address() { return MaidAccess.address(maid); }
            public boolean mayRequest(UUID network) {
                if (!(maid instanceof TamableAnimal pet) || pet.getOwnerUUID() == null) return false;
                var state = Create.LOGISTICS.logisticsNetworks.get(network);
                // Same UUID policy as GlobalLogisticsManager.mayInteract; no online or fake player required.
                return state == null || state.owner == null || !state.locked || state.owner.equals(pet.getOwnerUUID());
            }
        };
    }
}
