package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.concurrent.CompletableFuture;

/**
 * Real client review (mode {@code ui}): controls, panel and tooltips in both languages, crafting toggle,
 * and unbound one-key collection through actual mouse/key input and server inventory settlement.
 */
public final class UiClientReview {
    private static final java.util.UUID HIGHLIGHT=java.util.UUID.randomUUID();
    private static int popupSlot;
    private static java.util.UUID highlightFrequency() throws ReflectiveOperationException {
        var held=com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedClientHandler.class
                .getDeclaredField("previouslyHeldFrequency");
        held.setAccessible(true);
        return (java.util.UUID)held.get(null);
    }
    private static final Minecraft MC = Minecraft.getInstance();
    private static int phase, ticks, changed;
    private static CompletableFuture<Void> work;

    public static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        if (ticks - changed < 12) return false;
        switch (phase) {
            case 0 -> { openInventory(); }
            case 1 -> {
                if (!panelReady()) return false;
                TemplateClientReview.capture("ui-panel-level-zh");
                onServer(UiClientReview::givePendantCopy);
                next();
            }
            case 2 -> {
                if (!hoverPendant()) { changed = ticks; return false; }
                next();
            }
            case 3 -> { if (ticks - changed < 5) return false; TemplateClientReview.capture("ui-pendant-tooltip-zh"); openControls(); }
            case 4 -> { scrollKeysToBottom(); }
            case 5 -> { TemplateClientReview.capture("ui-controls-zh"); switchLanguage("en_us"); }
            case 6 -> { if (MC.getOverlay() != null) return false; openControls(); }
            case 7 -> { scrollKeysToBottom(); }
            case 8 -> { if (MC.getOverlay() != null) return false; TemplateClientReview.capture("ui-controls-en"); openInventory(); }
            case 9 -> {
                if (!panelReady()) return false;
                TemplateClientReview.capture("ui-panel-level-en");
                if (!hoverPendant()) { changed = ticks; return false; }
                next();
            }
            case 10 -> { if (ticks - changed < 5) return false; TemplateClientReview.capture("ui-pendant-tooltip-en"); switchLanguage("zh_cn"); }
            case 11 -> { openInventory(); }
            case 12 -> {
                if (!panelReady()) return false;
                TemplateClientReview.capture("ui-pendant-icon-zh");
                resize(854, 480);
                next();
            }
            case 13 -> {
                if (!panelReady()) return false;
                TemplateClientReview.capture("ui-panel-narrow-zh");
                resize(1280, 800);
                next();
            }
            case 14 -> {
                if (!panelReady()) return false;
                onServer(player -> {
                    ConsumptionTests.seed(player, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG), 8);
                    var recipe = player.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
                    @SuppressWarnings("unchecked") var crafting = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)recipe;
                    require(dev.scathiard.feedmepackages.consumption.CraftingService.place(player, crafting, false, false, true)
                            == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK, "Could not prepare cache-only recipe");
                }); next();
            }
            case 15 -> {
                if (!panelReady() || TemplateClientReview.snapshot().cells().getFirst().amount() != 8) return false;
                require(TemplateClientReview.snapshot().craftingEnabled(), "Fresh pendant must start ON");
                var r = TemplateClientReview.hostLayout().craftingButton(); TemplateClientReview.move(r.x()+6,r.y()+6); next();
            }
            case 16 -> {
                TemplateClientReview.capture("crafting-toggle-on");
                var r = TemplateClientReview.hostLayout().craftingButton(); TemplateClientReview.click(r.x()+6,r.y()+6); next();
            }
            case 17 -> {
                if (TemplateClientReview.snapshot().craftingEnabled() || dev.scathiard.feedmepackages.client.ClientMaterials.craftingEnabled()) return false;
                require(dev.scathiard.feedmepackages.client.ClientMaterials.active() && !dev.scathiard.feedmepackages.client.ClientMaterials.stacks().isEmpty()
                        && dev.scathiard.feedmepackages.client.ClientMaterials.craftingStacks().isEmpty(), "Crafting OFF incorrectly hid all material sources");
                onServer(player -> {
                    require(!dev.scathiard.feedmepackages.consumption.CraftingService.enabled(player), "UI OFF did not reach server");
                    require(dev.scathiard.feedmepackages.consumption.CraftingService.grid(player.containerMenu).getItem(0).isEmpty(), "OFF left an unspent recipe lease");
                    require(ConsumptionTests.stock(player,0)==8, "OFF consumed the preparation");
                }); TemplateClientReview.capture("crafting-toggle-off"); next();
            }
            case 18 -> { MC.screen.onClose(); openInventory(); }
            case 19 -> {
                if (!panelReady()) return false;
                require(!TemplateClientReview.snapshot().craftingEnabled(), "Reopening reset OFF");
                var r = TemplateClientReview.hostLayout().craftingButton(); TemplateClientReview.click(r.x()+6,r.y()+6); next();
            }
            case 20 -> {
                if (!TemplateClientReview.snapshot().craftingEnabled() || !dev.scathiard.feedmepackages.client.ClientMaterials.craftingEnabled()) return false;
                require(!dev.scathiard.feedmepackages.client.ClientMaterials.craftingStacks().isEmpty(), "ON failed to restore client crafting sources");
                TemplateClientReview.capture("crafting-toggle-restored"); next();
            }
            case 21 -> {
                onServer(player -> {
                    require(dev.scathiard.feedmepackages.service.AccessGate.resolve(player).handle().networkId() == null,
                            "Unbound collection fixture unexpectedly has a network");
                    player.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 5));
                    player.getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 3));
                    player.containerMenu.broadcastChanges();
                }); next();
            }
            case 22 -> {
                if (!panelReady()) return false;
                require(!TemplateClientReview.snapshot().bound(), "Client still considers the pendant bound");
                TemplateClientReview.capture("collect-unbound-before");
                var r = TemplateClientReview.hostLayout().transferButton(); TemplateClientReview.click(r.x()+6,r.y()+6); next();
            }
            case 23 -> {
                onServer(player -> {
                    require(ConsumptionTests.stock(player,0)==13 && player.getInventory().getItem(8).isEmpty(),
                            "Unbound collect button failed to move the five matching logs");
                    require(player.getInventory().getItem(9).is(net.minecraft.world.item.Items.COBBLESTONE)
                            && player.getInventory().getItem(9).getCount()==3, "Collect changed unmatched items");
                    player.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
                    player.containerMenu.broadcastChanges();
                }); next();
            }
            case 24 -> {
                if (!panelReady() || TemplateClientReview.snapshot().cells().getFirst().amount()!=13) return false;
                var binding = dev.scathiard.feedmepackages.client.ClientBootstrap.PALLET_COLLECT;
                var previous = binding.getKey();
                try {
                    binding.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(75));
                    var input = new net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre(MC.screen,75,0,0);
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(input);
                    require(input.isCanceled(), "Unbound collection shortcut was not accepted");
                } finally { binding.setKey(previous); }
                next();
            }
            case 25 -> {
                onServer(player -> require(ConsumptionTests.stock(player,0)==15 && player.getInventory().getItem(8).isEmpty()
                        && player.getInventory().getItem(9).getCount()==3, "Unbound shortcut did not preserve item totals"));
                next();
            }
            case 26 -> {
                if (!panelReady() || TemplateClientReview.snapshot().cells().getFirst().amount()!=15) return false;
                TemplateClientReview.capture("collect-unbound-after");
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_UNBOUND_COLLECT_PASSED mouse=5 key=2 unmatched=3 stock=15");
                onServer(player -> {
                    var copy=FmpRegistries.PENDANT.toStack();
                    copy.set(FmpRegistries.NETWORK.get(), HIGHLIGHT);
                    player.getInventory().setItem(player.getInventory().selected,copy);
                    player.inventoryMenu.broadcastChanges();
                });
                next();
            }
            case 27 -> {
                if (!HIGHLIGHT.equals(highlightFrequency())) return false;
                onServer(player -> {
                    player.getMainHandItem().remove(FmpRegistries.NETWORK.get());
                    player.inventoryMenu.broadcastChanges();
                });
                next();
            }
            case 28 -> {
                if (highlightFrequency()!=null) return false;
                var snap=TemplateClientReview.snapshot();
                var layout=TemplateClientReview.hostLayout();
                var cell=layout.cells().stream().filter(c->c.bounds().y()==layout.cells().stream()
                        .mapToInt(other->other.bounds().y()).max().orElseThrow()
                        && c.bounds().x()==layout.cells().stream().mapToInt(other->other.bounds().x()).max().orElseThrow())
                        .findFirst().orElseGet(()->layout.cells().getLast());
                int slot=snap.grid().storageSlot(cell.slot());
                onServer(player->ConsumptionTests.seed(player,slot,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD),5));
                popupSlot=cell.slot();
                next();
            }
            case 29 -> {
                if (TemplateClientReview.snapshot().cells().get(popupSlot).amount()!=5) return false;
                var dot=TemplateClientReview.hostLayout().cells().stream().filter(c->c.slot()==popupSlot).findFirst().orElseThrow().dot();
                TemplateClientReview.click(dot.x()+1,dot.y()+1);
                next();
            }
            case 30 -> {
                var layout=TemplateClientReview.hostLayout();
                require(layout.slider()!=null,"Cell controls failed to open");
                var footer=layout.foldButton(); var popup=layout.slider();
                int x=Math.max(footer.x(),popup.x())+1, y=Math.max(footer.y(),popup.y())+1;
                require(footer.contains(x,y) && popup.contains(x,y),"Fixture did not overlap the footer");
                TemplateClientReview.move(x,y); TemplateClientReview.capture("031-cell-controls-above-footer");
                TemplateClientReview.click(x,y); next();
            }
            case 31 -> {
                var layout=TemplateClientReview.hostLayout();
                require(!layout.hidden() && layout.slider()!=null,"Click on cell settings fell through and folded the panel");
                var dot=layout.cells().stream().filter(c->c.slot()==popupSlot).findFirst().orElseThrow().dot();
                TemplateClientReview.click(dot.x()+1,dot.y()+1); next();
            }
            case 32 -> {
                editAddress(); pasteFixture("退货站🚀");
                pressAddressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER); next();
            }
            case 33 -> {
                if (!"退货站🚀".equals(TemplateClientReview.snapshot().returnAddress())) return false;
                editAddress(); pasteFixture("取消"); pressAddressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
                MC.setScreen(null); next();
            }
            case 34 -> openInventory();
            case 35 -> {
                if (!panelReady()) return false;
                require("退货站🚀".equals(TemplateClientReview.snapshot().returnAddress()),"Address damaged by cancel or reopening");
                dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP031_UI_PASSED nativeNetwork={} unboundCleared=true footerOverlap=true addressCommitCancelReopen=true",HIGHLIGHT);
                return true;
            }
            default -> { }
        }
        return false;
    }

    /**
     * Both the panel session and a container screen have to be up before a panel picture means anything. The
     * snapshot is read through the review helper: a picture without it would show an empty panel.
     */
    static void editAddress() throws ReflectiveOperationException {
        var r=TemplateClientReview.hostLayout().returnBar();
        TemplateClientReview.click(r.x()+r.width()/2,r.y()+r.height()/2);
    }
    static void pasteFixture(String text) throws ReflectiveOperationException {
        // Exercise the editor with a local clipboard fixture. Do not read or replace the host clipboard.
        var method=dev.scathiard.feedmepackages.client.LogisticsPanel.class.getDeclaredMethod("editReturnKey",
                int.class,int.class,java.util.function.Supplier.class);
        method.setAccessible(true);
        int modifier=Minecraft.ON_OSX?org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER:org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
        require((Boolean)method.invoke(null,org.lwjgl.glfw.GLFW.GLFW_KEY_V,modifier,
                (java.util.function.Supplier<String>)()->text),"Paste shortcut refused");
    }
    static void pressAddressKey(int code) {
        var event=new net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre(MC.screen,code,0,0);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(),"Editor did not handle key "+code);
    }
    private static boolean panelReady() throws ReflectiveOperationException {
        return MC.player != null && MC.screen instanceof AbstractContainerScreen<?>
                && TemplateClientReview.snapshot() != null;
    }

    private static void openInventory() {
        MC.setScreen(new InventoryScreen(MC.player));
        next();
    }

    /** Window resize is how the panel's own narrow behaviour is exercised without touching the shipped code. */
    private static void resize(int width, int height) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(MC.getWindow().getWindow(), width, height);
        MC.resizeDisplay();
    }

    private static void openControls() {
        MC.setScreen(new KeyBindsScreen(MC.screen, MC.options));
        next();
    }

    /** The controls list is hundreds of rows long: jump it to the end instead of faking wheel notches. */
    private static void scrollKeysToBottom() throws ReflectiveOperationException {
        var field = KeyBindsScreen.class.getDeclaredField("keyBindsList");
        field.setAccessible(true);
        var list = (net.minecraft.client.gui.components.AbstractSelectionList<?>) field.get(MC.screen);
        list.setScrollAmount(1.0E9);
        next();
    }

    /** The pendant copy needs a server round trip; the client sees it one or two ticks later. */
    private static void onServer(java.util.function.Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID();
        var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }

    private static void givePendantCopy(ServerPlayer player) {
        CuriosApi.getCuriosInventory(player).ifPresent(inventory -> inventory.findCurios("necklace").stream()
                .filter(found -> found.stack().is(FmpRegistries.PENDANT.get()) && !found.slotContext().cosmetic())
                .findFirst().ifPresent(found -> player.getInventory().add(found.stack().copy())));
    }

    /** Moves the cursor onto the copied pendant; false while the new stack has not reached the client yet. */
    private static boolean hoverPendant() throws ReflectiveOperationException {
        if (!(MC.screen instanceof AbstractContainerScreen<?> screen)) return false;
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.getItem().is(FmpRegistries.PENDANT.get())) continue;
            TemplateClientReview.move(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
            return true;
        }
        return false;
    }

    private static void switchLanguage(String code) {
        MC.options.languageCode = code;
        MC.getLanguageManager().setSelected(code);
        work = MC.reloadResourcePacks();
        next();
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }

    private static void next() { phase++; changed = ticks; }
}
