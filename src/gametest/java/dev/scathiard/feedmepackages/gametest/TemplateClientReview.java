package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.TemplateBrowsers;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Scoped reuse of native client drivers; no plugin fixtures or production test hooks are packaged. */
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TemplateClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final String RUN=Long.toString(System.currentTimeMillis());
    private static final String MODE=System.getProperty("fmp.compatReview", "");
    private static int phase,ticks,changed,sourceX,sourceY;
    private static boolean ended;
    private static CompletableFuture<Void> work;
    private static ItemStack first=ItemStack.EMPTY,second=ItemStack.EMPTY;
    private static net.minecraft.client.gui.screens.Screen inventoryScreen;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if(!MODE.isEmpty())event.enqueueWork(()->NeoForge.EVENT_BUS.addListener(TemplateClientReview::tick));
    }
    private static void tick(ClientTickEvent.Post event) {
        if(ended)return;
        ticks++;
        try {
            if(work!=null){if(!work.isDone())return;work.join();work=null;}
            if(ticks-changed>6000)throw new IllegalStateException("Compatibility review timed out: "+MODE+" phase="+phase);
            switch(phase) {
                case 0 -> {
                    if (MC.getOverlay() != null || MC.screen == null) return;
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),1280,800);
                    MC.options.pauseOnLostFocus = false; MC.options.guiScale().set(2); MC.resizeDisplay();
                    MC.options.languageCode = "zh_cn"; MC.getLanguageManager().setSelected("zh_cn");
                    work = MC.reloadResourcePacks(); next();
                }
                case 1 -> {
                    MC.createWorldOpenFlows().createFreshLevel("fmp-template-" + RUN,
                            new LevelSettings("FMP templates " + RUN, GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                                    true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(7319L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), MC.screen);
                    next();
                }
                case 2 -> {
                    if (MC.player == null || MC.screen != null || MC.getSingleplayerServer() == null || MC.player.tickCount < 20) return;
                    server(player -> {
                        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow()
                                .getStacks().setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                        AccessGate.resolve(player);
                    }); next();
                }
                case 3 -> {
                    if(CuriosApi.getCuriosInventory(MC.player).map(inv->inv.findCurios("necklace").stream()
                            .noneMatch(found->found.stack().is(FmpRegistries.PENDANT.get()))).orElse(true))return;
                    if(MODE.equals("templates")){MC.setScreen(new InventoryScreen(MC.player));next();}
                    else {phase=100;changed=ticks;}
                }
                case 4 -> {
                    if (!ready()) return;
                    require(TemplateBrowsers.available(), "No optional browser registered its template provider");
                    cellClick(0, 2); require(target() == 0, "Middle click did not enter selection mode"); next();
                }
                case 5 -> {
                    if (ticks - changed < 10) return;
                    capture("dimmed"); first = findSource(ItemStack.EMPTY);
                    mouse(sourceX, sourceY, 0, 1); mouse(sourceX, sourceY, 0, 0); next();
                }
                case 6 -> {
                    if (!ready()) return;
                    require(target() == -1, "Successful server reply did not close selection mode");
                    server(player -> verify(player, 0, first)); next();
                }
                case 7 -> {
                    if (!ready()) return;
                    cellClick(1, 2); require(target() == 1, "Second cell could not enter selection mode");
                    var original = MC.screen;
                    var key = new ScreenEvent.KeyPressed.Pre(original, 256, 0, 0);
                    NeoForge.EVENT_BUS.post(key); if (!key.isCanceled()) original.keyPressed(256, 0, 0);
                    require(target() == -1 && MC.screen == original, "Escape did not only cancel selection");
                    var binding = dev.scathiard.feedmepackages.client.ClientBootstrap.SELECT_TEMPLATE;
                    var oldKey = binding.getKey();
                    try {
                        binding.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(75));
                        var rebound = new ScreenEvent.KeyPressed.Pre(original, 75, 0, 0);
                        NeoForge.EVENT_BUS.post(rebound);
                        require(rebound.isCanceled() && target() == 1, "Rebound keyboard selector did not use the hovered cell");
                        NeoForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Pre(original, 256, 0, 0));
                        require(target() == -1, "Rebound selection did not cancel");
                    } finally { binding.setKey(oldKey); }
                    second = findSource(first); mouse(sourceX, sourceY, 0, 1); traceBrowserDrag("pressed"); next();
                }
                case 8 -> {
                    if (ticks - changed < 3) return;
                    var bounds = cellBounds(1);
                    move(bounds.x() + 8, bounds.y() + 8); traceBrowserDrag("moved"); next();
                }
                case 9 -> {
                    // Logical client ticks may catch up before a render frame; EMI starts dragging
                    // in MouseHandler.handleAccumulatedMovement, not synchronously in onMove.
                    if (ticks - changed < 10 || !nativeBrowserDragReady()) return;
                    capture("dragging");
                    traceBrowserDrag("before-release");
                    var bounds = cellBounds(1);
                    mouse(bounds.x() + 8, bounds.y() + 8, 0, 0); next();
                }
                case 10 -> {
                    if (!ready()) return;
                    server(player -> { verify(player, 0, first); verify(player, 1, second); });
                    capture("filters"); next();
                }
                case 11 -> {
                    if (!ready()) return;
                    inventoryScreen = MC.screen;
                    findSource(ItemStack.EMPTY, net.minecraft.world.item.Items.OAK_PLANKS);
                    mouse(sourceX, sourceY, 0, 1); mouse(sourceX, sourceY, 0, 0); next();
                }
                case 12 -> {
                    if (ticks - changed < 20) return;
                    require(MC.screen != inventoryScreen, "Ordinary browser click no longer opens recipes");
                    MC.screen.onClose(); require(MC.screen == inventoryScreen, "Recipe browser did not restore inventory; expected="
                            + inventoryScreen.getClass().getName() + " actual=" + (MC.screen == null ? "null" : MC.screen.getClass().getName())); next();
                }
                case 13 -> {
                    if (!ready()) return;
                    server(player -> { verify(player, 0, first); verify(player, 1, second); }); next();
                }
                case 14 -> {
                    FeedMePackages.LOGGER.info("FMP_TEMPLATE_CLIENT_PASSED middleSelect=true escape=true rebind=true drag=true ordinaryRecipeClick=true phantomItems=0");
                    closeReview();
                }
                case 100 -> {
                    boolean complete;
                    if(MODE.equals("jei"))complete=ClientConsumptionReview.tick();
                    else {
                        String name=switch(MODE) {
                            case "emi" -> "EmiClientReview";
                            case "fluid","fluid-input" -> "FluidClientReview";
                            case "capacity" -> "CapacityClientReview";
                            case "fxnt" -> "FxntClientReview";
                            case "sophisticated" -> "SophisticatedClientReview";
                            case "maid" -> "MaidClientReview";
                            case "pallet" -> "PalletClientReview";
                            case "feedback" -> "FeedbackClientReview";
                            case "tacz" -> "TaczClientReview";
                            case "wand" -> "WandClientReview";
                            case "gadgets" -> "GadgetsClientReview";
                            case "effortless" -> "EffortlessClientReview";
                            default -> throw new IllegalArgumentException("Unknown review "+MODE);
                        };
                        var method=Class.forName("dev.scathiard.feedmepackages.gametest."+name)
                                .getDeclaredMethod(MODE.equals("fluid-input")?"inputTick":"tick");
                        method.setAccessible(true);complete=(boolean)method.invoke(null);
                    }
                    if(complete)closeReview();
                }
                case 140 -> {if(ticks-changed>=20){ended=true;MC.stop();}}
                default -> throw new IllegalStateException("Unknown phase "+phase);
            }
        } catch(Throwable failure) {
            ended=true;FeedMePackages.LOGGER.error("FMP_COMPAT_CLIENT_FAILED mode="+MODE+" phase="+phase,failure);
            capture("failed-"+MODE);MC.stop();
        }
    }
    private static void closeReview() {
        FeedMePackages.LOGGER.info("FMP_COMPAT_CLIENT_PASSED mode={}",MODE);
        MC.player.closeContainer();MC.setScreen(null);phase=140;changed=ticks;
    }
    private static boolean ready() { return ticks - changed >= 20 && MC.screen instanceof AbstractContainerScreen<?>
            && LogisticsPanel.visibleCells(MC.screen).size() == 9 && LogisticsPanel.recipeReady(); }
    static dev.scathiard.feedmepackages.network.PanelPackets.Snapshot snapshot() throws ReflectiveOperationException {
        var field=LogisticsPanel.class.getDeclaredField("snapshot"); field.setAccessible(true);
        return (dev.scathiard.feedmepackages.network.PanelPackets.Snapshot)field.get(null);
    }
    static void nativeSlotClick(int index) throws ReflectiveOperationException {
        var screen=(AbstractContainerScreen<?>)MC.screen; var slot=screen.getMenu().getSlot(index);
        click(screen.getGuiLeft()+slot.x+8,screen.getGuiTop()+slot.y+8);
    }
    private static void traceBrowserDrag(String step) throws ReflectiveOperationException {
        if (!net.neoforged.fml.ModList.get().isLoaded("emi")) return;
        var button=MouseHandler.class.getDeclaredField("activeButton"); button.setAccessible(true);
        var type=Class.forName("dev.emi.emi.screen.EmiScreenManager");
        var pressed=type.getDeclaredField("pressedStack"); pressed.setAccessible(true);
        var dragged=type.getDeclaredField("draggedStack"); dragged.setAccessible(true);
        FeedMePackages.LOGGER.info("FMP_BROWSER_DRAG_TRACE step={} active={} pressed={} dragged={} expected={} target={} ready={}",step,
                button.getInt(MC.mouseHandler),pressed.get(null),dragged.get(null),second,target(),LogisticsPanel.recipeReady());
    }
    private static boolean nativeBrowserDragReady() throws ReflectiveOperationException {
        if (!net.neoforged.fml.ModList.get().isLoaded("emi")) return true;
        var field=Class.forName("dev.emi.emi.screen.EmiScreenManager").getDeclaredField("draggedStack"); field.setAccessible(true);
        var drag=(dev.emi.emi.api.stack.EmiIngredient)field.get(null);
        return !drag.isEmpty() && drag.getEmiStacks().size()==1 && ItemStack.isSameItemSameComponents(drag.getEmiStacks().getFirst().getItemStack(),second);
    }
    private static void verify(ServerPlayer player, int slot, ItemStack expected) {
        var state = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state();
        var cell = state.cells().get(slot);
        require(ItemVariantKey.of(expected, player.registryAccess()).equals(cell.filter()) && cell.amount() == 0,
                "Browser did not set the exact empty filter at " + slot + "; expected=" + expected + "; actual=" + cell);
        require(player.getInventory().isEmpty() && player.containerMenu.getCarried().isEmpty(), "A template created a real inventory item");
    }
    private static ItemStack findSource(ItemStack excluded) throws ReflectiveOperationException {
        return findSource(excluded, null);
    }
    private static ItemStack findSource(ItemStack excluded, net.minecraft.world.item.Item wanted) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>) MC.screen;
        for (int y = 26; y < screen.height - 26; y += 8)
            for (int x = screen.getGuiLeft() + screen.getXSize() + 6; x < screen.width - 6; x += 8) {
                move(x, y); var stack = TemplateBrowsers.hovered();
                if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, excluded) || wanted != null && !stack.is(wanted)) continue;
                try { ItemVariantKey.of(stack, MC.player.registryAccess()); } catch (IllegalArgumentException invalid) { continue; }
                sourceX = x; sourceY = y; return stack;
            }
        throw new IllegalStateException("No visible browser ingredient available");
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static int target() throws ReflectiveOperationException {
        var field = LogisticsPanel.class.getDeclaredField("templateTarget"); field.setAccessible(true); return field.getInt(null);
    }
    private static dev.scathiard.feedmepackages.client.PanelLayout.Rect cellBounds(int slot) {
        return LogisticsPanel.visibleCells(MC.screen).stream().filter(cell -> cell.slot() == slot)
                .findFirst().orElseThrow().bounds();
    }
    static void cellClick(int index, int button) throws ReflectiveOperationException {
        var bounds = cellBounds(index);
        mouse(bounds.x() + 8, bounds.y() + 8, button, 1); mouse(bounds.x() + 8, bounds.y() + 8, button, 0);
    }
    static dev.scathiard.feedmepackages.client.PanelLayout hostLayout() throws ReflectiveOperationException {
        var field = LogisticsPanel.class.getDeclaredField("layout"); field.setAccessible(true);
        return (dev.scathiard.feedmepackages.client.PanelLayout)field.get(null);
    }
    static void click(double x, double y) throws ReflectiveOperationException { mouse(x,y,0,1); mouse(x,y,0,0); }
    static void move(double x, double y) throws ReflectiveOperationException {
        var window = MC.getWindow(); var method = MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
        method.setAccessible(true); method.invoke(MC.mouseHandler, window.getWindow(),
                x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight());
    }
    static void mouse(double x, double y, int button, int action) throws ReflectiveOperationException {
        move(x, y); var method = MouseHandler.class.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
        method.setAccessible(true); method.invoke(MC.mouseHandler, MC.getWindow().getWindow(), button, action, 0);
    }
    private static void wheel(double x, double y, double delta) throws ReflectiveOperationException {
        move(x,y); var method=MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);
        method.setAccessible(true); method.invoke(MC.mouseHandler,MC.getWindow().getWindow(),0.0,delta);
    }
    static void capture(String name) {
        Screenshot.grab(MC.gameDirectory, "fmp-template-" + RUN + "-" + name + ".png", MC.getMainRenderTarget(),
                message -> FeedMePackages.LOGGER.info("FMP_TEMPLATE_SCREENSHOT {}", message.getString()));
    }
    private static void next() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_TEMPLATE_CLIENT_PHASE {}", phase); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
