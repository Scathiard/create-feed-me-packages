package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import dev.scathiard.feedmepackages.FeedMePackages;
import java.io.IOException;
import dev.scathiard.feedmepackages.client.PanelLayout;
import dev.scathiard.feedmepackages.client.SupplyCreativeScreen;
import dev.scathiard.feedmepackages.client.SupplyInventoryScreen;
import dev.scathiard.feedmepackages.domain.CacheDropTarget;
import dev.scathiard.feedmepackages.domain.CacheGrid;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.mixin.ContainerScreenAccess;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.service.AccessGate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/** Recovered from test.42; pixel-atlas and layout corrections only. */
public final class LogisticsPanel {
    private static final Minecraft MC = Minecraft.getInstance();
    /**
     * The three 13x13 button sheets the user drew ({@code 参考/Button_13X13 - 转移.png}, {@code - 收纳.png} and
     * {@code - 展开.png}): black frame, grey face, and the icon <b>baked into the sheet</b> - a "drop it into the
     * box" glyph for the one-key collect, a right-pointing chevron for the fold button, and a left-pointing chevron
     * for the entry left on screen while the panel is hidden. Each is drawn <b>1:1</b> (the layout rect IS the sheet
     * size, so there is no scale factor) and the direction is simply which sheet is used: no mirroring, no column
     * mapping, no negative scaling anywhere. Because the image is the whole button there is no separate face fill
     * either, which makes "the face covers the arrow" impossible: that was the earlier "grey square" bug, where
     * {@link #overlay} drew at {@link #BUTTON_Z} while the icon was drawn at z = 0.
     *
     * <p>Shipped names (the user's mapping): {@code button.png} = transfer, {@code button_fold.png} = fold (收纳),
     * {@code button_unfold.png} = unfold (展开). The transfer bytes reached {@code button.png} by <b>renaming</b>
     * the sheet that used to sit there - no sheet was overwritten and no sheet's bytes were dropped: the 7x7 sheet
     * that {@code button.png} held two rounds ago became the fold sheet's slot in turn, and every earlier byte is
     * still recoverable from {@code 参考/} and from the git history.
     */
    private static final ResourceLocation BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "create_feed_me_packages", "textures/gui/button.png");
    /** The fold sheet (收纳, right-pointing chevron): the fold button while the panel is expanded. */
    private static final ResourceLocation BUTTON_FOLD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "create_feed_me_packages", "textures/gui/button_fold.png");
    /** The unfold sheet (展开, left-pointing chevron): the entry left on screen while the panel is hidden. */
    private static final ResourceLocation BUTTON_UNFOLD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "create_feed_me_packages", "textures/gui/button_unfold.png");
    /**
     * The user-authored cell background. The shipped sheet is exactly this size - the crop that moved the drawn
     * block to (0,0) - so the blit reads the whole texture at 1:1. See {@link #renderSlot}.
     */
    private static final ResourceLocation SLOT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "create_feed_me_packages", "textures/gui/slot_source.png");
    /** Edge of the cropped slot sheet in pixels (was the 18x18 block at (101,65) of the 256x256 reference). */
    private static final int SLOT_SIZE = 18;
    /**
     * The layer the panel's own buttons and flat overlays live on. {@link #overlay} has always drawn here, and
     * the button sheet must use the SAME layer: the "grey square, no arrow" bug was an icon drawn at z = 0
     * underneath a face fill at {@code +BUTTON_Z}.
     */
    static final float BUTTON_Z = 190.0f;
    /** Hover highlight / disabled veil sit strictly above the sheet, so they can never hide it. */
    static final float BUTTON_MARK_Z = 191.0f;
    private static final int BUTTON_HOVER_MARK = 0x28FFFFFF;
    /** Veil over the whole button when the action is unavailable (keeps the old "greyed out" reading). */
    private static final int BUTTON_DISABLED_VEIL = 0xB0000000;

    private static AbstractContainerScreen<?> screen;
    private static UUID window;
    private static PanelPackets.Snapshot snapshot;
    private static PanelLayout layout;
    private static final Map<String, ItemStack> ICONS;
    private static final Map<String, net.neoforged.neoforge.fluids.FluidStack> FLUID_ICONS = new HashMap<>();
    private static int tick;
    private static int lastQuery;
    private static int sequence;
    private static int waiting;
    private static int waitingSince;
    private static int firstRow;
    private static int selected;
    private static final Map<Integer, CellControlDraft> controlDrafts = new HashMap<>();
    private static int dragStartValue;
    private static CellControlDraft.Context dragContext;
    private static long serial;
    private static boolean draggingSlider;
    private static boolean draggingMaximum;
    /** Drag origin and "did the pointer actually move" so pressing an endpoint without moving never
     *  rewrites the stored value (both test.57 candidates had that defect). */
    private static double dragOriginX;
    private static boolean dragMoved;
    /** A threshold change that could not be sent yet (an address submit was in flight). It is flushed
     *  as soon as that command settles, so "edit the address, then drag the slider" still works. */
    private static int capturedButton;
    private static int passthroughReleaseButton = -1;
    /** Local view state only: folding never disables logistics or changes cache contents. */
    private static final PanelVisibilityState VISIBILITY = new PanelVisibilityState(
            MC.gameDirectory.toPath().resolve("config").resolve(PanelVisibilityState.FILE_NAME));
    private static boolean hidden;
    /** The sequence whose result must stay silent: the one-key collect (user's silence rule). */
    private static int silentCollectSequence;
    private static boolean returnEditing;
    private static String returnBuffer;
    private static int pendingReturnSequence;
    private static UUID pendingReturnWindow;
    private static UUID pendingReturnSession;
    private static String pendingReturnBuffer;
    private static QueuedPanelClick queuedReturnClick;
    /** Client-side display prediction for the single in-flight panel intent. It only overrides one
     *  cell's rendered number during the pre-confirmation wait; it never creates item stacks, never
     *  touches the cache, backpack or cursor, and is cleared on ack/close/swap/timeout. */
    private static Predict predict;

    /** Local ownership of THIS client's unplaced preview, used to withdraw only our preview on close.
     *  Never derived from snapshot.reserved (which is the sum of every player's Hold for that cell):
     *  it would destroy an independent same-variant creative carry from another player's reservation. */
    private static String previewVariant;
    private static int previewRemaining;
    private static int previewSeq;
    private static int cursorOperation;
    private static long cursorSerial;
    private static UUID cursorSession;
    private static ItemStack cursorExpected = ItemStack.EMPTY;
    private static boolean cursorPending;
    private static final Map<Integer, CacheActions.Intent> pendingReleases = new HashMap<>();
    private static PendingCursorChange pendingCursorChange;
    private static final class PendingCursorChange {
        final UUID window, session; final int sequence;
        boolean closed; ItemStack expected;
        PendingCursorChange(UUID window, UUID session, int sequence, ItemStack before) {
            this.window=window; this.session=session; this.sequence=sequence; this.expected=before.copy();
        }
        boolean matches(UUID window, UUID session, int sequence) {
            return this.window.equals(window) && this.session.equals(session) && this.sequence == sequence;
        }
    }
    public record CreativeClick(int sequence, int mode, int take, int remaining, ItemStack before) {}
    /** Whether the TAKE_CURSOR was confirmed by the server (vs. still awaiting) so an empty carry is
     *  never mistaken for a replaced preview during the request/confirm gap. */
    private static boolean previewConfirmed;

    private record Predict(UUID window, UUID session, int slot, String variant, int sequence, long baseSerial, int result) {}
    private enum QueuedClickKind {
        COPY_ADDRESS, SCROLL, TOGGLE_SLOT, CLEAR_FILTER, DEPOSIT, DEPOSIT_FLUID, TAKE_INVENTORY, TAKE_CURSOR
    }
    private record QueuedPanelClick(UUID window, UUID session, QueuedClickKind kind, int slot, int button, int row, String template) {}
    private static Component feedback;
    private static int feedbackUntil;
    private static List<Component> tooltip;
    private static int mouseX;
    private static int mouseY;
    private static final Map<String, Predicate<Screen>> recipeOverlays = new java.util.concurrent.ConcurrentHashMap<>();
    private static int templateTarget = -1, templateSequence, earlyTemplateButton = -1;
    private record BrowserDrag(Screen screen, double x, double y, TemplateBrowsers.Template template) {}
    private static BrowserDrag browserDrag;
    private static boolean retainTransition;
    private static int overlayBottomInset;
    private static final ResourceLocation PANEL;

    private LogisticsPanel() {
    }

    public static void recipeOverlay(String browser, Predicate<Screen> predicate) {
        recipeOverlays.put(browser, Objects.requireNonNull(predicate));
    }
    private static boolean isRecipeOverlay(Screen candidate) { return recipeOverlays.values().stream().anyMatch(p -> p.test(candidate)); }

    public static void overlayBottomInset(int pixels) {
        overlayBottomInset = Math.clamp((long)pixels, (int)0, (int)64);
    }

    public static void register() {
        try {
            hidden = VISIBILITY.load();
        } catch (IOException | SecurityException failure) {
            hidden = false;
            FeedMePackages.LOGGER.warn("Could not read panel visibility; starting expanded", failure);
        }
        PanelNetwork.receiveOnClient(LogisticsPanel::receive);
        PanelNetwork.receiveCursorOnClient(LogisticsPanel::receiveCursor);
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ScreenEvent.Opening event) -> {
            boolean bl = retainTransition = window != null && (event.getNewScreen() == screen || isRecipeOverlay(event.getNewScreen()));
            if (LogisticsPanel.MC.player == null || event.getNewScreen() == null) {
                return;
            }
            ICuriosItemHandler inventory = CuriosApi.getCuriosInventory((LivingEntity)LogisticsPanel.MC.player).orElse(null);
            if (inventory == null || inventory.findCurios(new String[]{"necklace"}).stream().noneMatch(found -> !found.slotContext().cosmetic() && found.stack().getItem() instanceof PendantItem)) {
                return;
            }
            if (event.getNewScreen().getClass() == InventoryScreen.class) {
                event.setNewScreen((Screen)new SupplyInventoryScreen((Player)LogisticsPanel.MC.player));
            } else if (event.getNewScreen().getClass() == CreativeModeInventoryScreen.class) {
                event.setNewScreen((Screen)new SupplyCreativeScreen(LogisticsPanel.MC.player));
            }
        });
        bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> LogisticsPanel.tick());
        bus.addListener((ScreenEvent.Render.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen())) {
                LogisticsPanel.updateLayout();
            }
        });
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, LogisticsPanel::foreground);
        bus.addListener(LogisticsPanel::postRender);
        // Observe the browser's press without consuming it: short clicks must still open recipes.
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                (ScreenEvent.MouseButtonPressed.Pre event) -> {
                    browserDrag = null;
                    if (current(event.getScreen()) && recipeReady() && templateTarget < 0 && event.getButton() == 0
                            && MC.player.containerMenu.getCarried().isEmpty() && !layout.bounds().contains(event.getMouseX(), event.getMouseY())) {
                        var source = TemplateBrowsers.selection();
                        if (!source.isEmpty()) browserDrag = new BrowserDrag(event.getScreen(), event.getMouseX(), event.getMouseY(), source);
                    }
                });
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.press(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.MouseButtonReleased.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.release(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        });
        // Run after browser input routing, including a consumed release, so its pending click
        // is cleared normally. Only an actual drag ending inside a writable FMP cell is a drop.
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, true,
                (ScreenEvent.MouseButtonReleased.Pre event) -> {
                    var drag = browserDrag; browserDrag = null;
                    if (drag == null || event.getButton() != 0 || drag.screen() != event.getScreen()
                            || MC.screen != event.getScreen() || !current(event.getScreen())
                            || Math.pow(event.getMouseX() - drag.x(), 2) + Math.pow(event.getMouseY() - drag.y(), 2) < 64) return;
                    for (var cell : visibleCells(event.getScreen())) if (cell.bounds().contains(event.getMouseX(), event.getMouseY())) {
                        setTemplate(cell.slot(), drag.template()); return;
                    }
                });
        bus.addListener((ScreenEvent.MouseDragged.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen()) || !LogisticsPanel.visible()) {
                return;
            }
            if (draggingSlider) {
                LogisticsPanel.updateSliderDrag(event.getMouseX(), false);
            }
            if (draggingMaximum) {
                LogisticsPanel.updateSliderDrag(event.getMouseX(), true);
            }
            if (waiting != 0 || draggingSlider || draggingMaximum || capturedButton >= 0 || layout.bounds().contains(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.MouseScrolled.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.scroll(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen())) {
                return;
            }
            if (returnEditing && LogisticsPanel.editReturnKey(event.getKeyCode())) {
                event.setCanceled(true);
                return;
            }
            if (LogisticsPanel.key(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.CharacterTyped.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen())) {
                return;
            }
            if (returnEditing) {
                if (returnBuffer.length() < 128 && event.getCodePoint() >= ' ' && event.getCodePoint() <= '\uffff') {
                    returnBuffer = returnBuffer + new String(Character.toChars(event.getCodePoint()));
                }
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.Closing event) -> {
            if (event.getScreen() == screen && !retainTransition) {
                LogisticsPanel.close();
            }
        });
    }

    /** Invoked around the actual native method, never from a speculative mouse press. */
    public static CreativeClick beforeCreativeClick(Screen owner, boolean list, boolean destroy,
            net.minecraft.world.inventory.Slot slot, int button, net.minecraft.world.inventory.ClickType type) {
        if (pendingCursorChange != null) return new CreativeClick(-1, 0, 0, 0, ItemStack.EMPTY);
        if (!current(owner) || !(owner instanceof CreativeModeInventoryScreen) || MC.player == null) return null;
        if (cursorPending) return new CreativeClick(-1, 0, 0, 0, ItemStack.EMPTY);
        if (!previewConfirmed || previewRemaining <= 0 || snapshot == null) return null;
        ItemStack before = MC.player.containerMenu.getCarried().copy();
        try {
            if (before.isEmpty() || !ItemStack.isSameItemSameComponents(before,
                    ItemVariantKey.decode(previewVariant, MC.player.registryAccess()).stack(MC.player.registryAccess(), 1))) {
                askServerReleasePreview(); previewRemaining=0; previewVariant=null; previewConfirmed=false;
                return null;
            }
        } catch (IllegalArgumentException invalid) { return null; }
        boolean pickup = type == net.minecraft.world.inventory.ClickType.PICKUP
                || type == net.minecraft.world.inventory.ClickType.QUICK_MOVE;
        int mode = destroy || list && pickup ? 1 : list
                || type == net.minecraft.world.inventory.ClickType.SWAP
                || type == net.minecraft.world.inventory.ClickType.CLONE
                || type == net.minecraft.world.inventory.ClickType.QUICK_MOVE
                || type == net.minecraft.world.inventory.ClickType.THROW && slot != null ? 2 : 0;
        int seq = sendCreativeOperation(CacheActions.Action.CREATIVE_BEGIN, previewSeq, mode, before);
        return new CreativeClick(seq, mode, previewSeq, previewRemaining, before);
    }

    public static void afterCreativeClick(CreativeClick click) {
        if (click == null || click.sequence() <= 0 || MC.player == null) return;
        ItemStack after = MC.player.containerMenu.getCarried().copy();
        boolean same = !after.isEmpty() && ItemStack.isSameItemSameComponents(after, click.before());
        previewRemaining = same ? Math.min(click.remaining(), after.getCount()) : 0;
        int end = sendCreativeOperation(CacheActions.Action.CREATIVE_END, click.take(), click.sequence(), same || after.isEmpty() ? after : null);
        cursorOperation = end;
        cursorExpected = after.copy();
        if (click.mode() == 0 && (same || after.isEmpty()) && click.before().getCount() > click.remaining())
            pendingCursorChange = new PendingCursorChange(window, cursorSession, end, after);
        // List replacement is final locally: the next same-tick independent pickup must remain free.
        // Transfer results are serialized until authoritative acceptance/rejection arrives.
        cursorPending = click.mode() == 0 && (!same || after.getCount() < click.before().getCount());
        if (click.mode() == 1 && previewRemaining == 0) {
            askServerReleasePreview();
            previewVariant = null; previewConfirmed = false; cursorOperation = 0;
        }
    }

    private static int sendCreativeOperation(CacheActions.Action action, int take, int detail, ItemStack cursor) {
        int seq = ++sequence;
        String template = cursor == null || cursor.isEmpty() ? "" : ItemVariantKey.of(cursor, MC.player.registryAccess()).encoded();
        var intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), action, cursor == null ? -1 : cursor.getCount(), take, detail, template);
        PacketDistributor.sendToServer(new PanelPackets.Command(window, seq, intent, false, "", 0));
        return seq;
    }

    private static void receiveCursor(PanelPackets.CursorUpdate packet) {
        // A submitted real transfer still owns its input until acknowledgement. Closing cancels only
        // its preview portion. Settle its real remainder before allowing the next native cursor use.
        if (pendingCursorChange != null && pendingCursorChange.closed && MC.player != null
                && pendingCursorChange.matches(packet.window(), packet.session(), packet.operationSequence())) {
            try {
                ItemStack remaining = packet.count() == 0 && packet.template().isEmpty() ? ItemStack.EMPTY
                        : ItemVariantKey.decode(packet.template(), MC.player.registryAccess()).stack(MC.player.registryAccess(), packet.count());
                if (packet.remaining() < 0 || packet.remaining() > remaining.getCount()
                        || !ItemStack.matches(pendingCursorChange.expected, MC.player.containerMenu.getCarried())) return;
                remaining.shrink(packet.remaining());
                MC.player.containerMenu.setCarried(remaining);
                pendingCursorChange = null;
            } catch (IllegalArgumentException invalid) { notice("result.invalid_item"); }
            return;
        }
        if (window == null || MC.player == null || !(screen instanceof CreativeModeInventoryScreen)
                || MC.screen != screen || !window.equals(packet.window())
                || !Objects.equals(cursorSession, packet.session()) || packet.operationSequence() != cursorOperation
                || packet.updateSequence() <= cursorSerial || packet.takeSequence() != previewSeq
                || !ItemStack.matches(cursorExpected, MC.player.containerMenu.getCarried())) return;
        if (packet.count() == -1 && packet.remaining() == 0 && packet.template().isEmpty()) {
            cursorSerial = packet.updateSequence(); cursorPending = false;
            previewRemaining = 0; previewConfirmed = false; previewVariant = null;
            return; // Acknowledge transfer; leave the unrelated native cursor completely untouched.
        }
        try {
            ItemStack stack = packet.count() == 0 && packet.template().isEmpty() ? ItemStack.EMPTY
                    : ItemVariantKey.decode(packet.template(), MC.player.registryAccess()).stack(MC.player.registryAccess(), packet.count());
            if (packet.count() < 0 || packet.count() > stack.getMaxStackSize()
                    || packet.remaining() < 0 || packet.remaining() > packet.count()) return;
            cursorSerial = packet.updateSequence();
            MC.player.containerMenu.setCarried(stack);
            cursorExpected = stack.copy();
            previewRemaining = packet.remaining();
            previewVariant = previewRemaining > 0 ? packet.template() : null;
            previewConfirmed = previewRemaining > 0;
            cursorPending = false;
            if (pendingCursorChange != null && pendingCursorChange.matches(packet.window(), packet.session(), packet.operationSequence())) pendingCursorChange = null;
        } catch (IllegalArgumentException invalid) { notice("result.invalid_item"); }
    }

    private static boolean supported(Screen candidate) {
        return candidate instanceof InventoryScreen || candidate instanceof CreativeModeInventoryScreen || candidate instanceof CraftingScreen;
    }

    private static boolean current(Screen candidate) {
        return candidate == screen && LogisticsPanel.supported(candidate) && window != null;
    }

    private static boolean visible() {
        return snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN && layout != null;
    }

    private static boolean active() {
        return snapshot != null && snapshot.status() == AccessGate.Status.ACTIVE && snapshot.session() != null;
    }

    /** Read-only state for the JEI transfer handler, which must not invent its own panel bookkeeping. */
    public static boolean cacheLive() { return active(); }
    /** A live panel whose pendant carries no network binding: it serves the cache but cannot restock. */
    public static boolean unbound() { return snapshot != null && !snapshot.bound(); }

    private static boolean bookOpen() {
        RecipeUpdateListener listener;
        AbstractContainerScreen<?> abstractContainerScreen = screen;
        return abstractContainerScreen instanceof RecipeUpdateListener && (listener = (RecipeUpdateListener)abstractContainerScreen).getRecipeBookComponent().isVisible();
    }

    private static void tick() {
        boolean overlay;
        ++tick;
        if (MC.player == null || MC.getConnection() == null) pendingCursorChange = null;

        boolean bl = overlay = isRecipeOverlay(LogisticsPanel.MC.screen) && window != null;
        if (LogisticsPanel.MC.player == null || MC.getConnection() == null || !LogisticsPanel.supported(LogisticsPanel.MC.screen) && !overlay) {
            if (window != null) {
                LogisticsPanel.close();
            }
            return;
        }
        if (!overlay && screen != LogisticsPanel.MC.screen) {
            LogisticsPanel.mount((AbstractContainerScreen)LogisticsPanel.MC.screen);
        }
        if (waiting != 0 && tick - waitingSince > 60) {
            for(var draft:controlDrafts.values()) draft.timedOut(waiting);
            waiting = 0;
            predict = null;   // Timeout is an unknown result: drop the display prediction only, and refresh.
            LogisticsPanel.notice("timeout");
        }
        flushPendingThresholds();
        if (tick - lastQuery >= 10) {
            lastQuery = tick;
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Query(window, LogisticsPanel.MC.player.containerMenu.containerId, true), (CustomPacketPayload[])new CustomPacketPayload[0]);
        }
    }

    /** Our preview was replaced/emptied on the creative list, so release our own panel-session hold on
     *  the server; otherwise creativeAfter would debit a later independent same-variant placement. */
    private static void askServerReleasePreview() {
        if (snapshot == null || window == null || LogisticsPanel.MC.player == null) return;
        try {
            var intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), CacheActions.Action.RELEASE_PREVIEW, -1, previewSeq, -1, "");
            // Cleanup acknowledgements are tracked separately from ordinary UI predictions.
            int seq = ++sequence;
            pendingReleases.put(seq, intent);
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Command(window, seq, intent, false, "", 0), (CustomPacketPayload[])new CustomPacketPayload[0]);
        } catch (IllegalArgumentException invalid) { /* stale template; nothing to release */ }
    }

    public static void mount(AbstractContainerScreen<?> value) {
        if (screen == value && window != null) {
            return;
        }
        LogisticsPanel.close();
        screen = value;
        window = UUID.randomUUID();
        lastQuery = tick - 10;
    }

    public static void unmount(AbstractContainerScreen<?> value) {
        if (screen == value && !retainTransition) {
            LogisticsPanel.close();
        }
    }

    private static void close() {
        templateTarget = -1; templateSequence = 0; earlyTemplateButton = -1; browserDrag = null;
        if(MC.getConnection()!=null && MC.player!=null && active() && window!=null) submitControls(true);
        controlDrafts.clear();
        dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_CLOSE_PREVIEW confirmed={} variant={} remaining={} carry={}",
                previewConfirmed, previewVariant, previewRemaining, LogisticsPanel.MC.player == null ? null : LogisticsPanel.MC.player.containerMenu.getCarried());
        // 只撤销本次 本地 FMP 预览（客户端维护的 previewRemaining），绝不按 snapshot.reserved(全局合计)撤。
        // 创造光标由客户端持有：TAKE_CURSOR 只发全量包、服务端 carried 为空；本地预览量由预览关联追踪，
        // 并在光标被创造列表替换（变体变化/清空）时失效（updatePreviewOwnership），保留真实/独立/新光标。
        if (screen instanceof CreativeModeInventoryScreen && LogisticsPanel.MC.player != null) {
            var carried = LogisticsPanel.MC.player.containerMenu.getCarried();
            if (previewConfirmed && previewRemaining > 0 && previewVariant != null && !carried.isEmpty()) {
                try {
                    var variant = ItemVariantKey.decode(previewVariant, (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess());
                    if (ItemStack.isSameItemSameComponents(carried, variant.stack((HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess(), 1))) {
                        ItemStack next = carried.copy();
                        next.setCount(Math.max(0, next.getCount() - previewRemaining));
                        LogisticsPanel.MC.player.containerMenu.setCarried(next.isEmpty() ? ItemStack.EMPTY : next);
                    }
                }
                catch (IllegalArgumentException invalid) { /* not our variant; leave the carry untouched */ }
            }
        }
        if (pendingCursorChange != null && !pendingCursorChange.closed && MC.player != null) {
            pendingCursorChange.closed = true;
            pendingCursorChange.expected = MC.player.containerMenu.getCarried().copy();
        }
        previewRemaining = 0; previewVariant = null; previewConfirmed = false;
        previewSeq = 0; cursorOperation = 0; cursorSerial = 0; cursorSession = null; cursorPending = false;
        cursorExpected = ItemStack.EMPTY; pendingReleases.clear();
        if (window != null && MC.getConnection() != null && LogisticsPanel.MC.player != null) {
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Query(window, LogisticsPanel.MC.player.containerMenu.containerId, false), (CustomPacketPayload[])new CustomPacketPayload[0]);
        }
        screen = null;
        window = null;
        snapshot = null;
        CellFeedback.clear();
        layout = null;
        waiting = 0;
        predict = null;
        serial = -1L;
        selected = -1;
        firstRow = 0;
        draggingSlider = false;
        draggingMaximum = false;
        dragMoved = false;
        capturedButton = -1;
        passthroughReleaseButton = -1;
        returnEditing = false;
        returnBuffer = "";
        pendingReturnSequence = 0;
        pendingReturnWindow = null;
        pendingReturnSession = null;
        pendingReturnBuffer = null;
        queuedReturnClick = null;
        tooltip = List.of();
        ICONS.clear(); FLUID_ICONS.clear();
        feedback = null;
    }
    public static void beforeContainerClose(AbstractContainerScreen<?> closing) {
        if(screen==closing && MC.getConnection()!=null && active() && window!=null) submitControls(true);
    }

    private static void receive(PanelPackets.Snapshot incoming) {
        if (incoming != null && pendingCursorChange != null
                && pendingCursorChange.window.equals(incoming.window()) && pendingCursorChange.sequence == incoming.acknowledged()
                && incoming.result() != CacheActions.Result.OK) pendingCursorChange = null;
        if (incoming == null || window == null || MC.screen != screen && !isRecipeOverlay(MC.screen)
                || !window.equals(incoming.window())) return;
        // A later processed sequence also proves earlier bounded cleanup commands were handled.
        pendingReleases.keySet().removeIf(seq -> seq <= incoming.acknowledged());
        boolean runQueuedReturn = false;
        if (incoming.acknowledged() != 0 && incoming.acknowledged() == waiting) {
            waiting = 0;
            predict = null;
            if (incoming.result() != CacheActions.Result.OK) {
                if (incoming.acknowledged() != silentCollectSequence)
                    notice("result." + incoming.result().name().toLowerCase(Locale.ROOT));
                if (LogisticsPanel.pendingReturnMatches(incoming)) {
                    returnEditing = true;
                    returnBuffer = pendingReturnBuffer == null ? "" : pendingReturnBuffer;
                    pendingReturnSequence = 0;
                    pendingReturnWindow = null;
                    pendingReturnSession = null;
                    pendingReturnBuffer = null;
                    queuedReturnClick = null;
                }
                // A failed DEPOSIT does not revoke an existing preview. A failed TAKE never grants one.
                if (!previewConfirmed && incoming.acknowledged() == cursorOperation) {
                    previewVariant = null; previewRemaining = 0;
                }
                cursorPending = false;
            } else if (LogisticsPanel.pendingReturnMatches(incoming)) {
                pendingReturnSequence = 0;
                pendingReturnWindow = null;
                pendingReturnSession = null;
                pendingReturnBuffer = null;
                runQueuedReturn = true;
            }
        }
        if (incoming.serial() <= serial) {
            if (incoming.acknowledged()!=0) for(var draft:controlDrafts.values()) if(currentDraft(draft))
                draft.snapshot(settings(snapshot.cells().get(draft.context().slot())),incoming.acknowledged(),incoming.result()==CacheActions.Result.OK);
            return;
        }
        boolean wasVisible = snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN;
        serial = incoming.serial();
        CellFeedback.accept(snapshot, incoming, net.minecraft.Util.getMillis());
        if (snapshot != null && !Objects.equals(snapshot.session(), incoming.session())) templateTarget = -1;
        snapshot = incoming;
        if (templateSequence != 0 && incoming.acknowledged() == templateSequence) {
            templateSequence = 0;
            if (incoming.result() == CacheActions.Result.OK) templateTarget = -1;
        }
        if (!active() || templateTarget >= incoming.cells().size()
                || templateTarget >= 0 && incoming.cells().get(templateTarget).amount() != 0) templateTarget = -1;
        controlDrafts.entrySet().removeIf(entry -> !currentDraft(entry.getValue()));
        if (dragContext != null && (snapshot.session() == null || !snapshot.session().equals(dragContext.session())
                || !controlDrafts.containsKey(dragContext.slot()) || !controlDrafts.get(dragContext.slot()).context().equals(dragContext))) {
            draggingSlider=false; draggingMaximum=false; dragContext=null;
        }
        for (var draft : controlDrafts.values()) {
            var cell = snapshot.cells().get(draft.context().slot());
            draft.snapshot(settings(cell), incoming.acknowledged(), incoming.result() == CacheActions.Result.OK);
        }
        if (wasVisible && incoming.status() == AccessGate.Status.NOT_WORN) {
            screen.init(MC, LogisticsPanel.screen.width, LogisticsPanel.screen.height);
        }
        if (incoming.acknowledged() != 0 && incoming.acknowledged() == waiting) {
            waiting = 0;
            if (incoming.result() != CacheActions.Result.OK
                    && incoming.acknowledged() != silentCollectSequence) {
                LogisticsPanel.notice("result." + incoming.result().name().toLowerCase(Locale.ROOT));
            }
        }
        if (incoming.acknowledged() == silentCollectSequence) silentCollectSequence = 0;
        if (!LogisticsPanel.active()) {
            selected = -1;
            draggingSlider = false;
            draggingMaximum = false;
        }
        if (selected >= incoming.cells().size() || selected >= 0 && incoming.cells().get(selected).template().isEmpty()) {
            selected = -1;
        }
        HashSet<String> present = new HashSet<String>();
        for (PanelPackets.CellView cell : incoming.cells()) {
            if (cell.template().isEmpty()) continue;
            present.add(cell.template());
            if (cell.fluid()) {
                try { FLUID_ICONS.put(cell.template(), dev.scathiard.feedmepackages.fluid.FluidVariantKey.decode(cell.template(), MC.player.registryAccess()).stack(MC.player.registryAccess(),1)); }
                catch (IllegalArgumentException invalid) { notice("invalid_display"); }
                continue;
            }
            if (ICONS.containsKey(cell.template())) continue;
            try {
                ICONS.put(cell.template(), ItemVariantKey.decode(cell.template(), (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess()).stack((HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess(), 1));
            }
            catch (IllegalArgumentException invalid) {
                LogisticsPanel.notice("invalid_display");
            }
        }
        ICONS.keySet().retainAll(present); FLUID_ICONS.keySet().retainAll(present);
        LogisticsPanel.updateLayout();
        if (runQueuedReturn) {
            LogisticsPanel.runQueuedPanelClick();
        }
        // A slider change queued behind an in-flight command can go out now.
        LogisticsPanel.flushPendingThresholds();
    }

    static boolean feedbackVisible(int slot) {
        return layout != null && !hidden && !layout.compact() && layout.cells().stream().anyMatch(box -> box.slot() == slot);
    }
    private static void updateLayout() {
        if (screen == null || snapshot == null || snapshot.status() == AccessGate.Status.NOT_WORN) {
            layout = null;
            return;
        }
        int count = LogisticsPanel.active() ? snapshot.cells().size() : 0;
        CacheGrid grid = CacheGrid.forCount(count);
        if (hidden) {
            CellFeedback.clear();
            // Fully hidden: only the entry square exists, at the place the fold button had. The inventory screen
            // is deliberately NOT moved - the entry must not jump, and the layout keeps the same anchor.
            layout = PanelLayout.hidden(LogisticsPanel.screen.height, screen.getGuiLeft(), screen.getGuiTop(), grid, LogisticsPanel.bookOpen());
            firstRow = 0;
            return;
        }
        int availableColumns = Math.max(2, (LogisticsPanel.screen.width - screen.getXSize() - 12 - 2 * PanelLayout.SIDE) / PanelLayout.ROW);
        int width = Math.min(PanelLayout.preferredWidth(grid), availableColumns * PanelLayout.ROW + 2 * PanelLayout.SIDE);
        if (!LogisticsPanel.bookOpen() && screen.getGuiLeft() < width + 8 && LogisticsPanel.screen.width >= screen.getXSize() + width + 12) {
            LogisticsPanel.shiftScreenTo(width + 8);
        }
        layout = PanelLayout.compute(LogisticsPanel.screen.height, screen.getGuiLeft(), screen.getGuiTop(), grid, firstRow, LogisticsPanel.active() ? selected : -1, LogisticsPanel.bookOpen());
        int availableHeight = LogisticsPanel.screen.height - overlayBottomInset;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget)) continue;
            AbstractWidget widget = (AbstractWidget)child;
            if (!widget.visible || widget.getY() < LogisticsPanel.screen.height / 2) continue;
            PanelLayout.Rect b = layout.bounds();
            if (widget.getX() >= b.x() + b.width() || widget.getX() + widget.getWidth() <= b.x()) continue;
            availableHeight = Math.min(availableHeight, widget.getY() - 2);
        }
        if (availableHeight != LogisticsPanel.screen.height) {
            layout = PanelLayout.compute(availableHeight, screen.getGuiLeft(), screen.getGuiTop(), grid, firstRow, LogisticsPanel.active() ? selected : -1, LogisticsPanel.bookOpen());
        }
        layout = layout.clampControls(LogisticsPanel.screen.width, LogisticsPanel.screen.height);
        firstRow = layout.firstRow();
    }

    /** Move the inventory screen - and the widgets that live inside it - to {@code next}, keeping them aligned. */
    private static void shiftScreenTo(int next) {
        int old = screen.getGuiLeft();
        if (old == next) return;
        int delta = next - old;
        ((ContainerScreenAccess)screen).fmp$setLeft(next);
        for (GuiEventListener child : screen.children()) {
            AbstractWidget widget;
            if (!(child instanceof AbstractWidget) || (widget = (AbstractWidget)child).getX() < old || widget.getX() >= old + screen.getXSize()) continue;
            widget.setX(widget.getX() + delta);
        }
    }

    /** Hide the panel (leaving only its entry) or bring it back. View state only - no cell, coordinate or ledger change. */
    private static void toggleHidden() {
        templateTarget = -1; browserDrag = null;
        LogisticsPanel.hidden = !LogisticsPanel.hidden;
        LogisticsPanel.updateLayout();
        try {
            VISIBILITY.save(hidden);
        } catch (IOException | SecurityException failure) {
            FeedMePackages.LOGGER.warn("Could not save panel visibility; current session remains usable", failure);
        }
    }

    private static void foreground(ContainerScreenEvent.Render.Foreground event) {
        if (!LogisticsPanel.current((Screen)event.getContainerScreen()) || !LogisticsPanel.visible() || layout.compact()) {
            return;
        }
        mouseX = event.getMouseX();
        mouseY = event.getMouseY();
        tooltip = List.of();
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate((float)(-screen.getGuiLeft()), (float)(-screen.getGuiTop()), 0.0f);
        LogisticsPanel.render(g);
        if (templateTarget >= 0) {
            // This foreground hook runs before recipe-browser overlays. An overlay render type
            // darkens the native inventory without writing depth that would hide later JEI/EMI.
            g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(), 0, 0, screen.width, screen.height, 0xb0000000);
            g.flush();
            layout.cells().stream().filter(c -> c.slot() == templateTarget).findFirst().ifPresent(c -> {
                var r = c.bounds(); g.enableScissor(r.x(), r.y(), r.x() + r.width(), r.y() + r.height());
                frame(g, layout.bounds()); renderCell(g, c); g.disableScissor();
            });
        }
        g.pose().popPose();
    }

    private static void postRender(ScreenEvent.Render.Post event) {
        if (!LogisticsPanel.current(event.getScreen()) || !LogisticsPanel.visible()) {
            return;
        }
        mouseX = event.getMouseX();
        mouseY = event.getMouseY();
        if (browserDrag != null && browserDrag.screen() == screen
                && Math.pow(mouseX - browserDrag.x(), 2) + Math.pow(mouseY - browserDrag.y(), 2) >= 64) {
            var g = event.getGuiGraphics(); g.pose().pushPose(); g.pose().translate(0, 0, 400);
            browserDrag.template().render(g, mouseX - 8, mouseY - 8); g.pose().popPose();
        }

        if (snapshot.fluidPreview().amount() > 0 && MC.player.containerMenu.getCarried().isEmpty()) {
            var preview = snapshot.fluidPreview(); var g = event.getGuiGraphics();
            try {
                var fluid = dev.scathiard.feedmepackages.fluid.FluidVariantKey.decode(preview.template(), MC.player.registryAccess()).stack(MC.player.registryAccess(),1);
                g.pose().pushPose(); g.pose().translate(0,0,400);
                TemplateBrowsers.renderFluid(g,fluid,mouseX-8,mouseY-8); g.pose().popPose();
                tooltip=List.of(fluid.getHoverName(),Component.literal(preview.amount()+" mB"),tr("fluid_preview"));
            } catch (IllegalArgumentException invalid) { notice("invalid_display"); }
        }
        if (layout.compact()) {
            tooltip = List.of();
            LogisticsPanel.button(event.getGuiGraphics(), layout.bounds().x(), layout.bounds().y(), "+", "expand");
        }
        if (!tooltip.isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
            event.getGuiGraphics().renderComponentTooltip(LogisticsPanel.MC.font, tooltip, mouseX, mouseY);
        }
    }

    private static void render(GuiGraphics g) {
        PanelLayout.Rect b = layout.bounds();
        int x = b.x();
        int y = b.y();
        if (layout.hidden()) {
            // Fully hidden: nothing but the entry square is drawn, and the entry is the only thing the layout
            // covers - so the area the panel used to occupy is free for whatever is underneath (JEI bookmarks).
            LogisticsPanel.renderEntry(g);
            return;
        }
        LogisticsPanel.frame(g, b);
        PanelLayout.Rect copy = layout.address();
        String address = snapshot.address();
        int textWidth = copy.width() - 2;
        String displayed = LogisticsPanel.MC.font.width(address) <= textWidth ? address : LogisticsPanel.MC.font.plainSubstrByWidth(address, Math.max(0, textWidth - LogisticsPanel.MC.font.width("\u2026"))) + "\u2026";
        LogisticsPanel.text(g, displayed, copy.x() + 1, copy.y() + 3, copy.contains(mouseX, mouseY) ? -9419222 : 0xFF000000);
        if (copy.contains(mouseX, mouseY)) {
            tooltip = List.of(LogisticsPanel.tr("address", new Object[0]), Component.literal((String)address));
        }
        if (!LogisticsPanel.active() || !snapshot.bound()) {
            LogisticsPanel.text(g, "!", x + 10, y + 20, -9352640);
            if (new PanelLayout.Rect(x + 10, y + 20, 6, 9).contains(mouseX, mouseY)) {
                var statusKey = LogisticsPanel.active() ? "unbound" : "status." + snapshot.status().name().toLowerCase(Locale.ROOT);
                // A locked cache has to explain itself here too, not only in the server log: this is the
                // state that made every cache-supplied recipe look like "missing materials".
                tooltip = snapshot.status() == dev.scathiard.feedmepackages.service.AccessGate.Status.STORAGE_LOCKED
                        ? List.of(LogisticsPanel.tr(statusKey, new Object[0]),
                                LogisticsPanel.tr("status.storage_locked.detail", new Object[] { dev.scathiard.feedmepackages.storage.CacheLedger.SCHEMA }))
                        : List.of(LogisticsPanel.tr(statusKey, new Object[0]));
            }
        }
        if (LogisticsPanel.active()) {
            for (PanelLayout.CellBox cell : layout.cells()) {
                LogisticsPanel.renderCell(g, cell);
            }
            if (layout.returnBar() != null) {
                LogisticsPanel.renderReturnBar(g);
            }
            if (layout.slider() != null) {
                LogisticsPanel.renderSlider(g);
            }
        }
        if (waiting != 0) {
            LogisticsPanel.overlay(g, x + 10, y + 31, 4, 2, -1654408);
        }
        if (feedback != null && tick < feedbackUntil) {
            LogisticsPanel.text(g, "!", x + 10, y + 20, -20117);
            if (new PanelLayout.Rect(x + 10, y + 20, 6, 9).contains(mouseX, mouseY)) {
                tooltip = List.of(feedback);
            }
        }
        if (layout.totalRows() > layout.visibleRows()) {
            PanelLayout.Rect rail = layout.scrollbar();
            g.fill(rail.x(), rail.y(), rail.x() + rail.width(), rail.y() + rail.height(), -13619412);
            int thumb = Math.max(7, rail.height() * layout.visibleRows() / layout.totalRows());
            int offset = (rail.height() - thumb) * firstRow / Math.max(1, layout.totalRows() - layout.visibleRows());
            g.fill(rail.x(), rail.y() + offset, rail.x() + rail.width(), rail.y() + offset + thumb, -4152474);
        }
        if (!layout.compact()) {
            LogisticsPanel.renderTransferButton(g);
            LogisticsPanel.renderFoldButton(g);
        }
    }

    /**
     * The one-key collect button: the user's "transfer" sheet (the drop-into-the-box glyph), the LEFT of the two
     * 13x13 squares in the panel's bottom border band. Disabled (greyed and inert) exactly when the cache cannot be
     * used right now. Creative mode: the server opens the narrow door for this action alone (it never touches the
     * client-owned cursor), so the button stays live.
     */
    private static void renderTransferButton(GuiGraphics g) {
        LogisticsPanel.iconButton(g, layout.transferButton(), BUTTON_TEXTURE, "collect_button",
                LogisticsPanel.collectEnabled());
    }

    /**
     * The fold-away button, drawn while the panel is expanded: the RIGHT of the two squares, hugging the panel's
     * bottom-right corner inside the border band, on its own sheet (收纳, right-pointing chevron). Pressing it hides
     * the whole panel - nothing is drawn and nothing is intercepted, so whatever sits behind it (JEI's bookmark
     * column) becomes usable again.
     */
    private static void renderFoldButton(GuiGraphics g) {
        LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_FOLD_TEXTURE, "fold_button", true);
    }

    /**
     * The one square left on screen while the panel is hidden: the same rect the fold button used (so the panel
     * never appears to jump), but on the unfold sheet (展开, left-pointing chevron).
     */
    private static void renderEntry(GuiGraphics g) {
        LogisticsPanel.iconButton(g, layout.foldButton(), BUTTON_UNFOLD_TEXTURE, "unfold_button", true);
    }

    /**
     * A button, drawn as <b>one image</b>: the sheet already contains its own frame, face and icon, so nothing is
     * filled underneath it and nothing can cover it, and the {@code texture} argument decides what it looks like -
     * there is no mirroring and no scaling in this class at all. The only marks drawn here come <b>after</b> the
     * sheet and strictly above it ({@link #BUTTON_MARK_Z}): a hover highlight or the disabled veil.
     */
    private static void iconButton(GuiGraphics g, PanelLayout.Rect r, ResourceLocation texture, String help,
            boolean enabled) {
        if (r.width() <= 0 || r.height() <= 0) return;   // no button in this state (e.g. transfer while hidden)
        boolean hover = enabled && r.contains(mouseX, mouseY);
        LogisticsPanel.buttonSheet(g, r, texture);
        if (!enabled) LogisticsPanel.mark(g, r, BUTTON_DISABLED_VEIL);
        else if (hover) LogisticsPanel.mark(g, r, BUTTON_HOVER_MARK);
        if (hover) tooltip = List.of(LogisticsPanel.tr(help, new Object[0]));
    }

    /**
     * Draw one button sheet 1:1 on the SAME layer as {@link #overlay} (see {@link #BUTTON_Z} - drawing it at z = 0
     * was the "grey square" bug): one blit, no scale, no column mapping. Pose is always popped; blending is left as
     * found.
     */
    private static void buttonSheet(GuiGraphics g, PanelLayout.Rect r, ResourceLocation texture) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.pose().pushPose();
        g.pose().translate((float)r.x(), (float)r.y(), BUTTON_Z);
        g.blit(texture, 0, 0, 0.0f, 0.0f, PanelLayout.BUTTON_SHEET, PanelLayout.BUTTON_SHEET,
                PanelLayout.BUTTON_SHEET, PanelLayout.BUTTON_SHEET);
        g.pose().popPose();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** A state mark (hover highlight / disabled veil) drawn strictly above the sheet. */
    private static void mark(GuiGraphics g, PanelLayout.Rect r, int color) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, BUTTON_MARK_Z);
        g.fill(r.x(), r.y(), r.x() + r.width(), r.y() + r.height(), color);
        g.pose().popPose();
    }

    /** True when the one-key collect may be sent: a live cache. Creative mode is allowed (narrow server door). */
    /**
     * The vanilla button click, played exactly the way {@code AbstractButton.playDownSound} plays it:
     * {@link SoundEvents#UI_BUTTON_CLICK} through {@link SimpleSoundInstance#forUI} at pitch <b>1.0F</b>, i.e. the
     * vanilla default volume and pitch - no custom sound file, no custom values. Client-side only: it just asks the
     * client sound manager to play, so nothing is sent, no protocol changes and the server is untouched.
     *
     * <p>Called from the three button press paths - one-key collect (transfer), fold, and the entry while the panel
     * is hidden - and <b>only after the press has been accepted</b>: never on hover, never while a button is
     * disabled (the disabled early return happens before any play call), and exactly once per accepted press even
     * when that press also sends an action. Sliders, cells, the address bar and the scrollbar deliberately stay
     * silent: the user asked for button sounds only.
     */
    private static void playButtonClick() {
        LogisticsPanel.MC.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static boolean collectEnabled() {
        return LogisticsPanel.active() && snapshot != null && snapshot.bound();
    }

    private static void renderCell(GuiGraphics g, PanelLayout.CellBox box) {
        PanelLayout.Rect r = box.bounds();
        PanelPackets.CellView cell = snapshot.cells().get(box.slot());
        int x = r.x();
        int y = r.y();
        renderSlot(g, x, y);
        ItemStack icon = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
        var fluidIcon = FLUID_ICONS.getOrDefault(cell.template(), net.neoforged.neoforge.fluids.FluidStack.EMPTY);
        if (!icon.isEmpty() || !fluidIcon.isEmpty()) {
            // Display "in-cell" quantity = cache amount minus the live cursor preview (P). During the
            // pre-confirmation wait a client-side prediction overrides this one cell so the number
            // changes immediately; a matching ack clears the prediction and the authoritative value
            // (already S−P) is shown. The cache ledger (S) and restock/return decisions keep the real
            // amount; only display uses this.
            int display = Math.max(0, cell.amount() - cell.reserved());
            if (predict != null && predict.slot() == box.slot() && predict.window().equals(window)
                    && (predict.variant().equals(cell.template()))) display = Math.max(0, predict.result());
            if (display == 0) {
                g.setColor(1.0f, 1.0f, 1.0f, 0.45f);
            }
            if (cell.fluid()) TemplateBrowsers.renderFluid(g,fluidIcon,x+1,y+1); else g.renderItem(icon, x + 1, y + 1);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            String amount = cell.fluid() ? CellControlArtwork.fluidQuantity(display) : Integer.toString(display);
            float scale = Math.min(1.0f, 16.0f / (float)LogisticsPanel.MC.font.width(amount));
            g.pose().pushPose();
            PoseStack poseStack = g.pose();
            float f = (float)(x + 17) - (float)LogisticsPanel.MC.font.width(amount) * scale;
            float f2 = y + 17;
            Objects.requireNonNull(LogisticsPanel.MC.font);
            poseStack.translate(f, f2 - 9.0f * scale, 190.0f);
            g.pose().scale(scale, scale, 1.0f);
            g.drawString(LogisticsPanel.MC.font, amount, 0, 0, display == 0 ? -6909823 : -661306, true);
            g.pose().popPose();
        }
        CellFeedback.render(g, box.slot(), x, y);
        boolean hover = r.contains(mouseX, mouseY);
        if (cell.residual()) {
            LogisticsPanel.overlay(g, x + 1, y + 13, 3, 3, -8206747);
            if (hover && new PanelLayout.Rect(x, y + 11, 6, 7).contains(mouseX, mouseY)) {
                tooltip = List.of(LogisticsPanel.tr("residual", new Object[0]));
            }
        }
        if (!cell.template().isEmpty() && cell.minimum() >= 0 && cell.amount() < cell.minimum()) {
            int color = cell.pending() > 0 ? -8206747 : -7105653;
            LogisticsPanel.overlay(g, x + 3, y + 1, 1, 3, color);
            LogisticsPanel.overlay(g, x + 1, y + 3, 5, 1, color);
            LogisticsPanel.overlay(g, x + 2, y + 4, 3, 1, color);
        }
        if (!cell.template().isEmpty() && cell.maximum() >= 0 && cell.amount() > cell.maximum()) {
            int color = cell.returnState() == dev.scathiard.feedmepackages.logistics.ReturnService.RETURN_SENT ? -2058918 : -6909823;
            LogisticsPanel.overlay(g, x + 1, y + 1, 5, 1, color);
            LogisticsPanel.overlay(g, x + 2, y + 2, 3, 1, color);
            LogisticsPanel.overlay(g, x + 3, y + 3, 1, 3, color);
        }
        if (hover && !cell.template().isEmpty()) {
            LogisticsPanel.overlay(g, box.dot().x(), box.dot().y() + 1, 4, 4, -2047362);
        }
        if (hover) {
            tooltip = new ArrayList<Component>();
            if (!icon.isEmpty() || !fluidIcon.isEmpty()) {
                if (cell.fluid()) tooltip.add(fluidIcon.getHoverName()); else tooltip.addAll(Screen.getTooltipFromItem((Minecraft)MC, (ItemStack)icon));
                int display = Math.max(0, cell.amount() - cell.reserved());
                if (predict != null && predict.slot() == box.slot() && predict.window().equals(window))
                    display = Math.max(0, predict.result());
                tooltip.add(LogisticsPanel.tr(cell.fluid() ? "fluid_stock" : "stock", display, snapshot.groupCapacity() * cell.stackSize()));
                if (!cell.fluid() && cell.stackSize() > 1) {
                    tooltip.add(LogisticsPanel.tr("stock_groups", cell.amount() / cell.stackSize(), snapshot.groupCapacity()));
                }
                if (display == 0 && cell.reserved() > 0) tooltip.add(LogisticsPanel.tr("held_preview", new Object[0]));
            }
            if (cell.fluid()) tooltip.add(tr("fluid_take"));
            if (cell.fluid() || cell.template().isEmpty()) tooltip.add(tr("fluid_deposit"));
            if (cell.amount() == 0 && TemplateBrowsers.available()) tooltip.add(tr("template_hint",ClientBootstrap.SELECT_TEMPLATE.getTranslatedKeyMessage()));
            if (cell.minimum() >= 0 && cell.amount() < cell.minimum() && mouseX < x + 7 && mouseY < y + 7) {
                tooltip = List.of(LogisticsPanel.tr(cell.pending() > 0 ? "requested" : "shortage", new Object[0]));
            }
            if (box.dot().contains(mouseX, mouseY) && !cell.template().isEmpty()) {
                tooltip = List.of(LogisticsPanel.tr("configure", new Object[0]));
            }
        }
    }

    /**
     * The user-authored cell background. Its texture holds <b>only</b> the 18x18 slot that is actually drawn: the
     * sheet used to be the user's full 256x256 panel reference (with its address plate and hooks) of which just
     * the block at (101,65) was ever sampled. Cropping moved those exact 324 pixels to (0,0) and the whole sheet is
     * now 18x18, so the blit reads the whole texture - the pixels on screen are unchanged, and the jar no longer
     * carries 8 KB of unreachable art. The reference sheet itself is kept outside the mod
     * ({@code 参考/slot_source-参考稿.png}).
     */
    private static void renderSlot(GuiGraphics g, int x, int y) {
        // This user-authored slot has its own stable source; panel.png may be reorganized independently.
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(SLOT_TEXTURE, x, y, 0.0f, 0.0f, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static void panelBlit(GuiGraphics g, int sx, int sy, int sw, int sh, int u, int v, int w, int h) {
        // All pieces are pixel-sized. A partial repeat is cropped, never scaled.
        if (sw > 0 && sh > 0) {
            // The panel texture carries alpha (e.g. the endpoint corners). Enable standard alpha
            // blending for this sprite so a low-alpha pixel blends instead of being written flat,
            // then restore the prior state so the blend never leaks to later draws.
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            g.blit(PANEL, sx, sy, (float) u, (float) v, sw, sh, 256, 256);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }

    private static dev.scathiard.feedmepackages.domain.CellSettings settings(PanelPackets.CellView cell) {
        return new dev.scathiard.feedmepackages.domain.CellSettings(cell.minimum(), cell.maximum(), cell.batch(), cell.pendingLimit());
    }
    private static boolean currentDraft(CellControlDraft draft) {
        var c = draft.context();
        if (snapshot == null || !Objects.equals(snapshot.session(), c.session()) || c.slot() < 0 || c.slot() >= snapshot.cells().size()) return false;
        var cell = snapshot.cells().get(c.slot());
        return c.filter().equals(cell.template()) && c.filterRevision() == cell.filterRevision();
    }
    private static CellControlDraft controls() {
        if (snapshot == null || selected < 0 || selected >= snapshot.cells().size()) return null;
        var cell = snapshot.cells().get(selected);
        var ctx = new CellControlDraft.Context(snapshot.session(), selected, cell.template(), cell.filterRevision());
        var draft = controlDrafts.get(selected);
        if (draft == null || !ctx.equals(draft.context())) {
            draft = new CellControlDraft(ctx, settings(cell)); controlDrafts.put(selected, draft);
        }
        return draft;
    }
    private static int cellCapacity() { return Math.multiplyExact(snapshot.groupCapacity(), snapshot.cells().get(selected).stackSize()); }
    private static void renderSlider(GuiGraphics g) {
        var draft = controls(); if (draft == null) return;
        var cell = snapshot.cells().get(selected); var r = layout.slider();
        var parcel = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("create", "cardboard_package_10x8")));
        CellControlArtwork.render(g, r, draft.value(), cellCapacity(), cell.pending(), parcel, cell.fluid());
        if (!r.contains(mouseX, mouseY)) return;
        tooltip = List.of();
        for (int field=0;field<5;field++) if (CellControlGeometry.field(r,field).contains(mouseX,mouseY)) {
            if (cell.fluid()) {
                tooltip = field == 4 ? List.of(tr("controls.fluid_pending", cell.pending()), tr("controls.reset"))
                        : field < 2 ? List.of(tr("controls.fluid_reserved"))
                        : List.of(tr(field == 2 ? "controls.lower" : "controls.upper"), tr("controls.fluid_step"));
                break;
            }
            tooltip = field == 4 ? List.of(tr("controls.pending",cell.pending()),tr("controls.reset"),tr("controls.late"))
                    : List.of(tr("controls."+new String[]{"batch","limit","lower","upper"}[field]),tr("controls.wheel"));
            if (field == 0 && draft.value().lower()>0 && draft.value().upper() >= 0
                    && (long)draft.value().lower()-1+draft.value().batch() > draft.value().upper())
                tooltip = List.of(tr("controls.batch"),tr("controls.wheel"),tr("controls.overshoot"));
            if ((field==0 || field==1) && draft.value().pendingLimit()>=0 && draft.value().pendingLimit()<draft.value().batch())
                tooltip=List.of(tr(field==0?"controls.batch":"controls.limit"),tr("controls.wheel"),tr("controls.conflict"));
            break;
        }
    }

    private static void renderReturnBar(GuiGraphics g) {
        boolean placeholder;
        PanelLayout.Rect r = layout.returnBar();
        int x = r.x();
        int y = r.y();
        int w = r.width();
        int h = r.height();
        // Background and hook were drawn once by frame(); only live text goes here.
        String value = returnEditing ? returnBuffer : (snapshot.returnAddress() == null ? "" : snapshot.returnAddress());
        int textWidth = Math.max(0, w - 12);
        boolean bl = placeholder = value.isEmpty() && !returnEditing;
        String shown = placeholder ? LogisticsPanel.tr("return_label", new Object[0]).getString() : (LogisticsPanel.MC.font.width(value) <= textWidth ? value : LogisticsPanel.MC.font.plainSubstrByWidth(value, Math.max(0, textWidth - LogisticsPanel.MC.font.width("\u2026"))) + "\u2026");
        int n = y + h / 2;
        Objects.requireNonNull(LogisticsPanel.MC.font);
        LogisticsPanel.text(g, shown, x + 9, n - 9 / 2, placeholder ? -7700888 : (returnEditing ? -11912408 : 0xFF000000));
        if (returnEditing) {
            int caretX = Math.min(x + w - 3, x + 9 + LogisticsPanel.MC.font.width(shown));
            g.fill(caretX, y + 4, caretX + 1, y + h - 4, -11912408);
        }
        if (r.contains(mouseX, mouseY)) {
            tooltip = List.of(LogisticsPanel.tr(returnEditing ? "return_editing" : "return_hint", new Object[0]));
        }
    }

    private static void frame(GuiGraphics g, PanelLayout.Rect b) {
        int x = b.x(), y = b.y(), w = b.width(), h = b.height();
        int bottom = y + h - 49;
        // Top caps include the ribbon and the first 18 pixels of wooden backing.
        panelBlit(g, x, y, 22, 36, 18, 47, 22, 36);
        panelBlit(g, x + w - 22, y, 22, 36, 53, 47, 22, 36);
        for (int tx = x + 22; tx < x + w - 22; tx++)
            panelBlit(g, tx, y, 1, 36, 44, 47, 1, 36);
        // The repeat strip is FOUR pixels tall and ONE pixel wide in its centre.
        // u=45 and v=89 are transparent atlas gutters, not repeatable wood.
        for (int ty = y + 36; ty < bottom; ty += 4) {
            int sh = Math.min(4, bottom - ty);
            panelBlit(g, x + 8, ty, 14, sh, 26, 85, 14, sh);
            panelBlit(g, x + w - 22, ty, 14, sh, 53, 85, 14, sh);
            for (int tx = x + 22; tx < x + w - 22; tx++)
                panelBlit(g, tx, ty, 1, sh, 44, 85, 1, sh);
        }
        // This single footer already contains the hook AND the blank address tag.
        panelBlit(g, x, bottom, 22, 49, 18, 91, 22, 49);
        panelBlit(g, x + w - 22, bottom, 22, 49, 53, 91, 22, 49);
        for (int tx = x + 22; tx < x + w - 22; tx++)
            panelBlit(g, tx, bottom, 1, 49, 44, 91, 1, 49);
    }

    private static void button(GuiGraphics g, int x, int y, String label, String help) {
        boolean hover = new PanelLayout.Rect(x, y, 18, 18).contains(mouseX, mouseY);
        (hover ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON).render(g, x, y);
        LogisticsPanel.text(g, label, x + (18 - LogisticsPanel.MC.font.width(label)) / 2, y + 5, -990269);
        if (hover) {
            tooltip = help.equals("address") ? List.of(LogisticsPanel.tr("address", new Object[0]), Component.literal((String)snapshot.address())) : List.of(LogisticsPanel.tr(help, new Object[0]));
        }
    }

    private static void text(GuiGraphics g, String value, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 190.0f);
        g.drawString(LogisticsPanel.MC.font, value, x, y, color, false);
        g.pose().popPose();
    }

    private static void overlay(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, BUTTON_Z);
        g.fill(x, y, x + w, y + h, color);
        g.pose().popPose();
    }

    private enum ReturnClose {
        NONE, CLOSED, SUBMITTED
    }

    private static ReturnClose closeReturnEditorForClick(double x, double y, PanelLayout inputLayout) {
        if (!returnEditing || !LogisticsPanel.active() || inputLayout.returnAddressContains(x, y)) {
            return ReturnClose.NONE;
        }
        String current = snapshot.returnAddress() == null ? "" : snapshot.returnAddress();
        if (returnBuffer.equals(current)) {
            returnEditing = false;
            return ReturnClose.CLOSED;
        }
        if (LogisticsPanel.sendReturnAddressDraft()) {
            returnEditing = false;
            return ReturnClose.SUBMITTED;
        }
        LogisticsPanel.notice("result.stale");
        return ReturnClose.NONE;
    }

    private static boolean pendingReturnMatches(PanelPackets.Snapshot incoming) {
        return pendingReturnSequence == incoming.acknowledged()
                && Objects.equals(pendingReturnWindow, incoming.window())
                && Objects.equals(pendingReturnSession, incoming.session());
    }

    private static boolean collapseSliderForClick(double x, double y, PanelLayout inputLayout) {
        if (selected < 0 || inputLayout.slider() == null || inputLayout.slider().contains(x, y)) {
            return false;
        }
        for (PanelLayout.CellBox box : inputLayout.cells()) {
            if (box.dot().contains(x, y)) {
                return false;
            }
        }
        selected = -1;
        LogisticsPanel.updateLayout();
        return true;
    }

    private static QueuedPanelClick queuedPanelClick(PanelLayout inputLayout, double x, double y, int button) {
        if (button != 0 && button != 1 || window == null || snapshot == null || snapshot.session() == null || inputLayout.compact()) {
            return null;
        }
        if (inputLayout.address().contains(x, y)) {
            return new QueuedPanelClick(window, snapshot.session(), QueuedClickKind.COPY_ADDRESS, -1, button, -1, "");
        }
        PanelLayout.Rect rail = inputLayout.scrollbar();
        if (inputLayout.totalRows() > inputLayout.visibleRows() && rail.contains(x, y)) {
            int row = (int)Math.round((y - rail.y()) * (inputLayout.totalRows() - inputLayout.visibleRows())
                    / Math.max(1, rail.height() - 1));
            return new QueuedPanelClick(window, snapshot.session(), QueuedClickKind.SCROLL, -1, button, row, "");
        }
        if (!LogisticsPanel.active() || inputLayout.slider() != null && inputLayout.slider().contains(x, y)) {
            return null;
        }
        for (PanelLayout.CellBox box : inputLayout.cells()) {
            if (!box.bounds().contains(x, y)) continue;
            PanelPackets.CellView cell = snapshot.cells().get(box.slot());
            if (box.dot().contains(x, y) && !cell.template().isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                return new QueuedPanelClick(window, snapshot.session(), QueuedClickKind.TOGGLE_SLOT, box.slot(), button, -1, cell.template());
            }
            if (button == 1 && Screen.hasControlDown()) {
                return new QueuedPanelClick(window, snapshot.session(), QueuedClickKind.CLEAR_FILTER, box.slot(), button, -1, cell.template());
            }
            if (!LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                return new QueuedPanelClick(window, snapshot.session(), Screen.hasAltDown() || cell.fluid() ? QueuedClickKind.DEPOSIT_FLUID : QueuedClickKind.DEPOSIT, box.slot(), button, -1, cell.template());
            }
            return new QueuedPanelClick(window, snapshot.session(), Screen.hasShiftDown() ? QueuedClickKind.TAKE_INVENTORY : QueuedClickKind.TAKE_CURSOR, box.slot(), button, -1, cell.template());
        }
        return null;
    }

    private static void runQueuedPanelClick() {
        QueuedPanelClick click = queuedReturnClick;
        queuedReturnClick = null;
        if (click == null || window == null || !click.window().equals(window)
                || snapshot == null || !Objects.equals(click.session(), snapshot.session())) {
            return;
        }
        if (click.kind() == QueuedClickKind.COPY_ADDRESS) {
            LogisticsPanel.MC.keyboardHandler.setClipboard(snapshot.address());
            LogisticsPanel.notice("copied");
            return;
        }
        if (click.kind() == QueuedClickKind.SCROLL) {
            CellFeedback.clear();
            firstRow = click.row();
            selected = -1;
            LogisticsPanel.updateLayout();
            return;
        }
        if (!LogisticsPanel.active() || click.slot() < 0 || click.slot() >= snapshot.cells().size()) {
            return;
        }
        PanelPackets.CellView cell = snapshot.cells().get(click.slot());
        if (!Objects.equals(click.template(), cell.template())) {
            return;
        }
        if (click.kind() == QueuedClickKind.TOGGLE_SLOT) {
            if (!cell.template().isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                selected = selected == click.slot() ? -1 : click.slot();
                LogisticsPanel.updateLayout();
            }
            return;
        }
        switch (click.kind()) {
            case CLEAR_FILTER -> LogisticsPanel.send(CacheActions.Action.CLEAR_FILTER, click.slot(), 0, -1, "");
            case DEPOSIT_FLUID -> LogisticsPanel.send(CacheActions.Action.DEPOSIT_FLUID, click.slot(), 0, 0, "");
            case DEPOSIT -> LogisticsPanel.send(CacheActions.Action.DEPOSIT, click.slot(), click.button() == 1 ? 1 : 0, -1, "");
            case TAKE_INVENTORY -> {
                if (cell.fluid()) { send(CacheActions.Action.TAKE_FLUID,click.slot(),8000,0,""); break; }
                ItemStack item = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
                LogisticsPanel.send(CacheActions.Action.TAKE_INVENTORY, click.slot(), click.button() == 1 ? 1 : item.getMaxStackSize(), -1, "");
            }
            case TAKE_CURSOR -> {
                if (cell.fluid()) { send(CacheActions.Action.TAKE_FLUID,click.slot(),1000,0,""); break; }
                ItemStack item = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
                LogisticsPanel.send(CacheActions.Action.TAKE_CURSOR, click.slot(), click.button() == 1 ? 1 : item.getMaxStackSize(), -1, "");
            }
            default -> {}
        }
    }

    private static boolean press(double x, double y, int button) {
        if (!LogisticsPanel.visible()) {
            return false;
        }
        // While the panel is hidden, the ONLY thing it may consume is its entry square. Everything else - the
        // whole area the panel used to occupy - must fall through to whatever is underneath (JEI's bookmarks),
        // so this check comes before the in-flight guard and before any other hit test.
        if (layout.hidden()) {
            if (!layout.bounds().contains(x, y)) return false;
            // The entry is the third button: it clicks exactly like the other two (vanilla sound, client-side only).
            LogisticsPanel.playButtonClick();
            LogisticsPanel.toggleHidden();
            return true;
        }
        if (templateTarget >= 0) {
            if (button == 1 || button == 2) { templateTarget = -1; capturedButton = button; return true; }
            var template = TemplateBrowsers.selection();
            if (button == 0 && !template.isEmpty()) {
                setTemplate(templateTarget, template); capturedButton = button; return true;
            }
            if (layout.bounds().contains(x, y)) { capturedButton = button; return true; }
            return false;
        }
        if (ClientBootstrap.SELECT_TEMPLATE.matchesMouse(button) && beginTemplate(x, y)) { capturedButton = button; return true; }
        if (active() && layout.slider() != null && layout.slider().contains(x,y)) {
            closeReturnEditorForClick(x,y,layout);
            capturedButton = button;
            if (button == 0) {
                if (CellControlGeometry.field(layout.slider(),4).contains(x,y))
                    send(CacheActions.Action.RESET_REQUEST,selected,0,0,"");
                else tryStartSliderDrag(layout,x,y);
            }
            return true;
        }
        if (waiting != 0) {
            capturedButton = button;
            return true;
        }
        if (active() && snapshot.fluidPreview().amount() > 0) {
            capturedButton=button;
            if (button != 0 || !MC.player.containerMenu.getCarried().isEmpty()) {
                send(CacheActions.Action.RELEASE_FLUID,-1,snapshot.fluidPreview().sequence(),0,""); return true;
            }
            for (var slot : screen.getMenu().slots) {
                int index=slot.getSlotIndex();
                if (slot.container != MC.player.getInventory() || index<0 || index>=36 || !slot.isActive() || !slot.mayPickup(MC.player)) continue;
                if (!new PanelLayout.Rect(screen.getGuiLeft()+slot.x,screen.getGuiTop()+slot.y,16,16).contains(x,y) || slot.getItem().isEmpty()) continue;
                try { send(CacheActions.Action.PLACE_FLUID,snapshot.fluidPreview().slot(),index,snapshot.fluidPreview().sequence(),ItemVariantKey.of(slot.getItem(),MC.player.registryAccess()).encoded()); }
                catch (IllegalArgumentException invalid) { notice("result.invalid_item"); }
                return true;
            }
            if (layout.cells().stream().anyMatch(c->c.slot()==snapshot.fluidPreview().slot() && c.bounds().contains(x,y)))
                send(CacheActions.Action.RELEASE_FLUID,-1,snapshot.fluidPreview().sequence(),0,"");
            return true;
        }
        PanelLayout inputLayout = layout;
        boolean insidePanel = inputLayout.bounds().contains(x, y);
        QueuedPanelClick queuedAfterReturn = insidePanel ? LogisticsPanel.queuedPanelClick(inputLayout, x, y, button) : null;
        ReturnClose returnClose = LogisticsPanel.closeReturnEditorForClick(x, y, inputLayout);
        boolean collapsedSlider = LogisticsPanel.collapseSliderForClick(x, y, inputLayout);
        if (!insidePanel) {
            if (returnClose == ReturnClose.SUBMITTED || collapsedSlider) {
                passthroughReleaseButton = button;
            }
            return false;
        }
        if (returnClose == ReturnClose.SUBMITTED) {
            // The address submit is in flight. A click that lands on the slider must still start the
            // drag immediately (the threshold command is queued until the submit settles), otherwise
            // "edit the address, then drag" silently needed a second press.
            if (button == 0 && LogisticsPanel.tryStartSliderDrag(inputLayout, x, y)) {
                capturedButton = button;
                return true;
            }
            queuedReturnClick = queuedAfterReturn;
            capturedButton = button;
            return true;
        }
        capturedButton = button;
        if (button != 0 && button != 1) {
            return true;
        }
        if (LogisticsPanel.active() && inputLayout.returnAddressContains(x, y)) {
            returnEditing = true;
            returnBuffer = snapshot.returnAddress() == null ? "" : snapshot.returnAddress();
            return true;
        }
        if (inputLayout.compact()) {
            if (LogisticsPanel.bookOpen()) {
                ((RecipeUpdateListener)screen).getRecipeBookComponent().toggleVisibility();
                screen.init(MC, LogisticsPanel.screen.width, LogisticsPanel.screen.height);
            }
            LogisticsPanel.updateLayout();
            return true;
        }
        // The two small buttons come first: the fold/entry toggle works in both states, the collect only when
        // the cache is usable. A disabled collect still swallows the click instead of letting it fall through to
        // a drop. Both are answered before the 2-pixel scrollbar rail so its thin band does not steal them;
        // elsewhere the rail behaves exactly as before.
        if (inputLayout.foldButton().contains(x, y)) {
            LogisticsPanel.playButtonClick();
            LogisticsPanel.toggleHidden();
            return true;
        }
        if (inputLayout.transferButton().contains(x, y)) {
            if (!LogisticsPanel.collectEnabled()) {
                // Disabled (greyed out): the press is swallowed but stays SILENT - no click sound at all. This
                // early return sits deliberately BEFORE the play call below.
                return true;
            }
            LogisticsPanel.playButtonClick();
            LogisticsPanel.send(CacheActions.Action.COLLECT_MATCHING, -1, 0, -1, "");
            return true;
        }
        int px = layout.bounds().x();
        int py = layout.bounds().y();
        if (inputLayout.address().contains(x, y)) {
            LogisticsPanel.MC.keyboardHandler.setClipboard(snapshot.address());
            LogisticsPanel.notice("copied");
            return true;
        }
        // The one-key collect button sits in the right-hand cap. It is answered before the scrollbar rail so
        // its band is not stolen by the 2-pixel rail; elsewhere the rail behaves as before.
        PanelLayout.Rect rail = inputLayout.scrollbar();
        if (inputLayout.totalRows() > inputLayout.visibleRows() && rail.contains(x, y)) {
            CellFeedback.clear();
            firstRow = (int) Math.round((y - rail.y()) * (inputLayout.totalRows() - inputLayout.visibleRows())
                    / Math.max(1, rail.height() - 1));
            selected = -1;
            LogisticsPanel.updateLayout();
            return true;
        }
        if (!LogisticsPanel.active()) {
            return true;
        }
        PanelLayout.Rect slider = inputLayout.slider();
        if (slider != null && slider.contains(x, y)) {
            LogisticsPanel.tryStartSliderDrag(inputLayout, x, y);
            return true;
        }
        for (PanelLayout.CellBox box : inputLayout.cells()) {
            if (!box.bounds().contains(x, y)) continue;
            PanelPackets.CellView cell = snapshot.cells().get(box.slot());
            if (box.dot().contains(x, y) && !cell.template().isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                selected = selected == box.slot() ? -1 : box.slot();
                LogisticsPanel.updateLayout();
                return true;
            }
            if (button == 1 && Screen.hasControlDown()) {
                LogisticsPanel.send(CacheActions.Action.CLEAR_FILTER, box.slot(), 0, -1, "");
            } else if (!LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                LogisticsPanel.depositAt(box.slot(), button);
            } else if (cell.fluid()) {
                send(CacheActions.Action.TAKE_FLUID,box.slot(),Screen.hasShiftDown()?8000:1000,0,"");
            } else {
                ItemStack item = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
                LogisticsPanel.send(Screen.hasShiftDown() ? CacheActions.Action.TAKE_INVENTORY : CacheActions.Action.TAKE_CURSOR, box.slot(), button == 1 ? 1 : item.getMaxStackSize(), -1, "");
            }
            return true;
        }
        // A stack clicked down anywhere in the panel body (not on a cell, not on a widget) is a drop:
        // the item names its cell, so no aiming is needed. See depositAt.
        if (LogisticsPanel.canDeposit(button, x, y)) LogisticsPanel.depositAt(-1, button);
        return true;
    }

    /** True when this press/release may move the cursor's stack into the cache. */
    private static boolean canDeposit(int button, double x, double y) {
        return LogisticsPanel.active() && (button == 0 || button == 1) && snapshot != null
                && !LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()
                && (layout == null || layout.slider() == null || !layout.slider().contains(x, y));
    }

    /**
     * The one deposit both click paths use. The cell the drop landed on ({@code aimed}) is the first choice -
     * a cache holds at most one cell per exact item, so the aimed cell only keeps the drop when it already
     * filters the item or is free while no other cell claims it; otherwise the item names its own cell (the
     * one that already filters it, else the first empty one, exactly the aim-free rule). {@code aimed < 0} is a
     * drop that landed on no cell at all: the panel's blank space (the border strips around the grid), which is
     * the aim-free drop verbatim. This sends the ordinary {@code DEPOSIT} to the one resolved cell - no new
     * action, no new packet - and the server keeps validating it ({@code DUPLICATE_FILTER} /
     * {@code FILTER_OCCUPIED} / {@code NO_SPACE} stay the net). A drop with nowhere to go says so instead of
     * doing nothing.
     */
    private static void depositAt(int aimed, int button) {
        if (aimed >= 0 && (Screen.hasAltDown() || snapshot.cells().get(aimed).fluid())) {
            send(CacheActions.Action.DEPOSIT_FLUID,aimed,0,0,""); return;
        }
        int first = button == 1 ? 1 : 0;
        String carried;
        try {
            carried = ItemVariantKey.of(LogisticsPanel.MC.player.containerMenu.getCarried(),
                    LogisticsPanel.MC.player.registryAccess()).encoded();
        } catch (IllegalArgumentException invalid) {
            // The blank-area drop always needed the encoded item. When the player aimed at a cell there is
            // nothing to guess: keep that plain deposit (the server answers its own INVALID_ITEM), as before.
            if (aimed >= 0) LogisticsPanel.send(CacheActions.Action.DEPOSIT, aimed, first, -1, "");
            else LogisticsPanel.notice("result.invalid_item");
            return;
        }
        int groupCapacity = Math.max(1, snapshot.capacity() / 64);
        List<CacheDropTarget.Cell> cells = new ArrayList<>(snapshot.cells().size());
        for (PanelPackets.CellView cell : snapshot.cells())
            cells.add(new CacheDropTarget.Cell(cell.template(),
                    cell.amount() >= groupCapacity * Math.max(1, cell.stackSize())));
        CacheDropTarget.Target target = CacheDropTarget.resolveDrop(cells, aimed, carried);
        if (!target.hasTarget()) {
            LogisticsPanel.notice("result.no_space");
            return;
        }
        LogisticsPanel.send(CacheActions.Action.DEPOSIT, target.slot(), first, -1, "");
    }

    private static boolean release(double x, double y, int button) {
        if (!LogisticsPanel.visible()) {
            capturedButton = -1;
            return false;
        }
        if (passthroughReleaseButton == button) {
            passthroughReleaseButton = -1;
            return false;
        }
        if (draggingSlider || draggingMaximum) {
            boolean maximum = draggingMaximum;
            if (dragMoved || Math.abs(x - dragOriginX) >= 2.0) updateSliderDrag(x,maximum);
            draggingSlider = false; draggingMaximum = false;
            var draft = controls(); if (draft != null && dragMoved) { draft.immediate(); flushPendingThresholds(); }
            dragMoved = false; capturedButton = -1; return true;
        }
        if (layout.slider() != null && layout.slider().contains(x,y)) { capturedButton = -1; return true; }
        if (waiting != 0) { capturedButton = -1; return true; }
        if (capturedButton == button) {
            capturedButton = -1;
            return true;
        }
        if (layout.bounds().contains(x, y)) {
            boolean depositable = LogisticsPanel.canDeposit(button, x, y);
            boolean onCell = false;
            if (depositable) for (PanelLayout.CellBox box : layout.cells()) {
                if (!box.bounds().contains(x, y)) continue;
                LogisticsPanel.depositAt(box.slot(), button);
                onCell = true;
            }
            // Released over empty panel space: same drop-anywhere rule as a press there.
            if (depositable && !onCell) LogisticsPanel.depositAt(-1, button);
            return true;
        }
        return false;
    }

    private static boolean scroll(double x, double y, double amount) {
        if (templateTarget >= 0 && layout != null && layout.bounds().contains(x, y)) return true;
        if (!visible()) return false;
        if (!layout.hidden() && layout.slider() != null && layout.slider().contains(x,y)) {
            var draft=controls(); if (draft==null || amount==0 || draggingSlider || draggingMaximum) return true;
            var value=draft.value(); boolean fluid=snapshot.cells().get(selected).fluid(); int step=fluid?8000:Screen.hasShiftDown()?snapshot.cells().get(selected).stackSize():1;
            int direction=amount>0?1:-1, cap=cellCapacity(), nine=9*snapshot.cells().get(selected).stackSize();
            int lower=value.lower(),upper=value.upper(),batch=value.batch(),limit=value.pendingLimit();
            if(!fluid && CellControlGeometry.field(layout.slider(),0).contains(x,y)) batch=CellControlDraft.wheel(batch,direction,step,1,nine,false);
            else if(!fluid && CellControlGeometry.field(layout.slider(),1).contains(x,y)) limit=CellControlDraft.wheel(limit,direction,step,1,nine,true);
            else if(CellControlGeometry.field(layout.slider(),2).contains(x,y)) {
                lower=CellControlDraft.wheel(Math.max(0,lower),direction,step,0,upper<0?cap:upper,false); if(lower==0) lower=-1;
            } else if(CellControlGeometry.field(layout.slider(),3).contains(x,y)) upper=CellControlDraft.wheel(upper,direction,step,Math.max(0,lower),cap,true);
            draft.changed(new dev.scathiard.feedmepackages.domain.CellSettings(lower,upper,batch,limit),tick);
            return true; // Includes wheel-at-boundary and the blank area: never scroll the backing grid.
        }
        if(!layout.bounds().contains(x,y)) return false;
        if(waiting!=0 || layout.compact() || layout.hidden()) return true;
        CellFeedback.clear();
        firstRow += amount>0?-1:1; selected=-1; updateLayout(); return true;
    }

    private static boolean key(int key, int scan, int modifiers) {
        if (templateTarget >= 0 && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { templateTarget = -1; return true; }
        if (ClientBootstrap.SELECT_TEMPLATE.matches(key, scan)) {
            if (templateTarget >= 0) { templateTarget = -1; return true; }
            return beginTemplate(TemplateBrowsers.cursorX(), TemplateBrowsers.cursorY());
        }
        return LogisticsPanel.visible() && waiting != 0 && key != 256;
    }

    private static boolean editReturnKey(int keyCode) {
        if (keyCode == 256) {
            returnEditing = false;
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            if (LogisticsPanel.sendReturnAddressDraft()) {
                returnEditing = false;
            } else {
                LogisticsPanel.notice("result.stale");
            }
            return true;
        }
        if (keyCode == 259) {
            if (!returnBuffer.isEmpty()) {
                returnBuffer = returnBuffer.substring(0, returnBuffer.length() - 1);
            }
            return true;
        }
        return false;
    }

    private static boolean sendReturnAddressDraft() {
        String submitted = returnBuffer;
        if (!LogisticsPanel.send(CacheActions.Action.SET_RETURN_ADDRESS, -1, -1, -1, submitted)) {
            return false;
        }
        pendingReturnSequence = waiting;
        pendingReturnWindow = window;
        pendingReturnSession = snapshot == null ? null : snapshot.session();
        pendingReturnBuffer = submitted;
        return true;
    }

    private static boolean tryStartSliderDrag(PanelLayout inputLayout, double x, double y) {
        var r=inputLayout.slider(); var draft=controls(); if(r==null || draft==null) return false;
        int low=CellControlGeometry.thumb(r,Math.max(0,draft.value().lower()),cellCapacity());
        int high=CellControlGeometry.thumb(r,draft.value().upper(),cellCapacity());
        if (y>=r.y()+40 && y<r.y()+47 && Math.abs(x-low)<=4) draggingSlider=true;
        else if(y>=r.y()+32 && y<r.y()+39 && Math.abs(x-high)<=4) draggingMaximum=true;
        else return false;
        dragOriginX=x; dragMoved=false;
        dragContext=draft.context();
        dragStartValue=draggingMaximum?(draft.value().upper()<0?cellCapacity():draft.value().upper()):Math.max(0,draft.value().lower());
        return true;
    }
    private static void updateSliderDrag(double x, boolean maximum) {
        var draft=controls(); if(draft==null || layout.slider()==null || !dragMoved && Math.abs(x-dragOriginX)<2) return;
        dragMoved=true; var value=draft.value(); int cap=cellCapacity();
        int next=CellControlGeometry.draggedValue(dragStartValue,x-dragOriginX,cap);
        if (snapshot.cells().get(selected).fluid()) next=Math.clamp(Math.round(next/8000f)*8000,0,cap);
        int lower=value.lower(),upper=value.upper();
        if(maximum) upper=next>=cap?-1:Math.max(next,Math.max(0,lower));
        else { lower=Math.min(next,upper<0?cap:upper); if(lower==0) lower=-1; }
        draft.changed(new dev.scathiard.feedmepackages.domain.CellSettings(lower,upper,value.batch(),value.pendingLimit()),tick);
    }
    private static void flushPendingThresholds() {
        if(snapshot==null || !active() || waiting!=0 || cursorPending || pendingCursorChange!=null || draggingSlider || draggingMaximum) return;
        submitControls(false);
    }
    private static void submitControls(boolean closing) {
        controlDrafts.entrySet().removeIf(e -> !currentDraft(e.getValue()));
        var drafts=controlDrafts.values().stream().filter(d->closing?d.dirty():d.ready(tick)).toList();
        if(drafts.isEmpty())return;
        var changes=drafts.stream().map(d->new CacheActions.SettingsChange(d.context().slot(),d.context().filterRevision(),d.value(),snapshot.cells().get(d.context().slot()).fluid())).toList();
        waiting=++sequence;waitingSince=tick;
        PacketDistributor.sendToServer(new PanelPackets.SettingsBatch(window,snapshot.session(),waiting,changes));
        for(var draft:drafts)draft.sentAll(waiting);
    }

    public static boolean recipeReady() {
        return LogisticsPanel.active() && waiting == 0 && window != null;
    }

    public static boolean templateTarget(int slot) {
        return recipeReady() && slot >= 0 && slot < snapshot.cells().size() && snapshot.cells().get(slot).amount() == 0
                && snapshot.fluidPreview().amount() == 0 && MC.player.containerMenu.getCarried().isEmpty() && (templateTarget < 0 || templateTarget == slot);
    }
    private static boolean beginTemplate(double x, double y) {
        if (!active() || waiting != 0 || layout == null || hidden || layout.compact() || returnEditing || snapshot.fluidPreview().amount()>0 || !MC.player.containerMenu.getCarried().isEmpty()) return false;
        for (var cell : layout.cells()) if (cell.bounds().contains(x, y)) {
            if (snapshot.cells().get(cell.slot()).amount() != 0) notice("result.filter_occupied");
            else if (!TemplateBrowsers.available()) notice("template_no_browser");
            else { templateTarget = cell.slot(); selected = -1; updateLayout(); notice("template_choose"); }
            return true;
        }
        return false;
    }
    public static boolean earlyTemplateInput(int button, int action) {
        if (action == 0 && earlyTemplateButton == button) { earlyTemplateButton = -1; return true; }
        if (action != 1 || templateTarget < 0 || !current(MC.screen) || !visible()) return false;
        if (button == 1 || button == 2) { templateTarget = -1; earlyTemplateButton = button; return true; }
        if (button != 0) return false;
        var template = TemplateBrowsers.selection();
        if (template.isEmpty()) return false; // Search, paging and empty space keep their normal routing.
        setTemplate(templateTarget, template); earlyTemplateButton = button; return true;
    }
    public static boolean setTemplate(int slot, ItemStack template) {
        return setTemplate(slot, TemplateBrowsers.Template.of(template));
    }
    public static boolean setTemplate(int slot, TemplateBrowsers.Template template) {
        if (!templateTarget(slot) || template == null || template.isEmpty()) return false;
        try {
            boolean fluid = !template.fluid().isEmpty();
            String encoded = fluid ? dev.scathiard.feedmepackages.fluid.FluidVariantKey.of(template.fluid(), MC.player.registryAccess()).encoded()
                    : ItemVariantKey.of(template.item(), MC.player.registryAccess()).encoded();
            if (!send(fluid ? CacheActions.Action.SET_FLUID_GHOST : CacheActions.Action.SET_GHOST, slot, 0, -1, encoded)) return false;
            templateSequence = waiting; return true;
        } catch (IllegalArgumentException invalid) { notice("result.invalid_item"); return false; }
    }

    public static boolean fillRecipe(ResourceLocation recipe, boolean maximum) {
        return LogisticsPanel.recipeReady() && LogisticsPanel.send(CacheActions.Action.FILL_RECIPE, 0, maximum ? 1 : 0, -1, recipe.toString());
    }

    public static List<PanelLayout.CellBox> visibleCells(Screen candidate) {
        if (!LogisticsPanel.current(candidate) || !LogisticsPanel.active() || layout == null
                || layout.compact() || layout.hidden() || waiting != 0) {
            return List.of();
        }
        return layout.cells();
    }

    public static List<PanelLayout.Rect> exclusions(Screen candidate) {
        return LogisticsPanel.current(candidate) && LogisticsPanel.visible() ? (layout.slider()==null ? List.of(layout.bounds()) : List.of(layout.bounds(),layout.slider())) : List.of();
    }

    private static boolean send(CacheActions.Action action, int slot, int first, int second, String template) {
        ItemStack held;
        if (snapshot == null || snapshot.session() == null || waiting != 0 || cursorPending || pendingCursorChange != null || window == null) {
            return false;
        }
        boolean creative = screen instanceof CreativeModeInventoryScreen && (action == CacheActions.Action.DEPOSIT || action == CacheActions.Action.TAKE_CURSOR || action == CacheActions.Action.DEPOSIT_FLUID);
        String cursor = "";
        int count = 0;
        if (creative && !(held = LogisticsPanel.MC.player.containerMenu.getCarried()).isEmpty()) {
            try {
                cursor = ItemVariantKey.of(held, (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess()).encoded();
                count = held.getCount();
            }
            catch (IllegalArgumentException invalid) {
                LogisticsPanel.notice("result.invalid_item");
                return false;
            }
        }
        CacheActions.Intent intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), action, slot, first, second, template);
        waiting = ++sequence;
        waitingSince = tick;
        // The one-key collect is silent on every non-OK result (user's silence rule): its answer is only ever
        // the server's own action-bar line when something actually moved.
        if (action == CacheActions.Action.COLLECT_MATCHING) silentCollectSequence = waiting;
        recordPredict(action, slot, first, template, waiting);
        if (creative) {
            cursorSession = snapshot.session(); cursorOperation = waiting;
            cursorExpected = MC.player.containerMenu.getCarried().copy(); cursorPending = true;
            if (action == CacheActions.Action.DEPOSIT || action == CacheActions.Action.DEPOSIT_FLUID) pendingCursorChange = new PendingCursorChange(window, cursorSession, waiting, cursorExpected);
            if (action == CacheActions.Action.TAKE_CURSOR) {
                previewSeq = waiting; previewRemaining = 0; previewVariant = null; previewConfirmed = false;
            } else if (!previewConfirmed) previewSeq = 0;
        }
        PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Command(window, waiting, intent, creative, cursor, count), (CustomPacketPayload[])new CustomPacketPayload[0]);
        return true;
    }

    /** Record a client-only display prediction for a {TAKE_CURSOR} (pick-up) or source-cell DEPOSIT
     *  (put-back) so the number changes immediately, before the server confirmation returns. It is a
     *  display alias only: it never creates items or writes inventory, and is cleared on ack/close. */
    private static void recordPredict(CacheActions.Action action, int slot, int first, String template, int sequence) {
        if (!(action == CacheActions.Action.TAKE_CURSOR || action == CacheActions.Action.DEPOSIT)) { predict = null; return; }
        if (snapshot == null || slot < 0 || slot >= snapshot.cells().size() || waiting == 0) { predict = null; return; }
        var cell = snapshot.cells().get(slot);
        if (cell.template().isEmpty()) { predict = null; return; }
        int base = Math.max(0, cell.amount() - cell.reserved());
        int delta;
        if (action == CacheActions.Action.TAKE_CURSOR) {
            // Worst-case pick-up amount is bounded by the request, the cell and the native stack cap.
            int cap = cell.stackSize();
            delta = Math.min(Math.max(0, base), Math.min(Math.max(0, first), cap));
        } else {
            // Source-cell put-back returns the carried stack to this cell; delta <= carried count.
            int carried = LogisticsPanel.MC.player.containerMenu.getCarried().getCount();
            delta = Math.min(carried, Math.max(0, cell.stackSize() - base));
        }
        int result = action == CacheActions.Action.TAKE_CURSOR ? Math.max(0, base - delta) : base + delta;
        predict = new Predict(window, snapshot.session(), slot, cell.template(), sequence, serial, result);
        // Track this client's local preview ownership for the withdrawn-amount bound of this take.
        // It is pending (previewConfirmed=false) until the server acknowledges; a rejected/expired take
        // never becomes confirmed and thus never claims a later independent same-variant carry.

    }

    private static Component tr(String key, Object ... args) {
        return Component.translatable((String)("gui.create_feed_me_packages." + key), (Object[])args);
    }

    private static void notice(String key) {
        feedback = LogisticsPanel.tr(key, new Object[0]);
        feedbackUntil = tick + 120;
    }

    static {
        ICONS = new HashMap<String, ItemStack>();
        selected = -1;
        serial = -1L;
        capturedButton = -1;
        returnBuffer = "";
        tooltip = List.of();
        PANEL = ResourceLocation.fromNamespaceAndPath((String)"create_feed_me_packages", (String)"textures/gui/panel.png");
    }
}
