package dev.scathiard.feedmepackages.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Test-only observation and native recovery of author-owned graves; never edits their stored loot. */
final class DeathRecovery {
    private DeathRecovery() {}

    static ServerPlayer respawn(net.minecraft.gametest.framework.GameTestHelper helper, ServerPlayer original) {
        var revived = TestPlayers.real(helper, original.getUUID()); revived.restoreFrom(original, false);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(revived, false));
        return revived;
    }

    static List<BlockEntity> graves(ServerPlayer player) {
        if (!ModList.get().isLoaded("gravestone") && !ModList.get().isLoaded("tombstone")) return List.of();
        var result = new ArrayList<BlockEntity>();
        for (var pos : BlockPos.betweenClosed(player.blockPosition().offset(-6, -6, -6), player.blockPosition().offset(6, 6, 6))) {
            var block = player.serverLevel().getBlockEntity(pos);
            if (block == null) continue;
            if (block.getClass().getName().equals("de.maxhenkel.gravestone.tileentity.GraveStoneTileEntity")) {
                if (player.getUUID().equals(call(call(block, "getDeath"), "getPlayerUUID"))) result.add(block);
            } else if (block.getClass().getName().equals("ovh.corail.tombstone.block.entity.BlockEntityPlayerGrave")
                    && (boolean) call(block, "isOwner", new Class<?>[]{net.minecraft.world.entity.player.Player.class}, player)) result.add(block);
        }
        if (ModList.get().isLoaded("tombstone")) {
            try {
                var handler = Class.forName("ovh.corail.tombstone.helper.DeathHandler").getField("INSTANCE").get(null);
                var location = call(handler, "getLastGrave", new Class<?>[]{net.minecraft.server.MinecraftServer.class, ServerPlayer.class},
                        player.getServer(), player);
                if (location != null && (boolean) call(location, "isSameDimension", new Class<?>[]{net.minecraft.world.level.Level.class}, player.serverLevel())) {
                    var block = player.serverLevel().getBlockEntity((BlockPos) call(location, "getPos"));
                    if (block != null && !result.contains(block) && block.getClass().getName().equals("ovh.corail.tombstone.block.entity.BlockEntityPlayerGrave")
                            && (boolean) call(block, "isOwner", new Class<?>[]{net.minecraft.world.entity.player.Player.class}, player)) result.add(block);
                }
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        }
        return result;
    }

    static List<Entity> corpses(ServerPlayer player) {
        if (!ModList.get().isLoaded("corpse")) return List.of();
        return player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(8), entity ->
                entity.getClass().getName().equals("de.maxhenkel.corpse.entities.CorpseEntity")
                        && player.getUUID().equals(call(call(entity, "getDeath"), "getPlayerUUID")));
    }

    static List<Entity> totems(ServerPlayer player) {
        if (!ModList.get().isLoaded("quark")) return List.of();
        return player.serverLevel().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(8), entity ->
                entity.getClass().getName().equals("org.violetmoon.quark.addons.oddities.entity.TotemOfHoldingEntity")
                        && player.getUUID().toString().equals(entity.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getString("owner")));
    }

    private static List<ItemStack> totemItems(Entity totem) {
        var data = totem.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        // curiosList is an equipment-restoration index into storedItems, not another copy of the goods.
        return data.getList("storedItems", net.minecraft.nbt.Tag.TAG_COMPOUND).stream()
                .map(tag -> ItemStack.parse(totem.registryAccess(), tag).orElseThrow()).toList();
    }

    /** Only live grave contents, not the author's administrative death-history backups. */
    @SuppressWarnings("unchecked")
    static List<ItemStack> storedItems(ServerPlayer player) {
        var result = new ArrayList<ItemStack>();
        for (var grave : graves(player)) {
            if (grave.getClass().getName().startsWith("de.maxhenkel.")) {
                result.addAll((List<ItemStack>) call(call(grave, "getDeath"), "getAllItems"));
            } else {
                var inventory = (IItemHandler) call(grave, "getInventory");
                for (int i = 0; i < inventory.getSlots(); i++) result.add(inventory.getStackInSlot(i));
            }
        }
        for (var corpse : corpses(player)) result.addAll((List<ItemStack>) call(call(corpse, "getDeath"), "getAllItems"));
        for (var totem : totems(player)) result.addAll(totemItems(totem));
        return result;
    }

    static List<ItemEntity> drops(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8));
    }

    static List<ItemStack> loot(ServerPlayer player) {
        var result = new ArrayList<>(storedItems(player));
        for (var drop : drops(player)) result.add(drop.getItem());
        for (var parcel : parcels(player)) result.add(parcel.getBox());
        return result;
    }

    static List<com.simibubi.create.content.logistics.box.PackageEntity> parcels(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(com.simibubi.create.content.logistics.box.PackageEntity.class, player.getBoundingBox().inflate(8));
    }

    /** Native break/use and the real corpse menu's transfer packet, not copied inventory-transfer logic. */
    static void recover(ServerPlayer player) {
        boolean sneak = player.isShiftKeyDown();
        try {
            player.setShiftKeyDown(true);
            for (var grave : graves(player)) {
                var pos = grave.getBlockPos(); var state = player.serverLevel().getBlockState(pos);
                if (grave.getClass().getName().startsWith("ovh.corail.")) {
                    // This fixture has no world ticks between death and clone. Advance the native age gate.
                    for (int i = 0; i < 31; i++) callStatic(grave.getClass(), "serverTick",
                            new Class<?>[]{net.minecraft.world.level.Level.class, BlockPos.class,
                                    net.minecraft.world.level.block.state.BlockState.class, grave.getClass()},
                            player.serverLevel(), pos, state, grave);
                    state.useWithoutItem(player.serverLevel(), player,
                            new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
                                    net.minecraft.core.Direction.UP, pos, false));
                } else if (!player.gameMode.destroyBlock(pos)) throw new IllegalStateException("Native grave breaking refused");
            }
            for (var corpse : corpses(player)) {
                corpse.interact(player, InteractionHand.MAIN_HAND);
                if (!player.containerMenu.getClass().getName().startsWith("de.maxhenkel.corpse.gui."))
                    throw new IllegalStateException("Native corpse interaction did not open its menu");
                corpsePacket(player, "MessageTransferItems");
                player.closeContainer();
            }
            for (var totem : totems(player)) {
                int before = totemItems(totem).stream().mapToInt(ItemStack::getCount).sum();
                for (int hit = 0; hit < 64 && !totemItems(totem).isEmpty(); hit++) player.attack(totem);
                if (!totemItems(totem).isEmpty()) throw new IllegalStateException("Native Quark totem attack did not recover its contents");
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_QUARK_NATIVE_TOTEM_RECOVERY owner={} itemsBefore={} remaining=0",
                        player.getUUID(), before);
            }
        } finally { player.setShiftKeyDown(sneak); }
    }

    static void takePendant(ServerPlayer player, java.util.UUID marker) {
        for (var corpse : corpses(player)) {
            corpse.interact(player, InteractionHand.MAIN_HAND);
            corpsePacket(player, "MessageOpenAdditionalItems");
            for (var slot : player.containerMenu.slots) {
                if (slot.container != player.getInventory()
                        && marker.equals(slot.getItem().get(dev.scathiard.feedmepackages.registry.FmpRegistries.NETWORK.get()))) {
                    player.containerMenu.clicked(slot.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
                    break;
                }
            }
            player.closeContainer();
        }
    }

    private static void corpsePacket(ServerPlayer player, String name) {
        try {
            var message = Class.forName("de.maxhenkel.corpse.net." + name).getConstructor().newInstance();
            var context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[]{IPayloadContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("player")) return player;
                        throw new IllegalStateException("Unexpected corpse payload context: " + method);
                    });
            call(message, "executeServerSide", new Class<?>[]{IPayloadContext.class}, context);
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }

    static Object call(Object target, String method) { return call(target, method, new Class<?>[0]); }
    static Object call(Object target, String method, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Native grave entry " + method, exception); }
    }
    private static Object callStatic(Class<?> target, String method, Class<?>[] types, Object... args) {
        try { return target.getMethod(method, types).invoke(null, args); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Native grave tick " + method, exception); }
    }
}
