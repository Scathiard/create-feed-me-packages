package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.domain.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real mouse/key callbacks exercise the actual picker and the native use-on packet. */
public final class PalletClientReview {
    private static int stage, ticks, changed, moves;
    private static CompletableFuture<Void> work;
    private static UUID cache;
    private static List<Item> blocks;
    private static BlockPos placed, broken;
    private static final Minecraft MC = Minecraft.getInstance();
    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed < 12 && stage != 3 && stage != 17 && stage != 20 && stage != 26) return false;
        switch (stage) {
            case 0 -> { server(PalletClientReview::setup); next(); }
            case 1 -> {
                if (!ClientMaterials.active() || PalletClient.candidates().size() != 34 || !Objects.equals(cache, ClientMaterials.cacheId())) return false;
                MC.setScreen(null); MC.mouseHandler.grabMouse(); org.lwjgl.glfw.GLFW.glfwFocusWindow(MC.getWindow().getWindow());
                MC.player.setYRot(0); MC.player.setXRot(40); button(2, true); next();
            }
            case 2 -> {
                require(MC.screen instanceof PalletScreen && spiral(), "Default middle button did not open spiral mode");
                TemplateClientReview.capture("pallet-spiral-default");
                // Prime the native post-grab first-move rule before starting the measured gesture.
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                moveSpiral(0); moves = 0; next();
            }
            case 3 -> {
                // Let the real client dispatch each pointer sample, rather than invoking the angular model.
                moves++;
                if (moves <= 207) { moveSpiral(moves / 3.0); return false; }
                if (moves == 208) { requireFocus(1); TemplateClientReview.capture("pallet-spiral-forward-wraps"); return false; }
                if (moves <= 418) { moveSpiral(69 - (moves - 208) / 3.0); return false; }
                requireFocus(33); TemplateClientReview.capture("pallet-spiral-reverse-wraps"); next();
            }
            case 4 -> { button(2, false); next(); }
            case 5 -> {
                server(p -> require(PalletService.choice(p).key().stack(p.registryAccess(), 1).is(blocks.get(33)),
                        "Repeated forward and reverse head-tail wraps did not select item 33"));
                button(2, true); next();
            }
            case 6 -> {
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                button(2, false); next();
            }
            case 7 -> {
                require(PalletClient.selected().is(blocks.get(33)), "Center cancellation changed the last choice");
                ClientBootstrap.PALLET_MODE.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_R));
                KeyMapping.resetMapping(); button(2, true); key(org.lwjgl.glfw.GLFW.GLFW_KEY_R); next();
            }
            case 8 -> {
                require(!spiral(), "Rebound mode key did not switch to grid while Middle was held");
                for (int i = 0; i < 12; i++) scroll(-1); next();
            }
            case 9 -> { TemplateClientReview.capture("pallet-grid-scrolled"); releaseTile(33); next(); }
            case 10 -> {
                server(p -> require(PalletService.choice(p).key().stack(p.registryAccess(), 1).is(blocks.get(33)), "Last grid item was inaccessible"));
                button(2, true); key(org.lwjgl.glfw.GLFW.GLFW_KEY_E); type("e");
                require(MC.screen instanceof PalletScreen, "Inventory key intercepted a search letter");
                key(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE); type("石头"); next();
            }
            case 11 -> {
                require(shown().size() >= 1 && shown().stream().allMatch(s -> s.getHoverName().getString().contains("石头")), "Native input did not filter grid names");
                TemplateClientReview.capture("pallet-search");
                int index = 0; while (index < shown().size() && !shown().get(index).is(Items.STONE)) index++;
                require(index < shown().size(), "Stone missing from search"); releaseTile(index); next();
            }
            case 12 -> {
                require(PalletClient.selected().is(Items.STONE), "Grid search did not select stone");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_G));
                KeyMapping.resetMapping(); keyState(org.lwjgl.glfw.GLFW.GLFW_KEY_G, true); next();
            }
            case 13 -> {
                require(MC.screen instanceof PalletScreen, "Rebound keyboard key did not open the picker");
                key(org.lwjgl.glfw.GLFW.GLFW_KEY_R); require(spiral(), "Grid did not switch back to spiral");
                moveGui(MC.getWindow().getGuiScaledWidth() / 2.0, MC.getWindow().getGuiScaledHeight() / 2.0);
                keyState(org.lwjgl.glfw.GLFW.GLFW_KEY_G, false); next();
            }
            case 14 -> {
                require(MC.screen == null && PalletClient.selected().is(Items.STONE), "Keyboard release did not cancel at center");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(4));
                KeyMapping.resetMapping(); button(4, true); next();
            }
            case 15 -> {
                require(MC.screen instanceof PalletScreen && spiral(), "Rebound mouse key did not open retained spiral mode");
                moveSpiral(0); button(4, false);
                require(MC.screen == null && PalletClient.selected().is(Items.STONE), "Rebound mouse release did not confirm");
                ClientBootstrap.PALLET_SELECT.setKey(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(2));
                ClientBootstrap.PALLET_MODE.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB));
                KeyMapping.resetMapping(); next();
            }
            case 16 -> {
                MC.player.setYRot(0); MC.player.setXRot(40);
                require(MC.hitResult instanceof BlockHitResult, "Native ray missed placement support");
                var hit = (BlockHitResult) MC.hitResult; placed = hit.getBlockPos().relative(hit.getDirection());
                button(1, true); next();
            }
            case 17 -> { button(1, false); next(); }
            case 18 -> {
                server(p -> {
                    require(p.level().getBlockState(placed).is(Blocks.STONE), "Native right click did not place selected stone");
                    var r = CacheLedger.get(p.getServer()).find(cache);
                    require(r.state().cells().getFirst().amount() == 6 && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()), "Placement lost stock or tool");
                    var edit = r.state().edit(); edit.extract(0, 6);
                    CacheLedger.get(p.getServer()).replace(AccessGate.resolve(p).handle(), r.state().revision(), r.withState(edit.finish()));
                }); TemplateClientReview.capture("pallet-native-placement"); next();
            }
            case 19 -> {
                if (PalletClient.candidates().stream().anyMatch(s -> s.is(Items.STONE))) return false;
                require(PalletClient.selected().is(Items.STONE), "Depleted selection was replaced");
                button(1, true); next();
            }
            case 20 -> { button(1, false); next(); }
            case 21 -> {
                server(p -> {
                    var r = CacheLedger.get(p.getServer()).find(cache);
                    require(r.state().cells().getFirst().amount() == 0 && r.state().cells().get(1).amount() == 7
                            && PalletService.choice(p).key().stack(p.registryAccess(), 1).is(Items.STONE), "Depletion spent replacement stock");
                    TestPlayers.necklace(p).setStackInSlot(0, FmpRegistries.PENDANT.toStack()); AccessGate.resolve(p);
                }); next();
            }
            case 22 -> {
                if (Objects.equals(cache, ClientMaterials.cacheId())) return false;
                require(PalletClient.selected().isEmpty(), "Cache switch kept the old choice");
                button(2, true); next();
            }
            case 23 -> {
                require(MC.screen instanceof PalletScreen && shown().isEmpty(), "Empty cache did not open empty picker");
                server(p -> p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)); next();
            }
            case 24 -> {
                if (MC.screen != null) return false;
                button(2, false);
                server(p -> { p.setItemInHand(InteractionHand.MAIN_HAND, FmpRegistries.POCKET_PALLET.toStack()); p.setGameMode(GameType.CREATIVE); });
                next();
            }
            case 25 -> {
                require(MC.hitResult instanceof BlockHitResult && MC.player.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()), "Test pallet not restored");
                broken = ((BlockHitResult) MC.hitResult).getBlockPos(); button(0, true); next();
            }
            case 26 -> { button(0, false); next(); }
            case 27 -> {
                require(!(MC.screen instanceof PalletScreen), "Default left click still opened selector");
                server(p -> require(p.level().getBlockState(broken).isAir() && p.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get()),
                        "Native mining remained blocked while holding the tool")); next();
            }
            case 28 -> {
                FeedMePackages.LOGGER.info("FMP_PALLET_CLIENT_PASSED defaultMiddle=true defaultSpiral=true forwardSteps=69 reverseSteps=70 nativeWrap=true nativeReverse=true nativeBlur=true keyboardAndMouseRebind=true centerCancel=true gridSearch=true nativePlace=true nativeMiningWithTool=true");
                return true;
            }
        }
        return false;
    }
    private static void setup(ServerPlayer p) {
        p.getInventory().clearContent();p.setGameMode(GameType.SURVIVAL);TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
        var handle=AccessGate.resolve(p).handle();cache=handle.cacheId();var ledger=CacheLedger.get(p.getServer());var old=ledger.find(cache);
        var edit=old.state().edit();for(int i=1;i<5;i++)edit.upgrade();
        blocks=BuiltInRegistries.ITEM.stream().filter(item -> item instanceof BlockItem && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")).limit(35).toList();
        require(blocks.getFirst()==Items.STONE,"Unexpected vanilla registry order");
        for(int i=0;i<34;i++){var key=ItemVariantKey.of(new ItemStack(blocks.get(i)),p.registryAccess());edit.filter(i,key);edit.insert(i,key,7);}
        var diamond=ItemVariantKey.of(new ItemStack(Items.DIAMOND),p.registryAccess());edit.filter(34,diamond);edit.insert(34,diamond,7);
        edit.filter(35,ItemVariantKey.of(new ItemStack(blocks.get(34)),p.registryAccess()));
        var state=edit.finish();var frame=old;
        ledger.replace(handle,old.state().revision(),frame.withState(state));p.setItemInHand(InteractionHand.MAIN_HAND,FmpRegistries.POCKET_PALLET.toStack());
        // The template world is high above this fixture; give the survival player a real floor
        // and reset the fall accumulated before teleporting into the test area.
        for (int x=4;x<=12;x++) for(int z=4;z<=12;z++)
            p.serverLevel().setBlockAndUpdate(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState());
        p.teleportTo(8.5,4,8.5);p.fallDistance=0;p.setHealth(p.getMaxHealth());
    }
    private static boolean spiral() throws ReflectiveOperationException {var f=PalletScreen.class.getDeclaredField("spiral");f.setAccessible(true);return f.getBoolean(MC.screen);}
    @SuppressWarnings("unchecked") private static List<ItemStack> shown() throws ReflectiveOperationException {var f=PalletScreen.class.getDeclaredField("shown");f.setAccessible(true);return (List<ItemStack>)f.get(MC.screen);}
    private static void releaseTile(int index) throws ReflectiveOperationException {
        var method=PalletScreen.class.getDeclaredMethod("layout");method.setAccessible(true);var layout=(PalletLayout.View)method.invoke(MC.screen);
        var tile=layout.tiles().stream().filter(t->t.index()==index).findFirst().orElseThrow();moveGui(tile.x()+12,tile.y()+12);button(2,false);
    }
    private static void moveSpiral(double steps) throws ReflectiveOperationException {
        var method = PalletScreen.class.getDeclaredMethod("spiralLayout"); method.setAccessible(true);
        var view = (PalletLayout.SpiralView) method.invoke(MC.screen);
        double radius = view.deadRadius() / .35, angle = steps * PalletSpiral.STEP;
        moveGui(view.centerX() + Math.cos(angle) * radius, view.centerY() + Math.sin(angle) * radius);
        if (stage == 3 && steps == Math.rint(steps) && ((int) steps) % 34 == 0) requireFocus(0);
        if (steps == 0 || Math.round(steps * 3) % 12 == 0 || steps == 33) {
            var state = PalletScreen.class.getDeclaredField("navigation"); state.setAccessible(true);
            var navigation = (PalletSpiral) state.get(MC.screen);
            FeedMePackages.LOGGER.info("FMP_PALLET_POINTER inputSteps={} position={} selected={} mouseX={} mouseY={}",
                    steps, navigation.position(), navigation.selected(), MC.mouseHandler.xpos(), MC.mouseHandler.ypos());
        }
    }
    private static void requireFocus(int index) throws ReflectiveOperationException {
        var field = PalletScreen.class.getDeclaredField("focus"); field.setAccessible(true);
        require(((ItemStack) field.get(MC.screen)).is(blocks.get(index)), "Native spiral hover differs at index " + index);
    }
    private static void keyState(int key, boolean down) throws ReflectiveOperationException {
        var method = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(MC.keyboardHandler, MC.getWindow().getWindow(), key, 0, down ? 1 : 0, 0);
    }
    private static void moveGui(double x,double y) throws ReflectiveOperationException {
        var method=MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);method.setAccessible(true);
        method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),x*MC.getWindow().getGuiScale(),y*MC.getWindow().getGuiScale());
        // Client ticks can run several times in one render frame. Drain the native movement dispatcher
        // per sample so it cannot coalesce the starting arc; do not call Screen or PalletSpiral directly.
        MC.mouseHandler.handleAccumulatedMovement();
    }
    private static void button(int button,boolean down) throws ReflectiveOperationException {var method=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),button,down?1:0,0);}
    private static void scroll(double y) throws ReflectiveOperationException {var method=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);method.setAccessible(true);method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0.0,y);}
    private static void key(int key) throws ReflectiveOperationException {var method=KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);method.setAccessible(true);method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,1,0);method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),key,0,0,0);}
    private static void type(String text) throws ReflectiveOperationException {var method=KeyboardHandler.class.getDeclaredMethod("charTyped",long.class,int.class,int.class);method.setAccessible(true);for(int point:text.codePoints().toArray())method.invoke(MC.keyboardHandler,MC.getWindow().getWindow(),point,0);}
    private static void server(Consumer<ServerPlayer> action){var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=CompletableFuture.runAsync(()->action.accept(server.getPlayerList().getPlayer(id)),server);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void next(){FeedMePackages.LOGGER.info("FMP_PALLET_STAGE {} complete",stage);stage++;changed=ticks;}
}
