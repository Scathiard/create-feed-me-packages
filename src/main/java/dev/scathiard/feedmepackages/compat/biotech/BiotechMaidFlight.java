package dev.scathiard.feedmepackages.compat.biotech;

import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.UUID;
import static dev.scathiard.feedmepackages.compat.biotech.BiotechCompatibility.*;

/** Adds a loaded maid endpoint to native flights; their movement and failure clocks stay native. */
public final class BiotechMaidFlight {
    private BiotechMaidFlight() {}
    private static final String TAG = "fmp_maid_flight";
    public static void save(BiotechTaskLink link, CompoundTag tag) {
        if (link.fmp$owner() == null || link.fmp$source() == null && link.fmp$target() == null) return;
        var own = new CompoundTag(); own.putUUID("owner", link.fmp$owner());
        if (link.fmp$source() != null) own.putUUID("source", link.fmp$source());
        if (link.fmp$target() != null) own.putUUID("target", link.fmp$target());
        tag.put(TAG, own);
    }
    public static void load(BiotechTaskLink link, CompoundTag tag) {
        var own = tag.getCompound(TAG);
        if (!own.hasUUID("owner")) return;
        link.fmp$owner(own.getUUID("owner"));
        link.fmp$source(own.hasUUID("source") ? own.getUUID("source") : null);
        link.fmp$target(own.hasUUID("target") ? own.getUUID("target") : null);
    }
    private static LivingEntity find(MinecraftServer server, UUID id, UUID owner) {
        if (server == null || id == null || owner == null) return null;
        for (var level : server.getAllLevels()) {
            var maid = MaidReturn.find(level, id);
            if (maid instanceof TamableAnimal pet && owner.equals(pet.getOwnerUUID())) return maid;
        }
        return null;
    }
    public static boolean targetsMaid(Object task) {
        return ready() && task instanceof BiotechTaskLink link && link.fmp$target() != null;
    }
    private static LivingEntity target(Object task, MinecraftServer server) {
        var link = (BiotechTaskLink)task;
        var maid = find(server, link.fmp$target(), link.fmp$owner());
        if (maid == null) return null;
        var current = server.getLevel((ResourceKey<Level>)get(task, "currentDimension"));
        if (current == null || !(boolean)call("canTarget", null, current, maid.level().dimension())) return null;
        if (get(task, "mission").toString().equals("PACKAGE_TO_PLAYER")) {
            // Arrival repeats full parcel authentication; flight tracking only checks the same worn cache identity.
            var box = (ItemStack)get(task, "box");
            var seal = box.has(dev.scathiard.feedmepackages.registry.FmpRegistries.FLUID_PARCEL_SEAL.get())
                    ? box.get(dev.scathiard.feedmepackages.registry.FmpRegistries.FLUID_PARCEL_SEAL.get())
                    : box.get(dev.scathiard.feedmepackages.registry.FmpRegistries.PARCEL_SEAL.get());
            var access = MaidAccess.resolve(maid);
            if (seal == null || !access.active() || !seal.playerId().equals(maid.getUUID()) || !seal.cacheId().equals(access.handle().cacheId())) return null;
        }
        return maid;
    }
    public static Object resolve(Object task, MinecraftServer server) {
        var maid = target(task, server);
        if (maid == null) return null;
        set(task, "targetDimension", maid.level().dimension());
        return resolved((ServerLevel)maid.level());
    }
    public static Vec3 position(Object task, boolean approach) {
        var maid = target(task, ServerLifecycleHooks.getCurrentServer());
        if (maid == null) return (Vec3)get(task, "position");
        var look = maid.getLookAngle().multiply(1, 0, 1).normalize();
        return maid.position().add(look.scale(.15)).add(0, approach ? 1.8 : 1.2, 0);
    }
    public static boolean reached(Object task, Vec3 landing) {
        var maid = target(task, ServerLifecycleHooks.getCurrentServer());
        var position = (Vec3)get(task, "position");
        return maid != null && (maid.getBoundingBox().inflate(.45, .6, .45).contains(position) || position.distanceTo(landing) <= 1.5);
    }
    public static Object deliver(Object task, MinecraftServer server, ServerLevel current, Vec3 landing) {
        var maid = target(task, server);
        var box = (ItemStack)get(task, "box"); var mission = get(task, "mission");
        if (maid == null) { call("fail", null, box, mission, current, landing); return delivered(false, false); }
        if (mission.toString().equals("CARRIER_RETURN_TO_PLAYER")) {
            if (!MaidCompatibility.returnCarrier(maid, carrier())) call("dropCarrier", null, current, landing);
            return delivered(false, true);
        }
        if (!ReceiveService.receive(MaidAccess.endpoint(maid), box.copy())) {
            call("fail", null, box, mission, current, landing); return delivered(false, false);
        }
        String mode = get(task, "returnMode").toString();
        if (mode.equals("ALWAYS_RETURN")) return delivered(true, true);
        boolean docked = MaidCompatibility.returnCarrier(maid, carrier());
        if (mode.equals("RETURN_WHEN_UNABLE")) return delivered(!docked, true);
        if (!docked) call("dropCarrier", null, current, landing);
        return delivered(false, true);
    }
    /** Only changes a maid-origin return recipient. The native reset/departure logic still performs the turn. */
    public static boolean startReturn(Object task, MinecraftServer server) {
        var link = (BiotechTaskLink)task;
        link.fmp$target(null);
        if (link.fmp$source() == null) return false; // A port-origin flight returns to its actual port normally.
        var maid = find(server, link.fmp$source(), link.fmp$owner());
        var current = server.getLevel((ResourceKey<Level>)get(task, "currentDimension"));
        if (maid == null || current == null || !(boolean)call("canTarget", null, current, maid.level().dimension())) {
            call("dropCarrier", null, current, get(task, "position")); call("remove", task); return true;
        }
        var departure = call("targetPort", task, current);
        link.fmp$target(maid.getUUID());
        set(task, "targetAllayPortPos", null); set(task, "targetAllayPortSubLevelId", null);
        set(task, "targetPlayerId", null); set(task, "targetDimension", maid.level().dimension());
        call("reset", task, returnMission()); call("depart", task, departure, true);
        return true;
    }
}
