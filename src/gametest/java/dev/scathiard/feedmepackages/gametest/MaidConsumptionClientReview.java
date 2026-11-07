package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Packaged native consumption in an integrated world, including an actual save/leave/reopen cycle. */
final class MaidConsumptionClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int phase, ticks, changed, maidId;
    private static UUID maidUuid, cache;
    private static CompletableFuture<Void> work;
    private static boolean reopening;
    @FunctionalInterface private interface Action { void run(ServerPlayer player) throws ReflectiveOperationException; }
    static boolean tick() throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("touhou_little_maid")) return true;
        if (reopening) return false;
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 1200, "Maid consumption client timeout phase=" + phase);
        switch (phase) {
            case 0 -> { MC.player.closeContainer(); MC.setScreen(null); server(MaidConsumptionClientReview::setup); next(); }
            case 1 -> {
                if (MC.level.getEntity(maidId) == null || ticks - changed < 15) return false;
                server(p -> {
                    var maid = maid(p); MaidPickup.setEnabled(maid, true);
                    maid.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW, 7));
                    var ground = new ItemEntity(p.level(), maid.getX(), maid.getY(), maid.getZ(), new ItemStack(Items.ARROW, 3));
                    ground.setNoPickUpDelay(); p.level().addFreshEntity(ground);
                    require((boolean)maid.getClass().getMethod("pickupItem", ItemEntity.class, boolean.class).invoke(maid, ground, false)
                            && stock(p, 1) == 7 && maid.getOffhandItem().getCount() == 7, "Packaged offhand pickup moved old items or missed the new three");
                    maid.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                    meal(maid, p.serverLevel());
                    require(maid.isUsingItem() && stock(p, 0) == 5 && maid.getOffhandItem().is(Items.BREAD)
                            && maid.getOffhandItem().getCount() == 1, "Packaged full-bag meal did not transfer one food");
                    maid.stopUsingItem();
                }); next();
            }
            case 2 -> {
                if (ticks - changed < 15) return false;
                TemplateClientReview.capture("maid-full-bag-interrupted-food"); next(); reopening = true;
                try { MC.level.disconnect(); MC.disconnect(new net.minecraft.client.gui.screens.TitleScreen()); }
                finally { reopening = false; }
            }
            case 3 -> {
                next(); reopening = true;
                try { MC.createWorldOpenFlows().openWorld(TemplateClientReview.worldId(), () -> { throw new IllegalStateException("Maid world failed to reopen"); }); }
                finally { reopening = false; }
            }
            case 4 -> {
                if (MC.player == null || MC.level == null || MC.screen != null || MC.player.tickCount < 30) return false;
                server(p -> {
                    var maid = maid(p); maidId = maid.getId();
                    require(stock(p, 0) == 5 && maid.getOffhandItem().is(Items.BREAD) && maid.getOffhandItem().getCount() == 1,
                            "Native save/reopen lost food or refunded its cache debit");
                    var hidden = (net.neoforged.neoforge.items.IItemHandler)maid.getClass().getMethod("getHideInv").invoke(maid);
                    require(hidden.getStackInSlot(0).is(Items.SHIELD), "Native save/reopen lost the original hand equipment");
                    meal(maid, p.serverLevel()); require(maid.isUsingItem() && stock(p, 0) == 5, "Resume spent a second cache food");
                }); next();
            }
            case 5 -> {
                if (ticks - changed < 50) return false;
                server(p -> require(!maid(p).isUsingItem() && maid(p).getOffhandItem().is(Items.SHIELD)
                        && maid(p).getMainHandItem().is(Items.DIAMOND_SWORD) && stock(p, 0) == 5, "Saved meal failed to restore native equipment"));
                TemplateClientReview.capture("maid-meal-rejoined-completed"); next();
            }
            case 6 -> {
                FeedMePackages.LOGGER.info("FMP_MAID_CONSUMPTION_CLIENT_PASSED fullBag=true handDelta=3 oldHand=7 foodTaken=1 nativeSaveLeaveReopen=true resumeDebit=0 equipmentRestored=true");
                if (!ModList.get().isLoaded("tacz")) return true;
                server(p -> {
                    var maid = maid(p); var gun = TaczTests.gun(p, "ak47"); maid.setItemInHand(InteractionHand.MAIN_HAND, gun);
                    var op = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                    op.getMethod("initialData").invoke(maid); op.getMethod("draw", Supplier.class).invoke(maid, (Supplier<ItemStack>)maid::getMainHandItem);
                    var holder = op.getMethod("getDataHolder").invoke(maid);
                    holder.getClass().getField("drawTimestamp").setLong(holder, -1);
                    holder.getClass().getField("shootTimestamp").setLong(holder, -30_000);
                    op.getMethod("reload").invoke(maid);
                    require(stock(p, 2) == 70 && TaczTests.totalLoaded(gun) == 0, "Starting packaged maid reload spent cached rounds before native feed");
                }); next();
            }
            case 7 -> {
                if (ticks - changed < 140) return false;
                server(p -> {
                    var maid = maid(p);
                    require(TaczTests.totalLoaded(maid.getMainHandItem()) == 30 && stock(p, 2) == 40,
                            "Packaged native maid reload failed to consume exactly 30 cache rounds with full bag");
                    var inv = MaidCompatibility.inventory(maid);
                    for (int i = 0; i < inv.getSlots(); i++) require(inv.getStackInSlot(i).is(Items.DIRT), "Maid reload staged ammo into the full backpack");
                }); next();
            }
            case 8 -> {
                var maid = (LivingEntity)MC.level.getEntity(maidId);
                require(maid != null && TaczTests.totalLoaded(maid.getMainHandItem()) == 30, "Client did not receive the maid's native loaded gun");
                TemplateClientReview.capture("maid-full-bag-tacz-loaded");
                FeedMePackages.LOGGER.info("FMP_MAID_TACZ_CLIENT_PASSED nativeReload=true realTimeTicks=true fullBag=true cache=70->40 magazine=30 clientEquipmentSync=true"); return true;
            }
        }
        return false;
    }
    private static void setup(ServerPlayer p) throws ReflectiveOperationException {
        var playerSlot = TestPlayers.necklace(p); var previous = playerSlot.getStackInSlot(0);
        playerSlot.setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(p, 0, new ItemStack(Items.BREAD), 6);
        ConsumptionTests.seed(p, 1, new ItemStack(Items.ARROW), 4);
        if (ModList.get().isLoaded("tacz")) ConsumptionTests.seed(p, 2, TaczTests.ammo("762x39"), 70);
        cache = AccessGate.resolve(p).handle().cacheId(); var pendant = playerSlot.getStackInSlot(0); playerSlot.setStackInSlot(0, previous);
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid"));
        var maid = (TamableAnimal)type.create(p.serverLevel()); require(maid != null, "Maid unavailable in packaged client");
        maid.setTame(true, false); maid.setOwnerUUID(p.getUUID()); maid.setNoAi(true); maid.setNoGravity(true);
        maid.setPos(p.position().add(2, 0, 0)); require(p.serverLevel().addFreshEntity(maid), "Could not add isolated maid fixture");
        var curios = CuriosApi.getCuriosInventory(maid).orElseThrow(); curios.reset();
        curios.getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, pendant);
        var inv = MaidCompatibility.inventory(maid);
        for (int i = 0; i < inv.getSlots(); i++) inv.setStackInSlot(i, new ItemStack(Items.DIRT, 64));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        maid.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD)); maidId = maid.getId(); maidUuid = maid.getUUID();
    }
    private static LivingEntity maid(ServerPlayer p) {
        var entity = p.serverLevel().getEntity(maidUuid);
        require(entity instanceof LivingEntity, "Saved native maid was not loaded"); return (LivingEntity)entity;
    }
    private static int stock(ServerPlayer p, int cell) { return CacheLedger.get(p.getServer()).find(cache).state().cells().get(cell).amount(); }
    private static void meal(LivingEntity maid, ServerLevel level) throws ReflectiveOperationException {
        var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask");
        var start = type.getDeclaredMethod("start", ServerLevel.class, maid.getClass(), long.class); start.setAccessible(true);
        start.invoke(type.getConstructor().newInstance(), level, maid, level.getGameTime());
    }
    private static void server(Action action) {
        var server = MC.getSingleplayerServer(); var player = server.getPlayerList().getPlayer(MC.player.getUUID());
        work = CompletableFuture.runAsync(() -> { try { action.run(player); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }, server);
    }
    private static void next() { phase++; changed = ticks; }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
