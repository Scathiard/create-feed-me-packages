package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.CompletableFuture;

/** Opt-in isolated integrated-client check. Stock is a fixture; reload uses the actual keyboard/event/network path. */
final class TaczClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static int stage, ticks, changed;
    private static CompletableFuture<Void> work;
    private static ItemStack nativeGun;
    private static java.util.UUID cacheId;
    @FunctionalInterface private interface Action { void run(ServerPlayer player) throws ReflectiveOperationException; }
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (!net.neoforged.fml.ModList.get().isLoaded("tacz")) {
            if (ticks < 40) return false;
            require(ClientMaterials.active() && !ClientCompatibility.taczSourcesAvailable()
                            && !dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.ready()
                            && !dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.ready() && chatCount("tacz_") == 0,
                    "Absent TaCZ disrupted normal cache access, linked missing classes, or emitted a false warning");
            return true;
        }
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 600, "TaCZ client timeout at stage " + stage);
        switch (stage) {
            case 0 -> {
                MC.player.closeContainer(); MC.setScreen(null);
                server(p -> {
                    p.getInventory().clearContent(); nativeGun = TaczTests.gun(p, "ak47");
                    p.setItemInHand(InteractionHand.MAIN_HAND, nativeGun);
                    ConsumptionTests.seed(p, 0, TaczTests.ammo("762x39"), 45); p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 1 -> {
                if (ticks - changed < 60 || !ClientCompatibility.taczSourcesAvailable() || !ClientMaterials.active()) return false;
                var gun = MC.player.getMainHandItem();
                require((boolean)Class.forName("com.tacz.guns.api.item.gun.AbstractGunItem").getMethod("canReload", LivingEntity.class, ItemStack.class)
                        .invoke(gun.getItem(), MC.player, gun), "Real client cannot query cached ammunition with its GUI closed");
                require(chatCount("tacz_") == 0, "Healthy TaCZ adapter printed a false failure");
                reloadKey(); next();
            }
            case 2 -> {
                if (ticks - changed < 90 || loaded(MC.player.getMainHandItem()) != 30) return false;
                require(hudCount() == 15, "Native reserve HUD did not include cached cartridges");
                server(p -> require(ConsumptionTests.stock(p, 0) == 15 && p.getMainHandItem() == nativeGun,
                        "Client reload did not consume exactly 30 from the integrated server cache"));
                TemplateClientReview.capture("tacz-loaded-from-cache"); next();
            }
            case 3 -> {
                var result = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator").getMethod("shoot").invoke(MC.player);
                require(result.toString().equals("SUCCESS"), "Native client shooting refused loaded cache ammo: " + result); next();
            }
            case 4 -> {
                if (ticks - changed < 20 || loaded(MC.player.getMainHandItem()) != 29) return false;
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 15, "Loaded magazine shot charged integrated cache twice");
                    FmpConfig.TACZ.set(false); FmpConfig.refreshServer();
                    nativeGun = TaczTests.gun(p, "ak47"); p.setItemInHand(InteractionHand.MAIN_HAND, nativeGun);
                    p.getInventory().setItem(1, TaczTests.ammo("762x39").copyWithCount(3)); p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 5 -> {
                if (ticks - changed < 60 || ClientCompatibility.taczSourcesAvailable() || loaded(MC.player.getMainHandItem()) != 0) return false;
                require(chatCount("tacz_config_disabled") == 1, "Disabled server adapter did not produce exactly one visible warning");
                TemplateClientReview.capture("tacz-disabled-warning"); reloadKey(); next();
            }
            case 6 -> {
                if (ticks - changed < 90 || loaded(MC.player.getMainHandItem()) != 3) return false;
                require(chatCount("tacz_config_disabled") == 1, "Disabled warning was spammed each tick");
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 15 && p.getInventory().getItem(1).isEmpty(), "Disabled native reload spent cache or lost normal ammo");
                    FmpConfig.TACZ.set(true); FmpConfig.refreshServer();
                }); next();
            }
            case 7 -> {
                if (!ClientCompatibility.taczSourcesAvailable()) return false;
                FeedMePackages.LOGGER.info("FMP_TACZ_INTEGRATED_ACCOUNT initial=45 reload=30 shotCache=0 disabledInventory=3 final=15 side=server-cache clientHints=ready");
                server(p -> {
                    nativeGun = TaczTests.gun(p, "minigun"); p.setItemInHand(InteractionHand.MAIN_HAND, nativeGun);
                    ConsumptionTests.seed(p, 1, TaczTests.ammo("308"), 3); p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 8 -> {
                if (ticks - changed < 60) return false;
                require(hudCount() == 3 && loaded(MC.player.getMainHandItem()) == 3, "Native inventory-fed HUD did not include cache");
                var result = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator").getMethod("shoot").invoke(MC.player);
                require(result.toString().equals("SUCCESS"), "Native client inventory-fed shot refused cache-only ammo: " + result); next();
            }
            case 9 -> {
                if (ticks - changed < 20) return false;
                server(p -> require(ConsumptionTests.stock(p, 1) == 2 && ConsumptionTests.stock(p, 0) == 15,
                        "Client inventory-fed shot did not debit the matching cache exactly once: matching=" + ConsumptionTests.stock(p, 1)
                                + " unrelated=" + ConsumptionTests.stock(p, 0))); next();
            }
            case 10 -> {
                TemplateClientReview.capture("tacz-inventory-fed-cache");
                FeedMePackages.LOGGER.info("FMP_TACZ_CLIENT_INVENTORY_FIRE initial=3 final=2 side=server-cache");
                hudEnabled().set(false);
                server(p -> { nativeGun = TaczTests.gun(p, "minigun"); p.setItemInHand(InteractionHand.MAIN_HAND, nativeGun); p.inventoryMenu.broadcastChanges(); });
                next();
            }
            case 11 -> {
                if (ticks - changed < 60) return false;
                require(loaded(MC.player.getMainHandItem()) == 0, "Hidden-HUD fixture already had a populated native magazine proxy");
                var result = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator").getMethod("shoot").invoke(MC.player);
                require(result.toString().equals("SUCCESS"), "Hidden-HUD native shot rejected cached ammunition: " + result); next();
            }
            case 12 -> {
                if (ticks - changed < 20) return false;
                server(p -> require(ConsumptionTests.stock(p, 1) == 1 && ConsumptionTests.stock(p, 0) == 15,
                        "Hidden-HUD shot did not debit cache exactly once: " + ConsumptionTests.stock(p, 1))); next();
            }
            case 13 -> {
                hudEnabled().set(true);
                FeedMePackages.LOGGER.info("FMP_TACZ_CLIENT_HIDDEN_HUD_FIRE initial=2 final=1 nativeProxy=0 side=server-cache");
                server(TaczClientReview::largeStock); next();
            }
            case 14 -> {
                if (ticks - changed < 60 || hudCount() != 4097) return false;
                var gun = MC.player.getMainHandItem();
                require(dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.firingCount(gun, loaded(gun)) == 4097,
                        "Client shot quantity counted cache twice after native HUD update");
                require(dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.format("4097", true).equals("≥4097"),
                        "Saturated material hint was presented as exact ammunition stock");
                TemplateClientReview.capture("tacz-capped-reserve");
                var result = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator").getMethod("shoot").invoke(MC.player);
                require(result.toString().equals("SUCCESS"), "Native shot with large cached reserve failed: " + result); next();
            }
            case 15 -> {
                if (ticks - changed < 20) return false;
                server(p -> {
                    require(ConsumptionTests.stock(p, 1) == 5000 && p.getInventory().getItem(1).isEmpty(), "Client direct-fed gun did not spend ordinary ammo before cache");
                    TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
                }); next();
            }
            case 16 -> {
                if (ticks - changed < 40 || ClientMaterials.active() || hudCount() != 0) return false;
                var result = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator").getMethod("shoot").invoke(MC.player);
                require(result.toString().equals("NO_AMMO"), "Unequipped client retained cache-only shooting access: " + result);
                server(p -> require(dev.scathiard.feedmepackages.storage.CacheLedger.get(p.getServer()).find(cacheId).state().cells().get(1).amount() == 5000,
                        "Unequip or rejected client shot changed stored ammunition")); next();
            }
            case 17 -> {
                FeedMePackages.LOGGER.info("FMP_TACZ_CLIENT_QUANTITY nativeInventory=1 cache=5000 visibleLowerBound=4097 noDoubleCount=true unequip=NO_AMMO");
                return true;
            }
        }
        return false;
    }
    private static void reloadKey() throws ReflectiveOperationException {
        var key = (net.minecraft.client.KeyMapping)Class.forName("com.tacz.guns.client.input.ReloadKey").getField("RELOAD_KEY").get(null);
        var input = MC.keyboardHandler.getClass().getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class);
        input.setAccessible(true);
        input.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key.getKey().getValue(), 0, 1, 0);
        input.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key.getKey().getValue(), 0, 0, 0);
    }
    private static int hudCount() throws ReflectiveOperationException {
        var field = Class.forName("com.tacz.guns.client.gui.overlay.GunHudOverlay").getDeclaredField("cacheInventoryAmmoCount");
        field.setAccessible(true); return field.getInt(null);
    }
    private static void largeStock(ServerPlayer p) throws ReflectiveOperationException {
        var access = dev.scathiard.feedmepackages.service.AccessGate.resolve(p); cacheId = access.handle().cacheId();
        var ledger = dev.scathiard.feedmepackages.storage.CacheLedger.get(p.getServer()); var old = ledger.find(cacheId);
        var key = dev.scathiard.feedmepackages.item.ItemVariantKey.of(TaczTests.ammo("308"), p.registryAccess());
        var configured = FmpConfig.GROUPS.get(old.state().level()-1);
        configured.set(Math.max(configured.get(), Math.ceilDiv(5000,key.stackSize())));
        FmpConfig.refreshServer(); dev.scathiard.feedmepackages.service.AccessGate.resolve(p);
        ConsumptionTests.seed(p, 1, TaczTests.ammo("308"), 4999);
        require(ConsumptionTests.stock(p, 1) == 5000, "Large-ammunition fixture did not reach intended stock");
        p.getInventory().setItem(1, TaczTests.ammo("308").copyWithCount(1)); p.inventoryMenu.broadcastChanges();
    }
    private static net.neoforged.neoforge.common.ModConfigSpec.BooleanValue hudEnabled() throws ReflectiveOperationException {
        return (net.neoforged.neoforge.common.ModConfigSpec.BooleanValue)Class.forName("com.tacz.guns.config.client.RenderConfig").getField("GUN_HUD_ENABLE").get(null);
    }
    private static int loaded(ItemStack gun) throws ReflectiveOperationException {
        var type = Class.forName("com.tacz.guns.api.item.IGun"); if (!type.isInstance(gun.getItem())) return -1;
        return (int)type.getMethod("getCurrentAmmoCount", ItemStack.class).invoke(gun.getItem(), gun)
                + ((boolean)type.getMethod("hasBulletInBarrel", ItemStack.class).invoke(gun.getItem(), gun) ? 1 : 0);
    }
    private static int chatCount(String suffix) throws ReflectiveOperationException {
        var chat = MC.gui.getChat(); var field = chat.getClass().getDeclaredField("allMessages"); field.setAccessible(true);
        int count = 0;
        for (Object value : (java.util.List<?>)field.get(chat)) {
            var content = ((net.minecraft.client.GuiMessage)value).content().getContents();
            if (content instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().startsWith("gui.create_feed_me_packages.compat." + suffix)) count++;
        }
        return count;
    }
    private static void server(Action action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> { try { action.run(server.getPlayerList().getPlayer(id)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } });
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_TACZ_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
