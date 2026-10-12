package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real author's menu, JEI plus mouse button, wire and native result clicks. No direct apply call. */
final class SophisticatedClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int phase, ticks, changed;
    private static UUID cache;
    private static ItemStack bag;
    private static CompletableFuture<Void> work;
    static boolean tick() throws Exception {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed > 700) throw new IllegalStateException("Sophisticated client timeout phase=" + phase);
        if (ticks - changed < 25) return false;
        switch (phase) {
            case 0 -> { server(SophisticatedClientReview::setup); next(); }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?>) || SophisticatedAccess.resolve(MC.player) == null
                        || !ClientMaterials.active() || !ClientCompatibility.sophisticatedSourcesAvailable()) break;
                JeiClientReview.openTransfer(); next();
            }
            case 2 -> { JeiClientReview.previewTransfer(); TemplateClientReview.capture("71-sophisticated-native-preview"); JeiClientReview.clickTransfer(); next(); }
            case 3 -> {
                server(p -> require(stock(p) == 8 && grid(p) == 1 && nativeInventory(p).getStackInSlot(0).isEmpty(), "Native bag did not precede cache"));
                click(result()); next();
            }
            case 4 -> {
                server(p -> require(stock(p) == 8 && grid(p) == 0 && p.containerMenu.getCarried().getCount() == 4,
                        "Native result click refilled or debited cache")); click(playerSlot(0)); next();
            }
            case 5 -> { JeiClientReview.openTransfer(); next(); }
            case 6 -> { JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 7 -> {
                server(p -> require(stock(p) == 7 && grid(p) == 1 && p.getInventory().getItem(8) == bag,
                        "Cache wire failed or replaced backpack identity"));
                TemplateClientReview.capture("72-sophisticated-cache-filled"); click(result()); next();
            }
            case 8 -> {
                server(p -> require(stock(p) == 7 && grid(p) == 0 && p.containerMenu.getCarried().getCount() == 4,
                        "Cached native craft double-debited or silently refilled")); click(playerSlot(0)); next();
            }
            case 9 -> { JeiClientReview.openTransfer(); next(); }
            case 10 -> { JeiClientReview.previewTransfer(); JeiClientReview.transferMaximum(); MC.screen.onClose(); next(); }
            case 11 -> {
                server(p -> require(stock(p) == 0 && grid(p) == 7, "Native maximum did not include cache"));
                TemplateClientReview.capture("73-sophisticated-maximum-filled"); MC.player.closeContainer(); next();
            }
            case 12 -> {
                server(p -> { setup(p); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY); }); next();
            }
            case 13 -> { if (ClientMaterials.active() || SophisticatedAccess.resolve(MC.player) == null) break; JeiClientReview.openTransfer(); next(); }
            case 14 -> { JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 15 -> {
                server(p -> require(stock(p) == 8 && grid(p) == 1 && nativeInventory(p).getStackInSlot(0).isEmpty(), "Unworn native JEI path was broken"));
                next();
            }
            case 16 -> {
                FeedMePackages.LOGGER.info("FMP_SOPHISTICATED_CLIENT_PASSED nativeMenu=true nativeJeiMouse=true realPayload=true physicalFirst=true exactDebit=true noDefaultRefill=true maximumApi=true unwornFallback=true");
                return true;
            }
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        try {
            p.closeContainer(); p.getInventory().clearContent(); TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
            ConsumptionTests.seed(p, 0, new ItemStack(Items.OAK_LOG), 8); cache = AccessGate.resolve(p).handle().cacheId();
            bag = SophisticatedTests.createBackpack(p); p.getInventory().selected = 8; p.inventoryMenu.broadcastChanges();
            bag.getItem().use(p.level(), p, InteractionHand.MAIN_HAND);
            require(SophisticatedAccess.resolve(p) != null, "Native item use did not open its backpack menu");
            nativeInventory(p).setStackInSlot(0, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastChanges();
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
    }
    private static IItemHandlerModifiable nativeInventory(ServerPlayer p) {
        try {
            var wrapper = p.containerMenu.getClass().getMethod("getStorageWrapper").invoke(p.containerMenu);
            return (IItemHandlerModifiable) wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); }
    }
    private static int grid(ServerPlayer p) { return SophisticatedAccess.resolve(p).grid().stream().mapToInt(s -> s.getItem().getCount()).sum(); }
    private static long stock(ServerPlayer p) { return CacheLedger.get(p.getServer()).find(cache).state().cells().get(0).amount(); }
    private static Slot result() throws ReflectiveOperationException {
        var context = SophisticatedAccess.resolve(MC.player);
        var slots = (List<?>) context.crafting().getClass().getMethod("getSlots").invoke(context.crafting());
        return slots.stream().filter(ResultSlot.class::isInstance).map(Slot.class::cast).findFirst().orElseThrow();
    }
    private static Slot playerSlot(int id) {
        return MC.player.containerMenu.slots.stream().filter(s -> s.container == MC.player.getInventory() && s.getContainerSlot() == id).findFirst().orElseThrow();
    }
    private static void click(Slot slot) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>) MC.screen;
        TemplateClientReview.click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> operation.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_SOPHISTICATED_CLIENT_PHASE {}", phase); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
