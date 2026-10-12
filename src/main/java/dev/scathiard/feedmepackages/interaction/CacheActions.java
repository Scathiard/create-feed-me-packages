package dev.scathiard.feedmepackages.interaction;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.domain.CollectPlan;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-side panel intent interpreter. A session is context, never a substitute for current access. */
public final class CacheActions {
    private CacheActions() {}
    /** Opt-in panel drop: the server resolves the exact variant across all pages. */
    public static final int AUTO_DEPOSIT = -2;
    public enum Action { DEPOSIT, TAKE_CURSOR, TAKE_INVENTORY, SET_GHOST, CLEAR_FILTER, THRESHOLDS, RESET_REQUEST, PREFERENCE, TERMINAL_ENABLED, CLEAR_NETWORK, TAKE_RESIDUAL, FILL_RECIPE, SET_RETURN_ADDRESS, RELEASE_PREVIEW, CREATIVE_BEGIN, CREATIVE_END, COLLECT_MATCHING, SUPPLY_SETTINGS, SET_FLUID_GHOST, TAKE_FLUID, PLACE_FLUID, DEPOSIT_FLUID, RELEASE_FLUID, MAID_PICKUP }
    public enum Result { OK, STALE, NOT_ACTIVE, INVALID_ITEM, DUPLICATE_FILTER, FILTER_OCCUPIED, NO_SPACE, INVALID_REQUEST, MISSING_MATERIAL, UNSUPPORTED_RECIPE, TOO_COMPLEX, READ_ONLY, DENIED }
    public record Intent(UUID session, long revision, Action action, int slot, int first, int second, String template) {
        public Intent {
            Objects.requireNonNull(session); Objects.requireNonNull(action); Objects.requireNonNull(template);
            if (template.getBytes(StandardCharsets.UTF_8).length > ItemVariantKey.MAX_BYTES)
                throw new IllegalArgumentException("Oversized intent template");
        }
    }
    private record TerminalProof(int slot, ItemStack stack) {}
    private record Session(UUID id, WeakReference<AbstractContainerMenu> menu, CacheHandle handle, List<TerminalProof> terminals) {}
    public record View(UUID session, AccessGate.Result access, CacheRecord record, boolean cacheFirst) {}
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();

    public static View open(ServerPlayer player) {
        FluidActions.cancel(player);
        var access = PanelAccess.resolve(player); var menu = player.containerMenu;
        if (!PanelAccess.supported(menu)) {
            SESSIONS.remove(player); return new View(null, access, null, false);
        }
        var proofs = access.terminals().stream().map(t -> new TerminalProof(t.slot(), t.stack().copy())).toList();
        var session = new Session(UUID.randomUUID(), new WeakReference<>(menu), access.handle(), proofs);
        SESSIONS.put(player, session);
        var ledger = CacheLedger.get(player.getServer());
        return new View(session.id(), access, access.active() ? ledger.find(access.handle().cacheId()) : null, ledger.cacheFirst(player.getUUID()));
    }

    public static void close(ServerPlayer player) {
        // Cancel any unplaced cursor preview (the cache amount was never deducted for it).
        CursorReservations.cancel(player);
        FluidActions.cancel(player);
        SESSIONS.remove(player);
    }

    public static View snapshot(ServerPlayer player) {
        validateOpenContext(player);
        var session = SESSIONS.get(player);
        if (session == null) return open(player);
        var access = PanelAccess.resolve(player); var ledger = CacheLedger.get(player.getServer());
        return new View(session.id(), access, access.active() ? ledger.find(access.handle().cacheId()) : null,
                ledger.cacheFirst(player.getUUID()));
    }

    public static void validateOpenContext(ServerPlayer player) {
        CursorReservations.validate(player);
        FluidActions.validate(player);
        var session = SESSIONS.get(player); if (session == null) return;
        var access = PanelAccess.resolve(player);
        if (session.menu().get() != player.containerMenu || !Objects.equals(session.handle(), access.handle())
                || access.terminals().size() != session.terminals().size()) { close(player); return; }
        for (var proof : session.terminals()) {
            var actual = access.terminals().stream().filter(t -> t.slot() == proof.slot()).findFirst().orElse(null);
            if (actual == null || !ItemStack.matches(actual.stack(), proof.stack())) { close(player); return; }
        }
    }

    public static Result execute(ServerPlayer player, Intent intent) {
        return execute(player, intent, -1);
    }
    public record SettingsChange(int slot, long filterRevision, dev.scathiard.feedmepackages.domain.CellSettings value, boolean fluid) {
        public SettingsChange(int slot, long filterRevision, dev.scathiard.feedmepackages.domain.CellSettings value) { this(slot, filterRevision, value, false); }
    }
    /** Settings use the filter lifetime, not the changing stock revision; the complete batch commits once. */
    public static Result settings(ServerPlayer player, UUID sessionId, List<SettingsChange> changes) {
        validateOpenContext(player); var session=SESSIONS.get(player); var access=PanelAccess.resolve(player);
        if(session==null || !session.id().equals(sessionId) || session.menu().get()!=player.containerMenu) return Result.STALE;
        if(!access.active()) return Result.NOT_ACTIVE;
        if(!access.handle().equals(session.handle()) || CraftingReservations.operating(access.handle().cacheId()) || FluidActions.operating(access.handle().cacheId())) return Result.STALE;
        if(changes.isEmpty() || changes.size()>36) return Result.INVALID_REQUEST;
        var ledger=CacheLedger.get(player.getServer()); var before=ledger.find(access.handle().cacheId()); var edit=before.state().edit(); var fluidEdit=before.fluids().state().edit();
        var seen=new HashSet<Integer>();
        try {
            for(var change:changes) {
                int slot=change.slot();
                if(slot<0 || slot>=before.state().cells().size() || !seen.add(slot)) return Result.INVALID_REQUEST;
                if (!before.state().writable(slot) || !before.fluids().state().writable(slot)) return Result.READ_ONLY;
                if (change.fluid()) {
                    var cell = before.fluids().state().cells().get(slot); var value = change.value();
                    if (cell.filter() == null || cell.filterRevision() != change.filterRevision()) return Result.STALE;
                    int unit = dev.scathiard.feedmepackages.fluid.FluidVariantKey.MILLIBUCKETS_PER_GROUP;
                    if (value.batch() != 1 || value.pendingLimit() != -1 || value.lower() >= 0 && value.lower() % unit != 0
                            || value.upper() >= 0 && value.upper() % unit != 0) return Result.INVALID_REQUEST;
                    fluidEdit.thresholds(slot, value.lower() < 0 ? -1 : value.lower() / unit, value.upper() < 0 ? -1 : value.upper() / unit);
                } else {
                    var cell=before.state().cells().get(slot);
                    if(cell.filter()==null || cell.filterRevision()!=change.filterRevision()) return Result.STALE;
                    edit.settings(slot,change.value());
                }
            }
            var items = edit.finish(); var fluids = fluidEdit.finish(); long revision = Math.max(items.revision(), fluids.revision());
            var nextItems = items.withRevision(revision);
            var nextFluids = fluids.withRevision(revision);
            ledger.replace(access.handle(),before.state().revision(),before.withStates(nextItems, before.fluids().withState(nextFluids)));
            return Result.OK;
        } catch(IllegalArgumentException invalid) { return Result.INVALID_REQUEST; }
    }

    public static Result execute(ServerPlayer player, Intent intent, int requestSeq) {
        // Creative inventory / ordinary packed access is gated HERE (the new-seq overload), so the old
        // signature delegating to it cannot bypass the restriction by omitting the sequence.
        // The gate exists because in the creative inventory screen the CLIENT owns the cursor and the vanilla
        // tab-pick packets (SetCreativeModeSlot): a server-side cursor mutation would desync it. That is a design
        // choice, not a missing facility - the ledger works exactly the same in creative.
        // COLLECT_MATCHING is deliberately NOT in this list (user asked for it to work in creative): it never
        // touches the cursor, it only moves real inventory slots (the same thing creative players already do when
        // they drop a stack into a chest), so the narrow door is safe. Every other action keeps the old rule.
        if (player.gameMode.isCreative() && player.containerMenu instanceof InventoryMenu
                && (intent.action() == Action.DEPOSIT || intent.action() == Action.TAKE_CURSOR
                        || intent.action() == Action.TAKE_RESIDUAL || intent.action() == Action.DEPOSIT_FLUID))
            return Result.INVALID_REQUEST;
        return execute(player, intent, null, requestSeq);
    }

    public static Result executeCreative(ServerPlayer player, Intent intent, String template, int count) {
        return executeCreative(player, intent, template, count, -1);
    }

    public static Result executeCreative(ServerPlayer player, Intent intent, String template, int count, int requestSeq) {
        if (!player.getAbilities().instabuild || !player.gameMode.isCreative() || !(player.containerMenu instanceof InventoryMenu)
                || !(intent.action() == Action.DEPOSIT || intent.action() == Action.TAKE_CURSOR || intent.action() == Action.TAKE_RESIDUAL || intent.action() == Action.DEPOSIT_FLUID))
            return Result.INVALID_REQUEST;
        ItemStack cursor;
        try {
            if (count == 0 && template.isEmpty()) cursor = ItemStack.EMPTY;
            else cursor = ItemVariantKey.decode(template, player.registryAccess()).stack(player.registryAccess(), count);
        } catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
        // Creative inventory owns its cursor on the client. A second server cursor would be
        // returned by vanilla on close after SetCreativeModeSlot has already placed the item.
        if (!player.containerMenu.getCarried().isEmpty()) return Result.STALE;
        return execute(player, intent, cursor, requestSeq);
    }

    private static Result execute(ServerPlayer player, Intent intent, ItemStack creativeCursor, int requestSeq) {
        var access = PanelAccess.resolve(player); var session = SESSIONS.get(player);
        if (session == null || !session.id().equals(intent.session()) || session.menu().get() != player.containerMenu) return Result.STALE;
        // A client that replaced/consumed its preview asks to release only its own panel-session hold,
        // before an independent creative-source placement could otherwise be mis-debited by creativeAfter.
        if (intent.action() == Action.RELEASE_FLUID) return FluidActions.release(player, intent.session(), intent.first());
        if (intent.action() == Action.RELEASE_PREVIEW) {
            // Intent.first carries the target takeSequence (the take being replaced); slot stays -1. The
            // release Command's own sequence is only this message's increment, never the target.
            return CursorReservations.releasePreview(player, intent.session(), intent.first());
        }
        // Retain enum ordinals as explicit rejection paths, never as hidden old UI capabilities.
        if (intent.action() == Action.TERMINAL_ENABLED || intent.action() == Action.CLEAR_NETWORK
                || intent.action() == Action.TAKE_RESIDUAL
                || intent.action() == Action.PREFERENCE) return Result.INVALID_REQUEST;
        if (!access.active()) { close(player); return Result.NOT_ACTIVE; }
        // Recheck the captured menu and pendant proofs on every mutating command.
        validateOpenContext(player);
        if (SESSIONS.get(player) != session || !access.handle().equals(session.handle())) return Result.STALE;
        if (CraftingReservations.operating(access.handle().cacheId()) || FluidActions.operating(access.handle().cacheId())) return Result.STALE;
        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        if (before.state().revision() != intent.revision()) return Result.STALE;
        if (intent.action() == Action.MAID_PICKUP) {
            var maid = dev.scathiard.feedmepackages.compat.maid.MaidMenus.target(player.containerMenu);
            if (maid == null || intent.first() < 0 || intent.first() > 1) return Result.INVALID_REQUEST;
            dev.scathiard.feedmepackages.compat.maid.MaidPickup.setEnabled(maid, intent.first() == 1);
            return Result.OK;
        }
        if (intent.action() == Action.FILL_RECIPE) return PanelAccess.maid(player) ? Result.UNSUPPORTED_RECIPE : recipe(player, intent);
        // The one-key collect is a cache-level action too: it needs no cell, so it must bypass the per-cell
        // slot validation below (exactly like the return address and the recipe fill).
        if (intent.action() == Action.COLLECT_MATCHING) return collect(player, access, before, ledger);
        // The return address is a cache-level preference, not a cell action: it uses no slot, so it
        // must bypass the per-cell slot validation below. Setting it marks the ledger dirty (saved).
        if (intent.action() == Action.SET_RETURN_ADDRESS) {
            if (intent.template().length() > 128) return Result.INVALID_REQUEST;
            if (intent.template().equals(ledger.returnAddress(access.handle().cacheId()))) return Result.OK;
            ledger.setReturnAddress(access.handle().cacheId(), intent.template());
            return Result.OK;
        }
        var carried = creativeCursor == null ? player.containerMenu.getCarried() : creativeCursor;
        // A package addressed to the maid whose page is open is handed over by unpacking it into HER cache,
        // so this must precede the per-cell slot validation (a package has no cell of its own).
        if (intent.action() == Action.DEPOSIT && PanelAccess.maid(player)
                && com.simibubi.create.content.logistics.box.PackageItem.isPackage(carried)) {
            var maid = dev.scathiard.feedmepackages.compat.maid.MaidMenus.target(player.containerMenu);
            if (maid != null && dev.scathiard.feedmepackages.compat.maid.MaidPackageHandover.addressedTo(carried, maid)) {
                // The handover reports its own reason when nothing matches (and then keeps the package).
                dev.scathiard.feedmepackages.compat.maid.MaidPackageHandover.deliver(player, maid, carried);
                return Result.OK;
            }
            return Result.INVALID_ITEM;
        }
        int slot = intent.slot();
        if (intent.action() == Action.DEPOSIT && intent.second() == AUTO_DEPOSIT) {
            if (slot < -1 || slot >= before.state().cells().size()) return Result.INVALID_REQUEST;
            ItemVariantKey key;
            try { key = ItemVariantKey.of(carried, player.registryAccess()); }
            catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
            int existing = before.state().find(key);
            java.util.function.IntPredicate empty = index -> before.state().writable(index) && before.fluids().state().writable(index)
                    && before.state().cells().get(index).filter() == null && before.fluids().state().cells().get(index).filter() == null;
            if (existing >= 0) slot = existing;
            else if (slot < 0 || !empty.test(slot)) {
                slot = -1;
                for (int index=0;index<before.state().cells().size();index++) if (empty.test(index)) {slot=index;break;}
            }
            if (slot < 0) return Result.NO_SPACE;
            intent = new Intent(intent.session(), intent.revision(), intent.action(), slot, intent.first(), -1, intent.template());
        }
        if (slot < 0 || slot >= before.state().cells().size()) return Result.INVALID_REQUEST;
        if (FluidActions.handles(before, slot, intent.action()))
            return FluidActions.execute(player, access.handle(), before, intent, carried, creativeCursor != null, requestSeq);
        // A cursor preview/return is settled by the real cursor events; it is handled before the
        // cell actions below so a held preview never re-enters a plain insert (which would add stock).
        Result cursorResult = CursorReservations.action(player, access.handle(), slot, intent, creativeCursor, requestSeq);
        if (cursorResult != null) return cursorResult;
        if (!before.state().writable(slot) && switch(intent.action()) {
            case SET_GHOST, DEPOSIT, CLEAR_FILTER, THRESHOLDS, SUPPLY_SETTINGS -> true;
            default -> false;
        }) return Result.READ_ONLY;
        var cell = before.state().cells().get(slot); var edit = before.state().edit();
        ItemStack nextCursor = null; InventoryTransfer.Plan inventoryPlan = null;
        switch (intent.action()) {
            case SET_GHOST -> {
                if (cell.amount() != 0) return Result.FILTER_OCCUPIED;
                if (!carried.isEmpty()) return Result.INVALID_REQUEST;
                ItemVariantKey variant;
                try { variant = ItemVariantKey.decode(intent.template(), player.registryAccess()); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
                if (!dev.scathiard.feedmepackages.service.CachePolicy.admits(variant,player.registryAccess())) return Result.DENIED;
                int duplicate = before.state().find(variant);
                if (duplicate >= 0 && duplicate != slot) return Result.DUPLICATE_FILTER;
                edit.filter(slot, variant);
            }

            case DEPOSIT -> {
                if (intent.first() != 0 && intent.first() != 1) return Result.INVALID_REQUEST;
                ItemVariantKey variant;
                try {
                    if (carried.isEmpty() || carried.getCount() > carried.getMaxStackSize()) return Result.INVALID_ITEM;
                    variant = ItemVariantKey.of(carried, player.registryAccess());
                } catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
                if (!dev.scathiard.feedmepackages.service.CachePolicy.admits(variant,player.registryAccess())) return Result.DENIED;
                int duplicate = before.state().find(variant);
                if (duplicate >= 0 && duplicate != slot) return Result.DUPLICATE_FILTER;
                if (cell.amount() > 0 && !variant.equals(cell.filter())) return Result.FILTER_OCCUPIED;
                edit.filter(slot, variant);
                int moved = edit.insert(slot, variant, intent.first() == 1 ? 1 : carried.getCount());
                if (moved == 0) return Result.NO_SPACE;
                nextCursor = carried.copy(); nextCursor.shrink(moved);
            }
            case TAKE_INVENTORY -> {
                // Shift-take is a real placement: it deducts immediately, leaving no pending preview.
                if (cell.filter() == null || cell.amount() == 0) return Result.NO_SPACE;
                var prototype = cell.filter().stack(player.registryAccess(), 1);
                if (intent.first() < 1) return Result.INVALID_REQUEST;
                int available = Math.min(Math.max(0, cell.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), slot, null)), intent.first());
                inventoryPlan = InventoryTransfer.insert(player.getInventory(), prototype, available);
                int moved = inventoryPlan.moved();
                if (moved == 0) return Result.NO_SPACE;
                edit.extract(slot, moved);
            }
            case CLEAR_FILTER -> {
                if (cell.amount() != 0) return Result.FILTER_OCCUPIED;
                edit.filter(slot, null);
            }
            case THRESHOLDS -> {
                try { edit.settings(slot, new dev.scathiard.feedmepackages.domain.CellSettings(intent.first(), intent.second(), cell.settings().batch(), cell.settings().pendingLimit())); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_REQUEST; }
            }
            case SUPPLY_SETTINGS -> {
                try { edit.settings(slot, new dev.scathiard.feedmepackages.domain.CellSettings(cell.settings().lower(), cell.settings().upper(), intent.first(), intent.second())); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_REQUEST; }
            }
            case RESET_REQUEST -> edit.reset(slot);
            default -> { return Result.INVALID_REQUEST; }
        }
        if (inventoryPlan != null && !inventoryPlan.stillValid(player.getInventory())) return Result.STALE;
        var replacement = before.withState(edit.finish());
        // Only original menu/inventory setters follow this commit; no external insert/drop callbacks.
        ledger.replace(access.handle(), before.state().revision(), replacement);
        if (nextCursor != null) cursor(player, nextCursor, creativeCursor != null, intent.session(), requestSeq);
        if (inventoryPlan != null) inventoryPlan.commit(player.getInventory());
        player.containerMenu.broadcastChanges(); return Result.OK;
    }

    /**
     * One-key collect (user request): move what the player carries into the cells that <b>already filter those
     * exact items</b>. Plan first, commit once — the inventory delta is simulated on copies and validated, then
     * the ledger is replaced, then the inventory is written; a changed or unreadable inventory changes nothing
     * at all.
     *
     * <p><b>Silence rule (user, 2026-09-19).</b> The player is told <b>only when something actually moved</b>,
     * and then exactly once ({@code message.create_feed_me_packages.collect}). Nothing to move, a full cell, an
     * unmatched kind, a stale revision and every other refusal are <b>silent</b> - this supersedes the earlier
     * "must always speak" wording.
     */
    private static Result collect(ServerPlayer player, AccessGate.Result access, CacheRecord before, CacheLedger ledger) {
        var inventory = player.getInventory();
        var state = before.state();
        var registries = player.registryAccess();
        List<CollectPlan.Source> sources = new ArrayList<>(InventoryTransfer.SLOTS);
        for (int slot = 0; slot < InventoryTransfer.SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemVariantKey variant = null;
            if (!stack.isEmpty()) {
                try { variant = ItemVariantKey.of(stack, registries); }
                catch (IllegalArgumentException unreadable) { variant = null; }   // the cache cannot file it
            }
            sources.add(new CollectPlan.Source(slot, variant, stack.isEmpty() ? 0 : stack.getCount()));
        }
        List<CollectPlan.Target> targets = new ArrayList<>(state.cells().size());
        for (int cell = 0; cell < state.cells().size(); cell++) {
            var held = state.cells().get(cell);
            if (held.filter() != null) targets.add(new CollectPlan.Target(cell, held.filter(), held.amount(), held.settings().upper(),
                    state.writable(cell) && dev.scathiard.feedmepackages.service.CachePolicy.admits(held.filter(),registries)));
        }
        CollectPlan.Plan plan = CollectPlan.simulate(targets, sources, state.capacity().groups());
        if (plan.isEmpty()) return Result.OK;   // nothing moved, nothing to say (silence rule)
        InventoryTransfer.Plan inventoryPlan = InventoryTransfer.take(inventory, plan.moves());
        if (!inventoryPlan.stillValid(inventory)) return Result.STALE;
        var edit = state.edit();
        for (CollectPlan.Move move : plan.moves()) {
            var filter = state.cells().get(move.cell()).filter();
            if (edit.insert(move.cell(), filter, move.amount()) != move.amount()) return Result.STALE;
        }
        // Both sides are proven now: one ledger replacement, then the original inventory setters.
        ledger.replace(access.handle(), state.revision(), before.withState(edit.finish()));
        inventoryPlan.commit(inventory);
        player.containerMenu.broadcastChanges();
        // Told once, only when something moved - and told the truth about what the return path will do.
        if (plan.shouldReport()) collectReport(player, plan, !PanelAccess.maid(player) && returnConfigured(ledger, access));
        return Result.OK;
    }

    /** True when this cache can actually send something away: a network binding plus a non-empty return address. */
    private static boolean returnConfigured(CacheLedger ledger, AccessGate.Result access) {
        String address = ledger.returnAddress(access.handle().cacheId());
        return access.handle().networkId() != null && address != null && !address.isEmpty();
    }

    /**
     * One action-bar line, and it must be true. What moved really did land in the cell, so that count is always
     * reported. Anything that landed <b>above the cell's own return line</b> only leaves later: the return path
     * runs on its own schedule (every 40 player ticks), needs a carrier and a return address, and sends the goods
     * to the player's return address - so the wording is "将按退货设置处理" (will be handled by the return
     * settings), never "已送往". With no return address the excess simply stays in the cell, so nothing about
     * sending is said at all.
     */
    private static void collectReport(ServerPlayer player, CollectPlan.Plan plan, boolean returnConfigured) {
        collectReports++;
        lastCollectReported = plan.moved();
        boolean mentionReturn = returnConfigured && plan.aboveMaximum() > 0;
        lastCollectMentionedReturn = mentionReturn;
        player.displayClientMessage(mentionReturn
                ? net.minecraft.network.chat.Component.translatable(
                        "message.create_feed_me_packages.collect_above_maximum", plan.moved(), plan.aboveMaximum())
                : net.minecraft.network.chat.Component.translatable(
                        "message.create_feed_me_packages.collect", plan.moved()), true);
    }

    /**
     * How many collect reports this process has emitted, and the number the last one carried. The silence rule
     * ("no message unless something moved, and then exactly one") and "the message matches the real intake" are
     * user-visible promises, and these counters are how the game tests observe them without a client attached.
     */
    private static volatile int collectReports;
    private static volatile int lastCollectReported;
    private static volatile boolean lastCollectMentionedReturn;

    public static int collectReports() { return collectReports; }
    public static int lastCollectReported() { return lastCollectReported; }
    public static boolean lastCollectMentionedReturn() { return lastCollectMentionedReturn; }
    public static void resetCollectReports() {
        collectReports = 0;
        lastCollectReported = 0;
        lastCollectMentionedReturn = false;
    }

    static void cursor(ServerPlayer player, ItemStack next, boolean creative, UUID session, int sequence) {        var menu = player.containerMenu;
        if (!creative) { menu.setCarried(next); return; }
        dev.scathiard.feedmepackages.network.PanelNetwork.sendCursor(player, session, 0, next, 0);
    }

    @SuppressWarnings("unchecked")
    private static Result recipe(ServerPlayer player, Intent intent) {
        if ((intent.first() != 0 && intent.first() != 1) || intent.template().length() > 256) return Result.INVALID_REQUEST;
        var id = net.minecraft.resources.ResourceLocation.tryParse(intent.template());
        var holder = id == null ? null : player.getServer().getRecipeManager().byKey(id).orElse(null);
        if (holder == null || !(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe)) return Result.UNSUPPORTED_RECIPE;
        var result = CraftingService.place(player, (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>) holder, intent.first() == 1, false, true);
        return switch (result) {
            case OK -> Result.OK; case INACTIVE -> Result.NOT_ACTIVE; case STALE -> Result.STALE; case NO_SPACE -> Result.NO_SPACE;
            case MISSING -> Result.MISSING_MATERIAL; case UNSUPPORTED -> Result.UNSUPPORTED_RECIPE; case TOO_COMPLEX -> Result.TOO_COMPLEX;
        };
    }
}
