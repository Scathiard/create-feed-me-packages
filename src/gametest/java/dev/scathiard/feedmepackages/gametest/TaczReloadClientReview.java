package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real native per-round keyboard reload and rendered tracks; no animation/time overrides. */
final class TaczReloadClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    // The older port's bundled gun pack predates the 1866. Its M870 uses the same native reserve query.
    private static final boolean PORTED = net.neoforged.fml.ModList.get().getModContainerById("tacz").orElseThrow()
            .getModInfo().getVersion().toString().startsWith("1.1.8");
    private static final String GUN = PORTED ? "m870" : "m1866", AMMO = PORTED ? "12g" : "45acp";
    private static int stage, ticks, changed;
    private static CompletableFuture<Void> work;
    private static final Set<String> animations = new LinkedHashSet<>();
    private static java.util.UUID cache;
    private static boolean continuedLoop;
    @FunctionalInterface private interface Action { void run(ServerPlayer player) throws ReflectiveOperationException; }
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 700, "m1866 reload timeout stage=" + stage + " animations=" + animations);
        switch (stage) {
            case 0 -> {
                MC.player.closeContainer(); MC.setScreen(null);
                server(p -> {
                    p.getInventory().clearContent(); TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                    p.getInventory().selected = 0; p.setItemInHand(InteractionHand.MAIN_HAND, TaczTests.gun(p, GUN));
                    ConsumptionTests.seed(p, 0, TaczTests.ammo(AMMO), 8);
                    cache = dev.scathiard.feedmepackages.service.AccessGate.resolve(p).handle().cacheId();
                    p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 1 -> {
                if (ticks - changed < 60 || !ClientMaterials.active() || context() == null) return false;
                require((boolean)context().getClass().getMethod("hasAmmoToConsume").invoke(context()),
                        "Native animation context sees no cached m1866 ammo while the server can reload");
                reload(); next();
            }
            case 2 -> {
                collectAnimations();
                if (ticks - changed < 260) return false;
                require(continuedLoop, "Rendered reload never continued past first round: " + animations);
                require(loaded(MC.player.getMainHandItem()) >= 5, "Native m1866 did not finish loading");
                server(p -> require(loaded(p.getMainHandItem()) + ConsumptionTests.stock(p, 0) == 8,
                        "m1866 changed total ammunition"));
                TemplateClientReview.capture("tacz-" + GUN + "-repeated-reload"); next();
            }
            case 3 -> {
                server(p -> { TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY); p.inventoryMenu.broadcastChanges(); }); next();
            }
            case 4 -> {
                if (ticks - changed < 40 || ClientMaterials.active()) return false;
                require(!(boolean)context().getClass().getMethod("hasAmmoToConsume").invoke(context()), "Unequipped animation retained cached reserve");
                server(p -> {
                    long reserve = dev.scathiard.feedmepackages.storage.CacheLedger.get(p.getServer()).find(cache).state().cells().get(0).amount();
                    require(reserve + loaded(p.getMainHandItem()) == 8, "Read-only animation query consumed reserve");
                }); next();
            }
            case 5 -> {
                FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_CLIENT_PASSED gun={} nativeKeyboard=true renderedTracks={} totalAmmo=8 unequip=true", GUN, animations);
                return true;
            }
        }
        return false;
    }
    private static Object machine() throws ReflectiveOperationException {
        var display = (Optional<?>) Class.forName("com.tacz.guns.api.TimelessAPI").getMethod("getGunDisplay", ItemStack.class).invoke(null, MC.player.getMainHandItem());
        return display.isEmpty() ? null : display.get().getClass().getMethod("getAnimationStateMachine").invoke(display.get());
    }
    private static Object context() throws ReflectiveOperationException {
        var machine = machine(); return machine == null ? null : machine.getClass().getMethod("getContext").invoke(machine);
    }
    private static void collectAnimations() throws ReflectiveOperationException {
        var machine = machine(); var controller = machine.getClass().getMethod("getAnimationController").invoke(machine);
        for (int i = 0; i < 16; i++) {
            var runner = controller.getClass().getMethod("getAnimation", int.class).invoke(controller, i);
            if (runner == null) continue;
            var animation = runner.getClass().getMethod("getAnimation").invoke(runner);
            String name = (String)animation.getClass().getField("name").get(animation);
            if (name.contains("reload_loop_next") || PORTED && name.equals("reload_loop") && loaded(MC.player.getMainHandItem()) >= 2) continuedLoop = true;
            if (animations.add(name)) FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_TRACK tick={} track={} animation={} loaded={}", ticks, i, name, loaded(MC.player.getMainHandItem()));
        }
    }
    private static int loaded(ItemStack gun) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.api.item.IGun");
        return (int)type.getMethod("getCurrentAmmoCount", ItemStack.class).invoke(gun.getItem(), gun)
                + ((boolean)type.getMethod("hasBulletInBarrel", ItemStack.class).invoke(gun.getItem(), gun) ? 1 : 0);
    }
    private static void reload() throws ReflectiveOperationException {
        var key = (net.minecraft.client.KeyMapping)Class.forName("com.tacz.guns.client.input.ReloadKey").getField("RELOAD_KEY").get(null);
        var input = MC.keyboardHandler.getClass().getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class); input.setAccessible(true);
        input.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key.getKey().getValue(), 0, 1, 0);
        input.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key.getKey().getValue(), 0, 0, 0);
    }
    private static void server(Action action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> { try { action.run(server.getPlayerList().getPlayer(id)); }
            catch (ReflectiveOperationException failed) { throw new IllegalStateException(failed); } });
    }
    private static void next() { stage++; changed = ticks; }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
