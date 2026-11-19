package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

/** Read-only view, separate from a GUI window. Entire snapshots are assembled from bounded, ordered pages. */
public final class MaterialHints {
    private MaterialHints() {}
    public static void clearClientPages() { PAGES.clear(); }
    private static final StockPages<Message> PAGES = new StockPages<>();
    private static final int WIRE_LIMIT = 144 * 1024;
    public record Message(UUID generation, long serial, boolean full, boolean active, boolean cacheFirst,
                          List<String> templates, List<Integer> amounts, int menu, List<Integer> ownReservations,
                          List<Integer> reservedGrid, UUID cache, boolean craftingEnabled, int offset, boolean last) implements CustomPacketPayload {
        public Message(UUID generation, long serial, boolean full, boolean active, boolean cacheFirst,
                       List<String> templates, List<Integer> amounts, int menu, List<Integer> ownReservations,
                       List<Integer> reservedGrid, UUID cache, boolean craftingEnabled) {
            this(generation, serial, full, active, cacheFirst, templates, amounts, menu, ownReservations, reservedGrid,
                    cache, craftingEnabled, 0, true);
        }
        public Message(UUID generation, long serial, boolean full, boolean active, boolean cacheFirst,
                       List<String> templates, List<Integer> amounts, int menu, List<Integer> ownReservations,
                       List<Integer> reservedGrid, UUID cache) {
            this(generation, serial, full, active, cacheFirst, templates, amounts, menu, ownReservations, reservedGrid, cache, true);
        }
        public Message(UUID generation, long serial, boolean full, boolean active, boolean cacheFirst,
                       List<String> templates, List<Integer> amounts, int menu, List<Integer> ownReservations, List<Integer> reservedGrid) {
            this(generation, serial, full, active, cacheFirst, templates, amounts, menu, ownReservations, reservedGrid, new UUID(0, 0));
        }
        public Message {
            Objects.requireNonNull(cache);
            templates = List.copyOf(templates); amounts = List.copyOf(amounts);
            ownReservations = List.copyOf(ownReservations); reservedGrid = List.copyOf(reservedGrid);
            if (ownReservations.size() != amounts.size() || reservedGrid.size() > 9 || menu < 0
                    || ownReservations.stream().anyMatch(n -> n < 0 || n > 4096) || reservedGrid.stream().anyMatch(n -> n < 0 || n > 4096))
                throw new IllegalArgumentException("Invalid reservation hints");
            if (serial < 1 || offset < 0 || (full ? templates.size() != amounts.size() : !templates.isEmpty())
                    || amounts.stream().anyMatch(n -> n < 0 || n > 4096) || (!active && (!full || !amounts.isEmpty())))
                throw new IllegalArgumentException("Invalid material hints");
        }
        public static final Type<Message> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "material_hints"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = StreamCodec.of((b, m) -> {
            int start = b.writerIndex();
            if (m.amounts.size() > StockPages.SIZE) throw new IllegalArgumentException("Material hints require paging");
            b.writeVarInt(m.offset); b.writeBoolean(m.last); b.writeUUID(m.generation); b.writeUUID(m.cache); b.writeLong(m.serial); b.writeBoolean(m.full); b.writeBoolean(m.active); b.writeBoolean(m.cacheFirst); b.writeBoolean(m.craftingEnabled);
            b.writeVarInt(m.amounts.size());
            for (int i = 0; i < m.amounts.size(); i++) { if (m.full) PanelPackets.text(b, m.templates.get(i), 1024); b.writeVarInt(m.amounts.get(i)); }
            b.writeVarInt(m.menu); for (int held : m.ownReservations) b.writeVarInt(held);
            b.writeVarInt(m.reservedGrid.size()); for (int held : m.reservedGrid) b.writeVarInt(held);
            PanelPackets.written(b, start, WIRE_LIMIT);
        }, b -> {
            PanelPackets.bound(b, WIRE_LIMIT); int offset = b.readVarInt(); boolean last = b.readBoolean(); var generation = b.readUUID(); var cache = b.readUUID(); long serial = b.readLong();
            boolean full = b.readBoolean(), active = b.readBoolean(), first = b.readBoolean(), crafting = b.readBoolean(); int size = PanelPackets.size(b, StockPages.SIZE);
            var templates = new ArrayList<String>(); var amounts = new ArrayList<Integer>();
            for (int i = 0; i < size; i++) { if (full) templates.add(PanelPackets.text(b, 1024)); amounts.add(b.readVarInt()); }
            int menu = b.readVarInt(); var own = new ArrayList<Integer>(); for (int i = 0; i < size; i++) own.add(b.readVarInt());
            int gridSize = PanelPackets.size(b, 9); var grid = new ArrayList<Integer>(); for (int i = 0; i < gridSize; i++) grid.add(b.readVarInt());
            PanelPackets.end(b); return new Message(generation, serial, full, active, first, templates, amounts, menu, own, grid, cache, crafting, offset, last);
        });
        public List<Message> pages() {
            if (amounts.size() <= StockPages.SIZE) return List.of(this);
            return StockPages.split(amounts.size(), (from, to) -> new Message(generation, serial, full, active, cacheFirst,
                    full ? templates.subList(from, to) : List.of(), amounts.subList(from, to), menu,
                    ownReservations.subList(from, to), reservedGrid, cache, craftingEnabled, from, to == amounts.size()));
        }
        private boolean sameSnapshot(Message other) {
            return generation.equals(other.generation) && cache.equals(other.cache) && serial == other.serial
                    && full == other.full && active == other.active && cacheFirst == other.cacheFirst && menu == other.menu
                    && craftingEnabled == other.craftingEnabled && reservedGrid.equals(other.reservedGrid);
        }
        public Message receivePage() {
            return PAGES.accept(this, offset, last, amounts.size(), Message::sameSnapshot, parts -> new Message(
                    generation, serial, full, active, cacheFirst, StockPages.collect(parts, Message::templates),
                    StockPages.collect(parts, Message::amounts), menu, StockPages.collect(parts, Message::ownReservations),
                    reservedGrid, cache, craftingEnabled));
        }
        @Override public Type<Message> type() { return TYPE; }
    }
    private record Sent(CacheHandle handle, Message message) {}
    private static final Map<ServerPlayer, Sent> LAST = new WeakHashMap<>();
    private static final Set<String> NOTED = new HashSet<>();
    private static final Map<String, Boolean> NAMABLE = new LinkedHashMap<>();
    /** The client decodes templates with {@link ItemVariantKey}; anything else must not be advertised. */
    private static boolean clientCanName(String encoded, ServerPlayer player) {
        var known = NAMABLE.get(encoded); if (known != null) return known;
        boolean ok;
        try { ok = ItemVariantKey.decode(encoded, player.registryAccess()) != null; } catch (RuntimeException notAnItem) { ok = false; }
        if (NAMABLE.size() > 4096) NAMABLE.clear();
        NAMABLE.put(encoded, ok);
        return ok;
    }
    private static void once(String key, String format, Object... arguments) {
        if (NOTED.add(key)) FeedMePackages.LOGGER.info(format, arguments);
    }
    private static Consumer<Message> clientReceiver = packet -> {};
    public static void receiveOnClient(Consumer<Message> consumer) { clientReceiver = Objects.requireNonNull(consumer); }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("material-hints-6").playToClient(Message.TYPE, Message.CODEC, (packet, context) -> {
            var complete = packet.receivePage();
            if (complete != null) clientReceiver.accept(complete);
        });
    }
    public static void forget(ServerPlayer player) { LAST.remove(player); }
    public static void tick(ServerPlayer player) {
        if (player.tickCount % 4 != 0) return;
        // ★ A locked ledger is WORLD-level data state: an unworn player never reaches the gate (NOT_WORN)
        // and would see nothing at all, so the first tick of every join reports it once - and never more
        // than once per join, because the map is weak and a rejoin brings a new player instance.
        if (JOIN_CHECKED.put(player, Boolean.TRUE) == null && !CacheLedger.get(player.getServer()).problem().isEmpty()) noticeLocked(player, "join");
        else if (player.tickCount % 100 == 0 && AccessGate.resolve(player).status() == AccessGate.Status.STORAGE_LOCKED) noticeLocked(player, "worn");
        var packet = next(player); if (packet != null) for (var page : packet.pages()) PacketDistributor.sendToPlayer(player, page);
    }
    private static final Set<UUID> TOLD_LOCKED = new HashSet<>();
    private static final Map<ServerPlayer, Boolean> JOIN_CHECKED = new WeakHashMap<>();
    private static void noticeLocked(ServerPlayer player, String reason) {
        if (TOLD_LOCKED.add(player.getUUID()))
            FeedMePackages.LOGGER.warn("FMP cache ledger locked; telling {} why, in their own language ({})", player.getGameProfile().getName(), reason);
        player.displayClientMessage(lockMessage(player), true);
    }
    /** Explain preservation without guessing the fault or serializing all caches for a notice. */
    private static Component lockMessage(ServerPlayer player) {
        var schema = CacheLedger.get(player.getServer()).newerSchema();
        return schema.isPresent()
                ? Component.translatable("gui.create_feed_me_packages.status.storage_locked.detail", schema.getAsInt(), CacheLedger.SCHEMA)
                : Component.translatable("gui.create_feed_me_packages.status.storage_locked.preserved");
    }
    public static Message next(ServerPlayer player) {
        var access = AccessGate.resolve(player); boolean active = access.active() && !player.isSpectator();
        var handle = active ? access.handle() : null; var ledger = CacheLedger.get(player.getServer());
        var templates = new ArrayList<String>(); var amounts = new ArrayList<Integer>();
        int nameless = 0;
        if (active) for (var cell : ledger.find(handle.cacheId()).state().cells()) {
            // ★ Only stock the client can name may be advertised. The client decodes templates with
            // ItemVariantKey, so a cell whose template is not an item (a fluid cell - which also cannot
            // serve crafting - or an unknown id) must never carry a positive amount: it used to throw
            // inside the client's accept() and clear the whole material view, which is how "every recipe
            // is missing materials" happened. The lists stay index-aligned with the cache cells because
            // the reservation lists below are read positionally.
            var filter = cell.filter();
            boolean named = filter != null && clientCanName(filter.encoded(), player);
            if (!named && cell.amount() > 0) nameless++;
            templates.add(named ? filter.encoded() : "");
            amounts.add(named ? Math.min(4096, Math.max(0, cell.amount() - CraftingReservations.reservedCache(handle.cacheId(), amounts.size(), null))) : 0);
        }
        int menu = player.containerMenu.containerId;
        var own = active ? CraftingReservations.cacheAmounts(player, amounts.size()) : List.<Integer>of();
        var grid = active ? CraftingReservations.gridAmounts(player) : List.<Integer>of();
        if (nameless > 0) {
            // A nameless cell has no prototype to add a lease back to; leaving its own amount in place
            // would make the client build a stack for a cell it cannot name.
            var adjusted = new ArrayList<Integer>(own);
            for (int i = 0; i < templates.size(); i++) if (templates.get(i).isEmpty()) adjusted.set(i, 0);
            own = adjusted;
            once("namelessCells", "FMP material hints: {} cache cell(s) cannot be named by an item template; kept out of the material view", nameless);
        }
        boolean crafting = active && dev.scathiard.feedmepackages.consumption.CraftingService.enabled(player);
        boolean first = active && ledger.cacheFirst(player.getUUID()); var last = LAST.get(player);
        if (last != null && Objects.equals(last.handle, handle) && last.message.templates.equals(templates)
                && last.message.amounts.equals(amounts) && last.message.cacheFirst == first && last.message.craftingEnabled == crafting && last.message.menu == menu
                && last.message.ownReservations.equals(own) && last.message.reservedGrid.equals(grid)) return null;
        boolean full = last == null || !Objects.equals(last.handle, handle) || !last.message.templates.equals(templates) || !active;
        UUID generation = full ? UUID.randomUUID() : last.message.generation;
        long serial = last == null ? 1 : Math.incrementExact(last.message.serial);
        var cache = handle == null ? new UUID(0, 0) : handle.cacheId();
        var stored = new Message(generation, serial, true, active, first, templates, amounts, menu, own, grid, cache, crafting); LAST.put(player, new Sent(handle, stored));
        return full ? stored : new Message(generation, serial, false, active, first, List.of(), amounts, menu, own, grid, cache, crafting);
    }
}
