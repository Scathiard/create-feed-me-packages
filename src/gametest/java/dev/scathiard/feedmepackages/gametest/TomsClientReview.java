package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native terminal mouse actions, including the full-inventory clear fallback. */
final class TomsClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final BlockPos POS = new BlockPos(0, -60, 2);
    private static int stage, ticks, changed, hotbarSlot;
    private static CompletableFuture<Void> work;

    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) {
            if (!work.isDone()) return false;
            work.join(); work = null;
        }
        require(ticks - changed < 300, "Tom's terminal timed out at stage " + stage);
        switch (stage) {
            case 0 -> {
                require(net.neoforged.fml.ModList.get().isLoaded("toms_storage"), "Pinned Tom's Storage release is absent");
                server(player -> {
                    player.getInventory().clearContent();
                    ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE_BRICKS), 8);
                    player.getInventory().setItem(0, new ItemStack(Items.STONE_BRICKS, 3));
                    Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("toms_storage", "crafting_terminal"));
                    require(block != null && BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("toms_storage"),
                            "Tom's crafting terminal block is missing");
                    player.serverLevel().setBlockAndUpdate(POS, block.defaultBlockState());
                    BlockEntity terminal = player.serverLevel().getBlockEntity(POS);
                    require(terminal instanceof MenuProvider
                                    && terminal.getClass().getName().equals("com.tom.storagemod.block.entity.CraftingTerminalBlockEntity"),
                            "Real terminal block entity did not exist");
                    player.connection.teleport(.5, -60, .5, 0, 0);
                    player.openMenu((MenuProvider)terminal);
                }); next();
            }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?> screen)
                        || !screen.getMenu().getClass().getName().equals("com.tom.storagemod.menu.CraftingTerminalMenu")
                        || ticks - changed < 12) return false;
                require(LogisticsPanel.visibleCells(screen).isEmpty(),
                        "FMP panel unexpectedly claimed Tom's own terminal screen");
                hotbarSlot = findInventorySlot(screen, 0);
                require(hotbarSlot >= 0, "Tom's menu lacks physical hotbar slot 0");
                TemplateClientReview.capture("toms-terminal-before-craft");
                TemplateClientReview.nativeSlotClick(hotbarSlot); next();
            }
            case 2, 3, 4 -> {
                if (ticks - changed < 4) return false;
                require(MC.player.containerMenu.getCarried().is(Items.STONE_BRICKS),
                        "Physical crafting ingredients were not on the native cursor");
                var screen = (AbstractContainerScreen<?>)MC.screen;
                var slot = screen.getMenu().getSlot(stage - 1);
                TemplateClientReview.mouse(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8, 1, 1);
                TemplateClientReview.mouse(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8, 1, 0);
                next();
            }
            case 5 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().isEmpty()
                        || !MC.player.containerMenu.getSlot(0).getItem().is(Items.STONE_BRICK_SLAB)) return false;
                require(MC.player.containerMenu.getSlot(0).getItem().getCount() == 6,
                        "Tom's native result count was wrong");
                TemplateClientReview.nativeSlotClick(0); next();
            }
            case 6 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    require(player.containerMenu.getCarried().is(Items.STONE_BRICK_SLAB)
                                    && player.containerMenu.getCarried().getCount() == 6,
                            "Tom's native result click did not yield exactly six slabs");
                    require(player.getInventory().getItem(0).isEmpty()
                                    && ConsumptionTests.stock(player, 0) == 8,
                            "Tom's crafting duplicated ingredients or used FMP cache");
                    for (int index = 1; index < 10; index++)
                        require(player.containerMenu.getSlot(index).getItem().isEmpty(),
                                "Tom's crafting grid kept a phantom ingredient");
                }); next();
            }
            case 7 -> {
                if (ticks - changed < 4) return false;
                TemplateClientReview.nativeSlotClick(findInventorySlot((AbstractContainerScreen<?>)MC.screen, 1));
                next();
            }
            case 8 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    require(player.containerMenu.getCarried().isEmpty()
                                    && player.getInventory().getItem(1).is(Items.STONE_BRICK_SLAB),
                            "Native cursor could not return output to the physical inventory");
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        if (player.getInventory().getItem(i).isEmpty())
                            player.getInventory().setItem(i, new ItemStack(Items.BEDROCK));
                    }
                    var terminal = player.serverLevel().getBlockEntity(POS);
                    require(terminal != null, "Tom's terminal vanished before full-inventory fallback");
                    craftingGrid(terminal).setItem(0, new ItemStack(Items.STONE_BRICKS, 2));
                }); next();
            }
            case 9 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getSlot(1).getItem().is(Items.STONE_BRICKS)) return false;
                var screen = (AbstractContainerScreen<?>)MC.screen;
                AbstractWidget clear = clearButton(screen);
                require(clear.visible && clear.active, "Tom's native clear button is unavailable");
                TemplateClientReview.capture("toms-full-inventory-clear");
                TemplateClientReview.click(clear.getX() + clear.getWidth() / 2.0,
                        clear.getY() + clear.getHeight() / 2.0);
                next();
            }
            case 10 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    var terminal = player.serverLevel().getBlockEntity(POS);
                    require(terminal != null && craftingGrid(terminal).getItem(0).isEmpty(),
                            "Native clear left ingredients in Tom's grid");
                    int ground = player.serverLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(POS).inflate(3)).stream().filter(entity ->
                            entity.getItem().is(Items.STONE_BRICKS)).mapToInt(entity -> entity.getItem().getCount()).sum();
                    require(ground == 2, "Full inventory did not route exactly two ingredients to the ground: " + ground);
                    require(ConsumptionTests.stock(player, 0) == 8 && player.getInventory().getItem(1).getCount() == 6,
                            "Clear operation changed FMP cache or crafted output");
                    player.closeContainer();
                }); next();
            }
            case 11 -> {
                if (ticks - changed < 8 || MC.screen != null) return false;
                FeedMePackages.LOGGER.info("FMP_TOMS_CLIENT_PASSED nativeTerminal=true nativeCraft=true "
                        + "physicalInput=3 physicalOutput=6 cacheUnchanged=8 fullBagClearGround=2 panelNotMounted=true");
                return true;
            }
        }
        return false;
    }

    private static int findInventorySlot(AbstractContainerScreen<?> screen, int index) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().getSlot(i);
            if (slot.container == MC.player.getInventory() && slot.getContainerSlot() == index) return i;
        }
        return -1;
    }

    private static AbstractWidget clearButton(AbstractContainerScreen<?> screen) throws ReflectiveOperationException {
        Field field = screen.getClass().getDeclaredField("btnClr");
        field.setAccessible(true);
        return (AbstractWidget)field.get(screen);
    }

    private static CraftingContainer craftingGrid(BlockEntity terminal) {
        try { return (CraftingContainer)terminal.getClass().getMethod("getCraftingInv").invoke(terminal); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_TOMS_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
