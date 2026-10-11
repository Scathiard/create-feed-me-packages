package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native entity-interaction packet, maid's Curios button and actual inventory clicks. */
final class MaidClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int phase, ticks, changed, maidId;
    private static UUID cache;
    private static CompletableFuture<Void> work;
    static boolean tick() throws Exception {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed > 700) throw new IllegalStateException("Maid client timeout phase=" + phase);
        if (ticks - changed < 25) return false;
        switch (phase) {
            case 0 -> {
                // The author's tall screen exceeds the minimum window at GUI scale 2.
                MC.options.guiScale().set(1); MC.resizeDisplay(); server(MaidClientReview::setup); next();
            }
            case 1 -> {
                var maid = MC.level.getEntity(maidId); if (maid == null) break;
                MC.gameMode.interact(MC.player, maid, InteractionHand.MAIN_HAND); next();
            }
            case 2 -> {
                if (MC.screen == null) break;
                var button = MC.screen.children().stream().filter(w -> w.getClass().getName()
                        .equals("com.github.tartaricacid.touhoulittlemaid.compat.curios.client.CuriosButton"))
                        .map(AbstractWidget.class::cast).findFirst().orElse(null);
                if (button == null) break;
                require(button.active && button.visible, "Native maid Curios button unavailable");
                TemplateClientReview.click(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0); next();
            }
            case 3 -> {
                if (!MC.player.containerMenu.getClass().getName().endsWith(".CuriosContainer")) break;
                // Seed only inside this foreign menu: ordinary inventory ticks legitimately retire old keys.
                server(p -> { p.getInventory().setItem(1, FmpRegistries.PERSONAL_PENDANT.toStack()); p.containerMenu.broadcastChanges(); });
                click(playerSlot(0)); next();
            }
            case 4 -> { click(necklace()); next(); }
            case 5 -> {
                server(p -> { var a = MaidAccess.resolve(maid(p)); require(a.active() && a.handle().cacheId().equals(cache)
                        && !AccessGate.resolve(p).active() && stock(p) == 8, "Mouse-equipped ordinary pendant did not belong to the maid"); });
                TemplateClientReview.capture("74-maid-ordinary-equipped"); click(necklace()); next();
            }
            case 6 -> { click(playerSlot(0)); next(); }
            case 7 -> {
                server(p -> require(!MaidAccess.resolve(maid(p)).active() && stock(p) == 8
                        && p.getInventory().getItem(0).is(FmpRegistries.PENDANT.get()), "Mouse unequip left maid access active or lost the pendant"));
                click(playerSlot(1)); next();
            }
            case 8 -> { click(necklace()); next(); }
            case 9 -> {
                server(p -> {
                    var worn = CuriosApi.getCuriosInventory(maid(p)).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().getStackInSlot(0);
                    var legacy = p.containerMenu.getCarried();
                    require(worn.isEmpty() && legacy.is(FmpRegistries.PERSONAL_PENDANT.get())
                            && !legacy.has(FmpRegistries.OWNER.get()) && !legacy.has(FmpRegistries.IDENTITY.get())
                            && !MaidAccess.resolve(maid(p)).active() && stock(p) == 8,
                            "Retired item rejection: worn="+worn+" carried="+legacy+" access="+MaidAccess.resolve(maid(p)).status()+" originalStock="+stock(p));
                });
                TemplateClientReview.capture("75-maid-legacy-item-rejected"); next();
            }
            case 10 -> { click(playerSlot(1)); next(); }
            case 11 -> {
                server(p -> require(p.containerMenu.getCarried().isEmpty() && p.getInventory().getItem(0).getCount() == 1
                        && p.getInventory().getItem(1).getCount() == 1 && stock(p) == 8, "Maid UI failed item conservation")); next();
            }
            case 12 -> {
                FeedMePackages.LOGGER.info("FMP_MAID_CLIENT_PASSED nativeEntityInteraction=true nativeCuriosButton=true mouseEquip=true mouseUnequip=true legacyItemRejectedIntact=true stock=8");
                return true;
            }
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        p.closeContainer(); p.getInventory().clearContent(); ConsumptionTests.seed(p, 0, new ItemStack(Items.BREAD), 8);
        cache = AccessGate.resolve(p).handle().cacheId();
        var ordinary = TestPlayers.necklace(p).getStackInSlot(0); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
        p.getInventory().setItem(0, ordinary);
        p.getInventory().selected = 7; p.inventoryMenu.broadcastChanges();
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid"));
        var maid = (TamableAnimal) type.create(p.serverLevel()); require(maid != null, "Native maid absent");
        maid.setTame(true, false); maid.setOwnerUUID(p.getUUID()); maid.setNoAi(true); maid.setNoGravity(true);
        maid.setPos(p.position().add(2, 0, 0)); require(p.serverLevel().addFreshEntity(maid), "Maid spawn failed");
        CuriosApi.getCuriosInventory(maid).orElseThrow().reset(); maidId = maid.getId();
    }
    private static TamableAnimal maid(ServerPlayer p) { return (TamableAnimal) p.serverLevel().getEntity(maidId); }
    private static long stock(ServerPlayer p) { return CacheLedger.get(p.getServer()).find(cache).state().cells().get(0).amount(); }
    private static Slot playerSlot(int id) {
        return MC.player.containerMenu.slots.stream().filter(s -> s.container == MC.player.getInventory() && s.getContainerSlot() == id).findFirst().orElseThrow();
    }
    private static Slot necklace() throws ReflectiveOperationException {
        for (var slot : MC.player.containerMenu.slots) {
            if (slot.getClass().getName().endsWith(".MaidCurioSlot") && slot.getClass().getMethod("getIdentifier").invoke(slot).equals("necklace")) return slot;
        }
        throw new IllegalStateException("Native maid necklace slot absent");
    }
    private static void click(Slot slot) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>) MC.screen;
        TemplateClientReview.click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> operation.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_MAID_CLIENT_PHASE {}", phase); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
