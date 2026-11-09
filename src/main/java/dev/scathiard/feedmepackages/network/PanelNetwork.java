package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.compat.maid.MaidMenus;
import dev.scathiard.feedmepackages.compat.maid.MaidPickup;
import dev.scathiard.feedmepackages.domain.CacheCapacity;
import dev.scathiard.feedmepackages.domain.CacheGrid;
import dev.scathiard.feedmepackages.domain.CacheViewport;
import dev.scathiard.feedmepackages.domain.ReturnAddress;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.interaction.PanelAccess;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.LogisticsSignals;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Server/client network coordinator for the Logistics Panel overlay.
 *
 * <p>Manages per-player open panel windows, rate-limiting budgets, snapshot serialization,
 * cursor preview synchronization, and close-time final settings receipts.
 */
public final class PanelNetwork {
    private PanelNetwork() {}

    // Installed only by client bootstrap. Common registration never resolves Minecraft/client classes on a server.
    private static Consumer<PanelPackets.Snapshot> clientReceiver = packet -> {};
    private static Consumer<PanelPackets.CursorUpdate> cursorReceiver = packet -> {};
    private static Consumer<PanelPackets.SelfLevel> levelReceiver = packet -> {};
    private static Consumer<PanelPackets.FinalSettingsReceipt> finalSettingsReceiver = packet -> {};

    public static void receiveOnClient(Consumer<PanelPackets.Snapshot> receiver) {
        clientReceiver = Objects.requireNonNull(receiver);
    }

    public static void receiveCursorOnClient(Consumer<PanelPackets.CursorUpdate> receiver) {
        cursorReceiver = Objects.requireNonNull(receiver);
    }

    public static void receiveLevelOnClient(Consumer<PanelPackets.SelfLevel> receiver) {
        levelReceiver = Objects.requireNonNull(receiver);
    }

    public static void receiveFinalSettingsOnClient(Consumer<PanelPackets.FinalSettingsReceipt> receiver) {
        finalSettingsReceiver = Objects.requireNonNull(receiver);
    }

    private record CompletedFinal(
            PanelPackets.FinalSettings request,
            PanelPackets.FinalSettingsReceipt receipt,
            int tick) {}

    // One bounded (<=8192-byte) replay receipt per connected player, including after native close.
    private static final Map<ServerPlayer, CompletedFinal> FINALS = new WeakHashMap<>();

    private record SentLevel(UUID cache, int level) {}

    private static final Map<ServerPlayer, SentLevel> LEVELS = new WeakHashMap<>();

    /**
     * Pushes the player's own cache level when it changes (wear, unwear, upgrade), at most once a second. The
     * pendant tooltip needs it while no panel is open; nothing about the item itself is synced or saved.
     */
    public static void tickLevel(ServerPlayer player) {
        if (player.tickCount % 20 != 0) {
            return;
        }
        var access = AccessGate.resolve(player);
        UUID cache = access.active() ? access.handle().cacheId() : null;
        var record = cache == null ? null : CacheLedger.get(player.getServer()).find(cache);
        int level = record == null ? 0 : record.state().level();
        var sent = LEVELS.get(player);
        if (sent != null && Objects.equals(sent.cache(), cache) && sent.level() == level) {
            return;
        }
        LEVELS.put(player, new SentLevel(cache, level));
        PacketDistributor.sendToPlayer(
                player,
                new PanelPackets.SelfLevel(cache == null ? new UUID(0L, 0L) : cache, level));
    }

    private static final Map<ServerPlayer, Window> WINDOWS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Budget> BUDGETS = new WeakHashMap<>();

    private static final class Window {
        final UUID id;
        final int menu;
        final ResourceKey<Level> dimension;
        long serial;
        long cursorSerial;
        int lastSequence;
        int firstSlot;
        int columnLimit = 6;
        boolean finalized;
        LogisticsSignals.Viewer feedback;
        CacheActions.Result lastResult = CacheActions.Result.OK;

        Window(UUID id, int menu, ServerPlayer player) {
            this.id = id;
            this.menu = menu;
            this.dimension = player.level().dimension();
        }

        void closeFeedback() {
            if (feedback != null) {
                feedback.close();
                feedback = null;
            }
        }
    }

    private static final class Budget {
        int queryTick = Integer.MIN_VALUE;
        int actionTick;
        int actions;

        boolean query(int tick) {
            if ((long) tick - queryTick < 4) {
                return false;
            }
            queryTick = tick;
            return true;
        }

        boolean action(int tick) {
            if (tick - actionTick >= 20 || tick < actionTick) {
                actionTick = tick;
                actions = 0;
            }
            return ++actions <= 16;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("18-fluid-batches");
        registrar.playToServer(PanelPackets.Query.TYPE, PanelPackets.Query.CODEC, (packet, context) -> {
            var reply = query((ServerPlayer) context.player(), packet);
            if (reply != null) {
                context.reply(reply);
            }
        });
        registrar.playToServer(PanelPackets.Command.TYPE, PanelPackets.Command.CODEC, (packet, context) -> {
            var reply = command((ServerPlayer) context.player(), packet);
            if (reply != null) {
                context.reply(reply);
            }
        });
        registrar.playToClient(
                PanelPackets.Snapshot.TYPE,
                PanelPackets.Snapshot.CODEC,
                (packet, context) -> clientReceiver.accept(packet));
        registrar.playToServer(PanelPackets.SettingsBatch.TYPE, PanelPackets.SettingsBatch.CODEC, (packet, context) -> {
            var reply = settings((ServerPlayer) context.player(), packet);
            if (reply != null) {
                context.reply(reply);
            }
        });
        registrar.playToServer(
                PanelPackets.FinalSettings.TYPE,
                PanelPackets.FinalSettings.CODEC,
                (packet, context) -> context.reply(finalSettings((ServerPlayer) context.player(), packet)));
        registrar.playToClient(
                PanelPackets.FinalSettingsReceipt.TYPE,
                PanelPackets.FinalSettingsReceipt.CODEC,
                (packet, context) -> finalSettingsReceiver.accept(packet));
        registrar.playToClient(
                PanelPackets.CursorUpdate.TYPE,
                PanelPackets.CursorUpdate.CODEC,
                (packet, context) -> cursorReceiver.accept(packet));
        registrar.playToClient(
                PanelPackets.SelfLevel.TYPE,
                PanelPackets.SelfLevel.CODEC,
                (packet, context) -> levelReceiver.accept(packet));
    }

    public static void forget(ServerPlayer player) {
        FINALS.remove(player);
        LEVELS.remove(player);
        var window = WINDOWS.remove(player);
        if (window != null) {
            window.closeFeedback();
        }
        BUDGETS.remove(player);
        CacheActions.close(player);
    }

    public static PanelPackets.FinalSettingsReceipt finalSettings(ServerPlayer player, PanelPackets.FinalSettings packet) {
        var batch = packet.settings();
        var cached = FINALS.get(player);
        if (cached != null && (long) player.tickCount - cached.tick() > 400) {
            FINALS.remove(player);
            cached = null;
        }
        if (cached != null && cached.request().equals(packet)) {
            return cached.receipt();
        }
        var result = CacheActions.Result.STALE;
        var window = WINDOWS.get(player);
        if (window != null
                && window.id.equals(batch.window())
                && window.menu == packet.menu()
                && window.menu == player.containerMenu.containerId
                && window.dimension.equals(player.level().dimension())
                && !window.finalized
                && batch.sequence() > window.lastSequence) {
            // Exactly once per established window, independently of the ordinary action budget.
            window.finalized = true;
            window.lastSequence = batch.sequence();
            result = CacheActions.settings(player, batch.session(), batch.changes());
            var receipt = new PanelPackets.FinalSettingsReceipt(batch.window(), batch.session(), batch.sequence(), result);
            FINALS.put(player, new CompletedFinal(packet, receipt, player.tickCount));
            return receipt;
        }
        return new PanelPackets.FinalSettingsReceipt(batch.window(), batch.session(), batch.sequence(), result);
    }

    public static PanelPackets.Snapshot settings(ServerPlayer player, PanelPackets.SettingsBatch packet) {
        var window = WINDOWS.get(player);
        if (window == null || !window.id.equals(packet.window()) || window.menu != player.containerMenu.containerId) {
            return null;
        }
        boolean allowed = BUDGETS.computeIfAbsent(player, p -> new Budget()).action(player.tickCount);
        if (window.finalized || packet.sequence() <= window.lastSequence) {
            return allowed ? snapshot(player, window, packet.sequence(), CacheActions.Result.STALE) : null;
        }
        window.lastSequence = packet.sequence();
        var result = allowed
                ? CacheActions.settings(player, packet.session(), packet.changes())
                : CacheActions.Result.TOO_COMPLEX;
        window.lastResult = result;
        return allowed ? snapshot(player, window, packet.sequence(), result) : null;
    }

    public static PanelPackets.Snapshot query(ServerPlayer player, PanelPackets.Query packet) {
        var window = WINDOWS.get(player);
        if (!packet.open()) {
            if (window != null && window.id.equals(packet.window())) {
                window.closeFeedback();
                WINDOWS.remove(player);
                CacheActions.close(player);
            }
            return null;
        }
        if (!BUDGETS.computeIfAbsent(player, p -> new Budget()).query(player.tickCount)) {
            return null;
        }
        if (packet.menu() != player.containerMenu.containerId
                || packet.firstSlot() < 0
                || packet.firstSlot() > CacheCapacity.MAX_SLOTS
                || packet.columnLimit() < 2
                || packet.columnLimit() > 6) {
            return null;
        }
        if (window == null || !window.id.equals(packet.window()) || window.menu != packet.menu()) {
            if (window != null) {
                window.closeFeedback();
            }
            CacheActions.close(player);
            window = new Window(packet.window(), packet.menu(), player);
            WINDOWS.put(player, window);
        }
        window.firstSlot = packet.firstSlot();
        window.columnLimit = packet.columnLimit();
        return snapshot(player, window, window.lastSequence, window.lastResult);
    }

    public static PanelPackets.Snapshot command(ServerPlayer player, PanelPackets.Command packet) {
        var window = WINDOWS.get(player);
        boolean ownsInput = packet.creativeCursor()
                && (packet.intent().action() == CacheActions.Action.DEPOSIT
                        || packet.intent().action() == CacheActions.Action.DEPOSIT_FLUID
                        || packet.intent().action() == CacheActions.Action.FILL_FLUID);
        if (window == null || !window.id.equals(packet.window()) || window.menu != player.containerMenu.containerId) {
            return ownsInput ? refusal(packet, CacheActions.Result.STALE) : null;
        }
        boolean replyAllowed = BUDGETS.computeIfAbsent(player, p -> new Budget()).action(player.tickCount);
        var action = packet.intent().action();
        boolean cleanup = action == CacheActions.Action.RELEASE_PREVIEW
                || action == CacheActions.Action.RELEASE_FLUID
                || action == CacheActions.Action.CREATIVE_BEGIN
                || action == CacheActions.Action.CREATIVE_END;
        if (packet.sequence() <= window.lastSequence || (window.finalized && !cleanup)) {
            return replyAllowed ? snapshot(player, window, packet.sequence(), CacheActions.Result.STALE) : null;
        }
        window.lastSequence = packet.sequence();
        var result = !replyAllowed && !cleanup
                ? CacheActions.Result.TOO_COMPLEX
                : action == CacheActions.Action.CREATIVE_BEGIN || action == CacheActions.Action.CREATIVE_END
                        ? creativeOperation(player, packet)
                        : packet.creativeCursor()
                                ? CacheActions.executeCreative(player, packet.intent(), packet.cursorTemplate(), packet.cursorCount(), packet.sequence())
                                : CacheActions.execute(player, packet.intent(), packet.sequence());
        window.lastResult = result;
        // Cleanup is O(1), affects only one exact Hold, and cannot be silently skipped before a native
        // placement. Bound replies independently instead of dropping the cleanup with the UI budget.
        return replyAllowed ? snapshot(player, window, packet.sequence(), result) : ownsInput ? refusal(packet, result) : null;
    }

    private static PanelPackets.Snapshot refusal(PanelPackets.Command packet, CacheActions.Result result) {
        // A real cursor input must get an explicit rejection even if its menu closed or the UI budget
        // ran out. This constant-size receipt reveals no inventory and performs no ledger lookup.
        return new PanelPackets.Snapshot(
                packet.window(),
                packet.intent().session(),
                -1,
                packet.sequence(),
                result,
                AccessGate.Status.NOT_WORN,
                -1,
                0,
                0,
                false,
                false,
                false,
                "",
                "",
                "",
                List.of(),
                List.of());
    }

    private static CacheActions.Result creativeOperation(ServerPlayer player, PanelPackets.Command packet) {
        var intent = packet.intent();
        if (packet.creativeCursor() || !player.gameMode.isCreative()) {
            return CacheActions.Result.INVALID_REQUEST;
        }
        var view = CacheActions.snapshot(player);
        if (!Objects.equals(view.session(), intent.session())) {
            return CacheActions.Result.STALE;
        }
        ItemStack cursor;
        try {
            // An unrelated cursor (e.g. a full backpack swapped from a native slot) never needs to
            // pass through our bounded cache-template codec. -1 is END-only and means do not write it.
            boolean detached = intent.action() == CacheActions.Action.CREATIVE_END
                    && intent.slot() == -1
                    && intent.template().isEmpty();
            cursor = detached
                    ? null
                    : intent.slot() == 0 && intent.template().isEmpty()
                            ? ItemStack.EMPTY
                            : ItemVariantKey.decode(intent.template(), player.registryAccess()).stack(player.registryAccess(), intent.slot());
            if (!detached && (cursor.getCount() > cursor.getMaxStackSize() || intent.slot() < 0)) {
                return CacheActions.Result.INVALID_ITEM;
            }
        } catch (IllegalArgumentException invalid) {
            return CacheActions.Result.INVALID_ITEM;
        }
        return intent.action() == CacheActions.Action.CREATIVE_BEGIN
                ? CursorReservations.beginCreative(player, intent.session(), intent.first(), packet.sequence(), intent.second(), cursor)
                : CursorReservations.endCreative(player, intent.session(), intent.first(), intent.second(), cursor);
    }

    public static void sendCursor(ServerPlayer player, UUID session, int takeSequence, ItemStack cursor, int remaining) {
        var window = WINDOWS.get(player);
        if (window == null || window.menu != player.containerMenu.containerId || session == null) {
            return;
        }
        var payload = new PanelPackets.CursorUpdate(
                window.id,
                session,
                takeSequence,
                window.lastSequence,
                ++window.cursorSerial,
                cursor == null || cursor.isEmpty() ? "" : ItemVariantKey.of(cursor, player.registryAccess()).encoded(),
                cursor == null ? -1 : cursor.getCount(),
                remaining);
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    private static PanelPackets.Snapshot snapshot(ServerPlayer player, Window window, int ack, CacheActions.Result result) {
        var view = CacheActions.snapshot(player);
        var access = view.access();
        var record = view.record();
        if (access.terminals().size() > PanelPackets.MAX_TERMINALS) {
            window.closeFeedback();
            CacheActions.close(player);
            return new PanelPackets.Snapshot(
                    window.id,
                    null,
                    ++window.serial,
                    ack,
                    CacheActions.Result.INVALID_REQUEST,
                    AccessGate.Status.INVALID_IDENTITY,
                    -1,
                    0,
                    0,
                    false,
                    false,
                    false,
                    "",
                    "",
                    "",
                    List.of(),
                    List.of());
        }
        var terminals = access.terminals().stream()
                .map(t -> new PanelPackets.TerminalView(
                        t.slot(),
                        false,
                        t.disabled(),
                        t.network() == null ? "" : t.network().toString()))
                .toList();
        List<PanelPackets.CellView> cells = new ArrayList<>();
        String residualItem = "";
        String returnAddress = access.active() && access.handle() != null
                ? CacheLedger.get(player.getServer()).returnAddress(access.handle().cacheId())
                : null;
        boolean preservedAddress = returnAddress != null && !ReturnAddress.valid(returnAddress);
        if (record == null) {
            window.closeFeedback();
        }
        if (record != null) {
            if (window.feedback == null || !window.feedback.matches(record.state().id())) {
                window.closeFeedback();
                window.feedback = new LogisticsSignals.Viewer(record.state().id());
            }
            window.firstSlot = CacheViewport.first(record.state().cells().size(), window.firstSlot, window.columnLimit);
            var grid = CacheGrid.window(record.state().cells().size(), window.firstSlot, window.columnLimit);
            window.feedback.page(grid.storageSlots());
            for (int i : grid.storageSlots()) {
                var fluidCell = record.fluids().state().cells().get(i);
                if (fluidCell.filter() != null) {
                    String template = fluidCell.filter().encoded();
                    int reserved = (int) Math.min(Integer.MAX_VALUE, FluidActions.reserved(access.handle().cacheId(), i));
                    cells.add(new PanelPackets.CellView(
                            template,
                            fluidCell.amount(),
                            fluidCell.settings().lower(),
                            fluidCell.settings().upper(),
                            record.fluids().state().pending(i),
                            reserved,
                            fluidCell.filter().stackSize(),
                            record.fluids().hasResidual(fluidCell.filter()),
                            ReturnService.dispatchState(
                                    access.handle(),
                                    returnAddress,
                                    i,
                                    "fluid:" + template,
                                    fluidCell.settings().upper(),
                                    fluidCell.settings().returnBatch()),
                            fluidCell.settings().batch(),
                            -1,
                            fluidCell.filterRevision(),
                            window.feedback.get(i),
                            true,
                            record.fluids().state().writable(i),
                            fluidCell.settings().returnBatch()));
                    continue;
                }
                var cell = record.state().cells().get(i);
                boolean slotResidual = cell.filter() != null && !record.residual(cell.filter()).isEmpty();
                int reserved = cell.filter() == null ? 0 : CursorReservations.reserved(access.handle().cacheId(), i);
                String template = cell.filter() == null ? "" : cell.filter().encoded();
                cells.add(new PanelPackets.CellView(
                        template,
                        cell.amount(),
                        cell.settings().lower(),
                        cell.settings().upper(),
                        record.state().pending(i),
                        reserved,
                        cell.filter() == null ? 1 : cell.filter().stackSize(),
                        slotResidual,
                        ReturnService.dispatchState(
                                access.handle(),
                                returnAddress,
                                i,
                                template,
                                cell.settings().upper(),
                                cell.settings().returnBatch()),
                        cell.settings().batch(),
                        cell.settings().pendingLimit(),
                        cell.filterRevision(),
                        window.feedback.get(i),
                        false,
                        record.state().writable(i),
                        cell.settings().returnBatch()));
            }
            for (var box : record.residuals().values()) {
                if (!box.isEmpty()) {
                    residualItem = BuiltInRegistries.ITEM.getKey(box.getItem()).toString();
                    break;
                }
            }
        }
        var fluid = FluidActions.preview(player);
        return new PanelPackets.Snapshot(
                window.id,
                view.session(),
                ++window.serial,
                ack,
                result,
                access.status(),
                record == null ? -1 : record.state().revision(),
                record == null ? 0 : record.state().level(),
                record == null ? 0 : Math.multiplyExact(record.state().capacity().groups(), 64),
                record != null && record.owner() != null,
                view.cacheFirst(),
                access.active() && access.handle().networkId() != null,
                PanelAccess.address(player),
                returnAddress == null || preservedAddress ? "" : returnAddress,
                residualItem,
                cells,
                terminals,
                new PanelPackets.FluidPreviewView(fluid.slot(), fluid.template(), fluid.amount(), fluid.sequence()),
                record == null ? 0 : window.firstSlot,
                record == null ? 0 : record.state().cells().size(),
                window.columnLimit,
                access.active() && MaidPickup.enabled(MaidMenus.target(player.containerMenu)),
                record == null || record.craftingEnabled(),
                preservedAddress,
                FluidPackages.capacity());
    }
}
