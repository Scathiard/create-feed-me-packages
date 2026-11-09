package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real native per-round keyboard reload and rendered tracks; no animation/time overrides. */
final class TaczReloadClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final boolean PHYSICAL = Boolean.getBoolean("fmp.taczReloadPhysical");
    // The older port's bundled gun pack predates the 1866. Its M870 uses the same native reserve query.
    private static final boolean PORTED = net.neoforged.fml.ModList.get().getModContainerById("tacz").orElseThrow()
            .getModInfo().getVersion().toString().startsWith("1.1.8");
    private record Sample(String gun, String ammo, int initial, int minimum) {}
    private static final List<Sample> SAMPLES = Arrays.stream(System.getProperty("fmp.taczReloadCases",
            PORTED ? "tacz:m870,tacz:12g,0,5" : "tacz:m1866,tacz:45acp,0,5").split(";")).map(value -> {
        var parts = value.split(",");
        if (parts.length != 4) throw new IllegalArgumentException("Expected gun,ammo,initial,minimum: " + value);
        return new Sample(parts[0], parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }).toList();
    private static int stage, ticks, changed, sampleIndex, loopStarts;
    private static boolean drawRequested;
    private static CompletableFuture<Void> work;
    private static final Set<String> animations = new LinkedHashSet<>();
    private static java.util.UUID cache;
    private static final Object[] previousRunners = new Object[16];
    private static final long[] previousProgress = new long[16];
    private static Sample sample() { return SAMPLES.get(sampleIndex); }
    @FunctionalInterface private interface Action { void run(ServerPlayer player) throws ReflectiveOperationException; }
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 900, "Reload timeout gun=" + sample().gun + " stage=" + stage + " animations=" + animations);
        switch (stage) {
            case 0 -> {
                if (ticks - changed < 30) return false;
                MC.player.closeContainer(); MC.setScreen(null);
                server(p -> {
                    p.getInventory().clearContent(); TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                    var gun = TaczTests.gun(p, sample().gun);
                    Class.forName("com.tacz.guns.api.item.IGun").getMethod("setCurrentAmmoCount", ItemStack.class, int.class)
                            .invoke(gun.getItem(), gun, sample().initial);
                    p.getInventory().setItem(0, gun);
                    ConsumptionTests.seed(p, 0, TaczTests.ammo(sample().ammo), PHYSICAL ? 0 : 8);
                    if (PHYSICAL) p.getInventory().setItem(1, TaczTests.ammo(sample().ammo).copyWithCount(8));
                    cache = dev.scathiard.feedmepackages.service.AccessGate.resolve(p).handle().cacheId();
                    p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 1 -> {
                if (!drawRequested) { press(MC.options.keyHotbarSlots[0]); drawRequested = true; changed = ticks; return false; }
                if (ticks - changed < 60 || !ClientMaterials.active() || context() == null) return false;
                var operator = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                if ((long)operator.getMethod("getSynDrawCoolDown").invoke(MC.player) > 0) return false;
                boolean nativeCanReload = (boolean)MC.player.getMainHandItem().getItem().getClass()
                        .getMethod("canReload", net.minecraft.world.entity.LivingEntity.class, ItemStack.class)
                        .invoke(MC.player.getMainHandItem().getItem(), MC.player, MC.player.getMainHandItem());
                FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_READY gun={} physical={} nativeCanReload={} spectator={} hints={}",
                        sample().gun, PHYSICAL, nativeCanReload, MC.player.isSpectator(),
                        dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.clientHints(MC.player.getMainHandItem()).stream().mapToInt(ItemStack::getCount).sum());
                require(nativeCanReload, "Native reload refused available ammo: " + sample().gun);
                require((boolean)context().getClass().getMethod("hasAmmoToConsume").invoke(context()),
                        "Native animation context sees no cached ammo: " + sample().gun);
                var stale = MC.player.getMainHandItem().copy();
                var api = Class.forName("com.tacz.guns.api.item.IGun");
                api.getMethod("setCurrentAmmoCount", ItemStack.class, int.class).invoke(stale.getItem(), stale, 0);
                require(dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.animationAmmo(stale) == !PHYSICAL,
                        "Native slot replacement was mistaken for a different gun: " + sample().gun);
                api.getMethod("setGunId", ItemStack.class, net.minecraft.resources.ResourceLocation.class)
                        .invoke(stale.getItem(), stale, net.minecraft.resources.ResourceLocation.parse("tacz:ak47"));
                require(!dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.animationAmmo(stale),
                        "A different gun inherited the held weapon's reserve");
                reload(); next();
            }
            case 2 -> {
                collectAnimations();
                if (ticks - changed < 400) return false;
                FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_RESULT gun={} physical={} loaded={} loops={} state={}", sample().gun, PHYSICAL,
                        loaded(MC.player.getMainHandItem()), loopStarts, context().getClass().getMethod("getReloadStateType").invoke(context()));
                require(loopStarts >= 2, "Rendered reload did not repeat: " + sample().gun + " starts=" + loopStarts + " animations=" + animations);
                require(loaded(MC.player.getMainHandItem()) >= sample().minimum, "Native reload stopped early: " + sample().gun);
                server(p -> require(loaded(p.getMainHandItem()) + ConsumptionTests.stock(p, 0) + p.getInventory().getItem(1).getCount() == 8 + sample().initial,
                        "Reload changed total ammunition: " + sample().gun));
                TemplateClientReview.capture("tacz-" + sample().gun.replace(':', '-') + "-repeated-reload"); next();
            }
            case 3 -> {
                server(p -> { TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY); p.inventoryMenu.broadcastChanges(); }); next();
            }
            case 4 -> {
                if (ticks - changed < 40 || ClientMaterials.active()) return false;
                require(!dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.animationAmmo(MC.player.getMainHandItem()), "Unequipped animation retained cached reserve");
                if (!PHYSICAL) require(!(boolean)context().getClass().getMethod("hasAmmoToConsume").invoke(context()), "Unequipped native query retained cached reserve");
                server(p -> {
                    long reserve = dev.scathiard.feedmepackages.storage.CacheLedger.get(p.getServer()).find(cache).state().cells().get(0).amount();
                    require(reserve + loaded(p.getMainHandItem()) + p.getInventory().getItem(1).getCount() == 8 + sample().initial, "Read-only animation query consumed reserve");
                }); next();
            }
            case 5 -> {
                FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_CLIENT_PASSED gun={} nativeKeyboard=true renderedTracks={} loopStarts={} totalAmmo={} unequip=true",
                        sample().gun, animations, loopStarts, 8 + sample().initial);
                if (++sampleIndex == SAMPLES.size()) return true;
                // Switch through a real empty hotbar slot so the author's draw/cancel lifecycle runs.
                press(MC.options.keyHotbarSlots[8]); drawRequested = false;
                stage = 0; changed = ticks; loopStarts = 0; animations.clear();
                Arrays.fill(previousRunners, null); Arrays.fill(previousProgress, 0);
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
            if (runner == null) { previousRunners[i] = null; continue; }
            var animation = runner.getClass().getMethod("getAnimation").invoke(runner);
            String name = (String)animation.getClass().getField("name").get(animation);
            long progress = (long)runner.getClass().getMethod("getProgressNs").invoke(runner);
            if (name.contains("reload_loop") && (runner != previousRunners[i] || progress < previousProgress[i])) {
                loopStarts++;
                FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_LOOP gun={} tick={} track={} animation={} loaded={} starts={}",
                        sample().gun, ticks, i, name, loaded(MC.player.getMainHandItem()), loopStarts);
            }
            previousRunners[i] = runner; previousProgress[i] = progress;
            if (animations.add(name)) FeedMePackages.LOGGER.info("FMP_TACZ_RELOAD_TRACK gun={} tick={} track={} animation={} loaded={}", sample().gun, ticks, i, name, loaded(MC.player.getMainHandItem()));
        }
    }
    private static int loaded(ItemStack gun) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.api.item.IGun");
        return (int)type.getMethod("getCurrentAmmoCount", ItemStack.class).invoke(gun.getItem(), gun)
                + ((boolean)type.getMethod("hasBulletInBarrel", ItemStack.class).invoke(gun.getItem(), gun) ? 1 : 0);
    }
    private static void reload() throws ReflectiveOperationException {
        var key = (net.minecraft.client.KeyMapping)Class.forName("com.tacz.guns.client.input.ReloadKey").getField("RELOAD_KEY").get(null);
        press(key);
    }
    private static void press(net.minecraft.client.KeyMapping key) throws ReflectiveOperationException {
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
