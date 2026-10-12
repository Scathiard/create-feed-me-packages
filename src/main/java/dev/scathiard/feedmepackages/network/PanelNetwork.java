package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.world.item.ItemStack;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

public final class PanelNetwork {
    private PanelNetwork() {}
    // Installed only by client bootstrap. Common registration never resolves Minecraft/client classes on a server.
    private static Consumer<PanelPackets.Snapshot> clientReceiver = packet -> {};
    public static void receiveOnClient(Consumer<PanelPackets.Snapshot> receiver) { clientReceiver = Objects.requireNonNull(receiver); }
    private static Consumer<PanelPackets.CursorUpdate> cursorReceiver = packet -> {};
    public static void receiveCursorOnClient(Consumer<PanelPackets.CursorUpdate> receiver) { cursorReceiver = Objects.requireNonNull(receiver); }
    private static final Map<ServerPlayer, Window> WINDOWS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Budget> BUDGETS = new WeakHashMap<>();
    private static final class Window {
        final UUID id; final int menu; long serial, cursorSerial; int lastSequence, firstSlot, columnLimit=6;
        dev.scathiard.feedmepackages.logistics.LogisticsSignals.Viewer feedback;
        void closeFeedback() { if (feedback != null) { feedback.close(); feedback = null; } }
        CacheActions.Result lastResult = CacheActions.Result.OK;
        Window(UUID id, int menu) { this.id = id; this.menu = menu; }
    }
    private static final class Budget {
        int queryTick = Integer.MIN_VALUE, actionTick, actions;
        boolean query(int tick) { if ((long) tick - queryTick < 4) return false; queryTick = tick; return true; }
        boolean action(int tick) {
            if (tick - actionTick >= 20 || tick < actionTick) { actionTick = tick; actions = 0; }
            return ++actions <= 16;
        }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("9-converged-maid-pickup");
        registrar.playToServer(PanelPackets.Query.TYPE, PanelPackets.Query.CODEC, (packet, context) -> {
            var reply = query((ServerPlayer) context.player(), packet); if (reply != null) context.reply(reply);
        });
        registrar.playToServer(PanelPackets.Command.TYPE, PanelPackets.Command.CODEC, (packet, context) -> {
            var reply = command((ServerPlayer) context.player(), packet); if (reply != null) context.reply(reply);
        });
        registrar.playToClient(PanelPackets.Snapshot.TYPE, PanelPackets.Snapshot.CODEC, (packet, context) -> clientReceiver.accept(packet));
        registrar.playToServer(PanelPackets.SettingsBatch.TYPE,PanelPackets.SettingsBatch.CODEC,(packet,context)->{
            var reply=settings((ServerPlayer)context.player(),packet);if(reply!=null)context.reply(reply);
        });
        registrar.playToClient(PanelPackets.CursorUpdate.TYPE, PanelPackets.CursorUpdate.CODEC, (packet, context) -> cursorReceiver.accept(packet));
    }
    public static void forget(ServerPlayer player) { var window = WINDOWS.remove(player); if (window != null) window.closeFeedback(); BUDGETS.remove(player); CacheActions.close(player); }
    public static PanelPackets.Snapshot settings(ServerPlayer player,PanelPackets.SettingsBatch packet) {
        var window=WINDOWS.get(player);
        if(window==null || !window.id.equals(packet.window()) || window.menu!=player.containerMenu.containerId)return null;
        boolean allowed=BUDGETS.computeIfAbsent(player,p->new Budget()).action(player.tickCount);
        if(packet.sequence()<=window.lastSequence)return allowed?snapshot(player,window,packet.sequence(),CacheActions.Result.STALE):null;
        window.lastSequence=packet.sequence();
        var result=allowed?CacheActions.settings(player,packet.session(),packet.changes()):CacheActions.Result.TOO_COMPLEX;
        window.lastResult=result;return allowed?snapshot(player,window,packet.sequence(),result):null;
    }
    public static PanelPackets.Snapshot query(ServerPlayer player, PanelPackets.Query packet) {
        var window = WINDOWS.get(player);
        if (!packet.open()) {
            if (window != null && window.id.equals(packet.window())) { window.closeFeedback(); WINDOWS.remove(player); CacheActions.close(player); }
            return null;
        }
        if (!BUDGETS.computeIfAbsent(player, p -> new Budget()).query(player.tickCount)) return null;
        if (packet.menu() != player.containerMenu.containerId || packet.firstSlot()<0 || packet.firstSlot()>dev.scathiard.feedmepackages.domain.CacheCapacity.MAX_SLOTS || packet.columnLimit()<2 || packet.columnLimit()>6) return null;
        if (window == null || !window.id.equals(packet.window()) || window.menu != packet.menu()) {
            if (window != null) window.closeFeedback();
            CacheActions.close(player); window = new Window(packet.window(), packet.menu()); WINDOWS.put(player, window);
        }
        window.firstSlot=packet.firstSlot();window.columnLimit=packet.columnLimit();
        return snapshot(player, window, window.lastSequence, window.lastResult);
    }
    public static PanelPackets.Snapshot command(ServerPlayer player, PanelPackets.Command packet) {
        var window = WINDOWS.get(player);
        boolean ownsInput = packet.creativeCursor() && (packet.intent().action() == CacheActions.Action.DEPOSIT || packet.intent().action() == CacheActions.Action.DEPOSIT_FLUID);
        if (window == null || !window.id.equals(packet.window()) || window.menu != player.containerMenu.containerId)
            return ownsInput ? refusal(packet, CacheActions.Result.STALE) : null;
        boolean replyAllowed = BUDGETS.computeIfAbsent(player, p -> new Budget()).action(player.tickCount);
        var action = packet.intent().action();
        boolean cleanup = action == CacheActions.Action.RELEASE_PREVIEW || action == CacheActions.Action.RELEASE_FLUID || action == CacheActions.Action.CREATIVE_BEGIN
                || action == CacheActions.Action.CREATIVE_END;
        if (packet.sequence() <= window.lastSequence)
            return replyAllowed ? snapshot(player, window, packet.sequence(), CacheActions.Result.STALE) : null;
        window.lastSequence = packet.sequence();
        var result = !replyAllowed && !cleanup ? CacheActions.Result.TOO_COMPLEX
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
        return new PanelPackets.Snapshot(packet.window(), packet.intent().session(), -1, packet.sequence(), result,
                AccessGate.Status.NOT_WORN, -1, 0, 0, false, false, false, "", "", "", List.of(), List.of());
    }
    private static CacheActions.Result creativeOperation(ServerPlayer player, PanelPackets.Command packet) {
        var intent = packet.intent();
        if (packet.creativeCursor() || !player.gameMode.isCreative()) return CacheActions.Result.INVALID_REQUEST;
        var view = CacheActions.snapshot(player);
        if (!Objects.equals(view.session(), intent.session())) return CacheActions.Result.STALE;
        ItemStack cursor;
        try {
            // An unrelated cursor (e.g. a full backpack swapped from a native slot) never needs to
            // pass through our bounded cache-template codec. -1 is END-only and means do not write it.
            boolean detached = intent.action() == CacheActions.Action.CREATIVE_END && intent.slot() == -1 && intent.template().isEmpty();
            cursor = detached ? null : intent.slot() == 0 && intent.template().isEmpty() ? ItemStack.EMPTY
                    : ItemVariantKey.decode(intent.template(), player.registryAccess()).stack(player.registryAccess(), intent.slot());
            if (!detached && (cursor.getCount() > cursor.getMaxStackSize() || intent.slot() < 0)) return CacheActions.Result.INVALID_ITEM;
        } catch (IllegalArgumentException invalid) { return CacheActions.Result.INVALID_ITEM; }
        return intent.action() == CacheActions.Action.CREATIVE_BEGIN
                ? CursorReservations.beginCreative(player, intent.session(), intent.first(), packet.sequence(), intent.second(), cursor)
                : CursorReservations.endCreative(player, intent.session(), intent.first(), intent.second(), cursor);
    }
    public static void sendCursor(ServerPlayer player, UUID session, int takeSequence, ItemStack cursor, int remaining) {
        var window = WINDOWS.get(player);
        if (window == null || window.menu != player.containerMenu.containerId || session == null) return;
        var payload = new PanelPackets.CursorUpdate(window.id, session, takeSequence, window.lastSequence,
                ++window.cursorSerial, cursor == null || cursor.isEmpty() ? "" : ItemVariantKey.of(cursor, player.registryAccess()).encoded(),
                cursor == null ? -1 : cursor.getCount(), remaining);
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload));
    }
    private static PanelPackets.Snapshot snapshot(ServerPlayer player, Window window, int ack, CacheActions.Result result) {
        var view = CacheActions.snapshot(player); var access = view.access(); var record = view.record();
        if (access.terminals().size() > PanelPackets.MAX_TERMINALS) {
            window.closeFeedback();
            CacheActions.close(player);
            return new PanelPackets.Snapshot(window.id, null, ++window.serial, ack, CacheActions.Result.INVALID_REQUEST,
                    AccessGate.Status.INVALID_IDENTITY, -1, 0, 0, false, false, false, "", "", "", List.of(), List.of());
        }
        var terminals = access.terminals().stream().map(t -> new PanelPackets.TerminalView(t.slot(),
                false, t.disabled(), t.network() == null ? "" : t.network().toString())).toList();
        List<PanelPackets.CellView> cells = new ArrayList<>();
        String residualItem = "";
        String returnAddress = access.active() && access.handle() != null
                ? dev.scathiard.feedmepackages.storage.CacheLedger.get(player.getServer()).returnAddress(access.handle().cacheId()) : null;
        if (record == null) window.closeFeedback();
        if (record != null) {
            if (window.feedback == null || !window.feedback.matches(record.state().id())) {
                window.closeFeedback(); window.feedback = new dev.scathiard.feedmepackages.logistics.LogisticsSignals.Viewer(record.state().id());
            }
            window.firstSlot=dev.scathiard.feedmepackages.domain.CacheViewport.first(record.state().cells().size(),window.firstSlot,window.columnLimit);
            var grid=dev.scathiard.feedmepackages.domain.CacheGrid.window(record.state().cells().size(),window.firstSlot,window.columnLimit);
            window.feedback.page(grid.storageSlots());
            for (int i : grid.storageSlots()) {
                var fluidCell = record.fluids().state().cells().get(i);
                if (fluidCell.filter() != null) {
                    String template = fluidCell.filter().encoded();
                    int reserved = (int)Math.min(Integer.MAX_VALUE, dev.scathiard.feedmepackages.interaction.FluidActions.reserved(access.handle().cacheId(), i));
                    cells.add(new PanelPackets.CellView(template, fluidCell.amount(), fluidCell.settings().lower(), fluidCell.settings().upper(),
                            record.fluids().state().pending(i), reserved, fluidCell.filter().stackSize(), record.fluids().hasResidual(fluidCell.filter()),
                            dev.scathiard.feedmepackages.logistics.ReturnService.dispatchState(access.handle(), returnAddress, i, "fluid:" + template, fluidCell.settings().upper()),
                            1, -1, fluidCell.filterRevision(), window.feedback.get(i), true, record.fluids().state().writable(i)));
                    continue;
                }
                var cell = record.state().cells().get(i);
                boolean slotResidual = cell.filter() != null && !record.residual(cell.filter()).isEmpty();
                int reserved = cell.filter() == null ? 0 : dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(access.handle().cacheId(), i);
                String template = cell.filter() == null ? "" : cell.filter().encoded();
                cells.add(new PanelPackets.CellView(template, cell.amount(), cell.settings().lower(), cell.settings().upper(), record.state().pending(i), reserved, cell.filter() == null ? 1 : cell.filter().stackSize(), slotResidual,
                        dev.scathiard.feedmepackages.logistics.ReturnService.dispatchState(access.handle(), returnAddress, i, template, cell.settings().upper()), cell.settings().batch(), cell.settings().pendingLimit(), cell.filterRevision(), window.feedback.get(i), false, record.state().writable(i)));
            }
            for (var box : record.residuals().values())
                if (!box.isEmpty()) { residualItem = BuiltInRegistries.ITEM.getKey(box.getItem()).toString(); break; }
        }
        var fluid = dev.scathiard.feedmepackages.interaction.FluidActions.preview(player);
        return new PanelPackets.Snapshot(window.id, view.session(), ++window.serial, ack, result, access.status(),
                record == null ? -1 : record.state().revision(), record == null ? 0 : record.state().level(),
                record == null ? 0 : Math.multiplyExact(record.state().capacity().groups(),64), record != null && record.owner() != null,
                view.cacheFirst(), access.active() && access.handle().networkId() != null, dev.scathiard.feedmepackages.interaction.PanelAccess.address(player),
                returnAddress == null ? "" : returnAddress, residualItem, cells, terminals,
                new PanelPackets.FluidPreviewView(fluid.slot(), fluid.template(), fluid.amount(), fluid.sequence()),
                record==null?0:window.firstSlot,record==null?0:record.state().cells().size(),window.columnLimit,
                access.active() && dev.scathiard.feedmepackages.compat.maid.MaidPickup.enabled(
                        dev.scathiard.feedmepackages.compat.maid.MaidMenus.target(player.containerMenu)));
    }
}
