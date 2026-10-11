package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Optional native transport endpoints. We own neither a flight queue nor a replacement carrier. */
public final class MaidCouriers {
    private MaidCouriers() {}
    private static Paper paper;
    private static Bee bee;
    private static boolean initialized;
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private record Paper(Constructor<?> target, Method register, Method getTarget, Method getBox,
                         Method position, Method dimension, Field entity, Field targetPosition, Field targetDimension) {}
    private record Bee(Class<?> targetType, Method level, Method address, Method box, Method network,
                       Method getTarget, Field target, Method position, Method setBox, Method invalidate,
                       Method members, Method inRange, Method portNetwork, Method sendDrone) {}
    public static void initialize() {
        if (initialized) return; initialized = true;
        if (ModList.get().isLoaded("cmpackagecouriers")) try {
            var target = Class.forName("com.kreidev.cmpackagecouriers.CourierTarget");
            var plane = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlane");
            if (dev.scathiard.feedmepackages.mixin.OptionalMixins.maidPaperCompatible())
                paper = new Paper(target.getConstructor(String.class, net.minecraft.world.entity.Entity.class),
                        target.getMethod("addOrUpdateTarget", target), plane.getMethod("getTarget"), plane.getMethod("getPackage"),
                        plane.getMethod("getPos"), plane.getMethod("getCurrentDim"), field(target, "entity"), field(target, "pos"), field(target, "dim"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            FeedMePackages.LOGGER.warn("FMP direct maid paper delivery disabled: native endpoint unavailable", incompatible);
        }
        if (ModList.get().isLoaded("create_mobile_packages")) try {
            String root = "de.theidler.create_mobile_packages.";
            var robo = Class.forName(root + "robo.VirtualRobo");
            var port = Class.forName(root + "blocks.bee_port.BeePortBlockEntity");
            var send = port.getDeclaredMethod("sendDrone", ItemStack.class, int.class); send.setAccessible(true);
            // Load the delivery controller too, so all required mixin ports have been applied before enabling.
            Class.forName(root + "entities.robo_entity.RoboBeeBehaviorController");
            if (dev.scathiard.feedmepackages.mixin.OptionalMixins.maidBeeCompatible())
                bee = new Bee(Class.forName(root + "robo.RoboTarget"), robo.getMethod("getServerLevel"),
                        robo.getMethod("getTargetAddress"), robo.getMethod("getItemStack"), robo.getMethod("getLogisticsNetworkId"),
                        robo.getMethod("getTarget"), field(robo, "target"), robo.getMethod("getCurrentPos"),
                        robo.getMethod("setItemStack", ItemStack.class), robo.getMethod("invalidateTarget"),
                        Class.forName(root + "network_settings.NetworkHelper").getMethod("getPlayerUUIDs", UUID.class),
                        Class.forName(root + "CMPHelper").getMethod("isWithinRange", Level.class, BlockPos.class, BlockPos.class),
                        port.getMethod("getLogisticsNetworkId"), send);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            FeedMePackages.LOGGER.warn("FMP direct maid bee delivery disabled: native endpoint unavailable", incompatible);
        }
    }
    public static boolean paperReady() { return paper != null; }
    public static boolean beeReady() { return bee != null; }
    public static void refresh(LivingEntity maid) {
        if (paper == null || maid.tickCount % 10 != 0) return;
        try { paper.register.invoke(null, paper.target.newInstance(MaidAccess.address(maid), maid)); }
        catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
    private static ParcelSeal seal(ItemStack box) {
        return box.get(FmpRegistries.PARCEL_SEAL.get());
    }
    private static LivingEntity destination(MinecraftServer server, ItemStack box, UUID network, boolean authenticate) {
        var seal = seal(box); if (seal == null || box.isEmpty()) return null;
        var maid = MaidAccess.find(server, seal.address()); if (maid == null || !seal.playerId().equals(maid.getUUID())) return null;
        var access = MaidAccess.resolve(maid);
        if (!access.active() || !access.handle().cacheId().equals(seal.cacheId())
                || network != null && !network.equals(access.handle().networkId())) return null;
        if (authenticate) {
            var ledger = CacheLedger.get(server);
            boolean valid = ParcelAuthentication.valid(ledger, maid.registryAccess(), box, seal);
            if (!valid) return null;
        }
        return maid;
    }
    /** Rebind the author's position-only saved target to the same loaded maid after a server restart. */
    public static void paperTick(Object plane, MinecraftServer server) {
        if (paper == null) return;
        try {
            var maid = destination(server, (ItemStack) paper.getBox.invoke(plane), null, false);
            if (maid == null) return;
            Object target = paper.getTarget.invoke(plane);
            paper.entity.set(target, maid); paper.targetPosition.set(target, maid.getEyePosition());
            paper.targetDimension.set(target, maid.level().dimension());
        } catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
    public static boolean paperArrive(Object plane, MinecraftServer server) {
        if (paper == null) return false;
        try {
            var box = (ItemStack) paper.getBox.invoke(plane); var maid = destination(server, box, null, true);
            if (maid == null || !maid.level().dimension().equals(paper.dimension.invoke(plane))
                    || maid.getEyePosition().distanceToSqr((Vec3) paper.position.invoke(plane)) > 2.25) return false;
            if (!ReceiveService.receive(MaidAccess.endpoint(maid), box)) return false;
            var level = (ServerLevel) maid.level();
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
                    com.simibubi.create.AllItems.CARDBOARD.asStack()), maid.getX(), maid.getEyeY(), maid.getZ(), 20, .3, .3, .3, .1);
            level.playSound(null, maid.blockPosition(), net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST.value(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1, .75f);
            return true;
        } catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
    private static boolean member(LivingEntity maid, UUID network) throws ReflectiveOperationException {
        return maid instanceof TamableAnimal pet && pet.getOwnerUUID() != null
                && ((Set<?>) bee.members.invoke(null, network)).contains(pet.getOwnerUUID());
    }
    private static LivingEntity beeDestination(Object robo, boolean authenticate) throws ReflectiveOperationException {
        var level = (ServerLevel) bee.level.invoke(robo); var network = (UUID) bee.network.invoke(robo);
        var maid = destination(level.getServer(), (ItemStack) bee.box.invoke(robo), network, authenticate);
        return maid != null && maid.level() == level && member(maid, network) ? maid : null;
    }
    public static boolean beeTarget(Object robo) {
        if (bee == null) return false;
        try {
            var maid = beeDestination(robo, false); if (maid == null) return false;
            Object old = bee.getTarget.invoke(robo);
            if (old != null && Proxy.isProxyClass(old.getClass()) && Proxy.getInvocationHandler(old) instanceof MaidTarget target
                    && target.maid == maid) return true;
            Object target = Proxy.newProxyInstance(bee.targetType.getClassLoader(), new Class<?>[]{bee.targetType}, new MaidTarget(maid));
            bee.target.set(robo, target); return true;
        } catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
    private static final class MaidTarget implements InvocationHandler {
        final LivingEntity maid; int eta;
        MaidTarget(LivingEntity maid) { this.maid = maid; }
        public Object invoke(Object proxy, Method method, Object[] args) throws ReflectiveOperationException {
            return switch (method.getName()) {
                case "getTargetPos" -> maid.position();
                case "asPlayer", "asBeePortBlockEntity", "asBlockPos" -> null;
                case "isValid" -> beeDestination(args[0], false) == maid;
                case "setETA" -> { eta = (int) args[0]; yield null; }
                case "getETA" -> eta;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FMP maid target " + maid.getUUID();
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }
    public static boolean beeSend(Object port, ItemStack box, int slot) {
        if (bee == null || !(port instanceof BlockEntity block) || !(block.getLevel() instanceof ServerLevel level)) return false;
        try {
            UUID network = (UUID) bee.portNetwork.invoke(port);
            var maid = destination(level.getServer(), box, network, true);
            if (maid == null || maid.level() != level || !member(maid, network)
                    || !(boolean) bee.inRange.invoke(null, level, maid.blockPosition(), block.getBlockPos())) return false;
            // This native method owns carrier consumption, missing-bee requests, cooldown and removing cargo.
            bee.sendDrone.invoke(port, box, slot); return true;
        } catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
    public static void beeArrive(Object robo) {
        if (bee == null) return;
        try {
            var maid = beeDestination(robo, true);
            if (maid == null || maid.position().add(0, 2, 0).distanceToSqr((Vec3) bee.position.invoke(robo)) > 2.25) return;
            var box = (ItemStack) bee.box.invoke(robo);
            if (!ReceiveService.receive(MaidAccess.endpoint(maid), box)) return;
            bee.setBox.invoke(robo, ItemStack.EMPTY); bee.invalidate.invoke(robo);
            // Continue the original delivery method: empty cargo selects the native return/home-port path.
        } catch (ReflectiveOperationException broken) { throw MaidCompatibility.failure(broken); }
    }
}
