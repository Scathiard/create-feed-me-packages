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
