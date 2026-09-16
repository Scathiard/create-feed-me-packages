package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.MaterialHints;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;

/** Animation/recipe hints only. These stacks never enter a player's real inventory. */
public final class ClientMaterials {
    private ClientMaterials() {}
    private static UUID generation;
    private static long serial;
    private static boolean active, first;
    private static final Set<String> NOTED = new HashSet<>();
    private static List<ItemStack> prototypes = List.of();
    private static List<Integer> counts = List.of();
    private static List<Integer> ownReservations = List.of(), reservedGrid = List.of();
    private static int menu;
    public static void register() {
        MaterialHints.receiveOnClient(ClientMaterials::accept); AmmoService.clientQuery(ClientMaterials::ammo);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }
    public static void clear() { generation = null; serial = 0; active = false; first = false; prototypes = List.of(); counts = List.of(); ownReservations = List.of(); reservedGrid = List.of(); }
    public static boolean active() { return active && Minecraft.getInstance().player != null && !Minecraft.getInstance().player.isSpectator(); }
    public static boolean cacheFirst() { return false; }
    public static long version() { return serial; }
    public static List<ItemStack> stacks() {
        return stacks(false);
    }
    public static List<ItemStack> craftingStacks() { return stacks(true); }
    private static List<ItemStack> stacks(boolean preparing) {
        if (!active()) return List.of();
        var result = new ArrayList<ItemStack>();
        for (int i = 0; i < counts.size(); i++) {
            int count = counts.get(i) + (preparing ? ownReservations.get(i) : 0);
            if (count > 0) result.add(prototypes.get(i).copyWithCount(count));
        }
        return result; // Counts are hints, not legal transferable stacks.
    }
    public static List<ItemStack> realGrid(Player player, net.minecraft.world.inventory.CraftingContainer grid) {
        var result = dev.scathiard.feedmepackages.consumption.MaterialTransaction.copies(grid.getItems());
        if (player.containerMenu.containerId == menu && result.size() == reservedGrid.size())
            for (int i = 0; i < result.size(); i++) result.get(i).shrink(Math.min(result.get(i).getCount(), reservedGrid.get(i)));
        return result;
    }
    private static void accept(MaterialHints.Message packet) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (packet.generation().equals(generation) && packet.serial() <= serial) { note("duplicate"); return; }
        // An increment for a generation this client never saw leaves the material view empty; say so once.
        if (!packet.full() && !packet.generation().equals(generation)) { note("unknownGeneration"); return; }
        try {
            if (packet.full()) {
                var decoded = new ArrayList<ItemStack>();
                for (var template : packet.templates()) decoded.add(template.isEmpty() ? ItemStack.EMPTY : decodeTemplate(template, player));
                prototypes = decoded; generation = packet.generation();
            }
            if (prototypes.size() != packet.amounts().size()) throw new IllegalArgumentException("Mismatched material delta");
            // A cell this client cannot name must not wipe the whole view: a fluid cell (0.3), an unknown
            // item id or a template with data this build does not know used to throw here and clear()
            // everything, which left JEI with no cache view at all and a silent fallback to the vanilla
            // transfer ("any recipe is missing materials"). Skip the entry, keep the rest, say it once.
            var nextCounts = new ArrayList<Integer>(packet.amounts());
            var nextOwn = new ArrayList<Integer>(packet.ownReservations());
            int nameless = 0;
            for (int i = 0; i < prototypes.size(); i++) {
                if (!prototypes.get(i).isEmpty() || (nextCounts.get(i) == 0 && nextOwn.get(i) == 0)) continue;
                if (nextCounts.get(i) > 0) nameless++;
                nextCounts.set(i, 0); nextOwn.set(i, 0);
            }
            if (nameless > 0) skip("namelessCellWithStock", nameless + " hinted cell(s) carry stock but no name this client can decode; they stay out of the material view");
            counts = nextCounts; first = false; active = packet.active(); serial = packet.serial();
            ownReservations = nextOwn; reservedGrid = packet.reservedGrid(); menu = packet.menu();
        } catch (IllegalArgumentException invalid) { clear(); FeedMePackages.LOGGER.warn("Rejected invalid material hints: {}", invalid.getMessage()); }
    }
    /** One undecodable template must not cost the player the whole cache view. */
    private static ItemStack decodeTemplate(String template, Player player) {
        try { return ItemVariantKey.decode(template, player.registryAccess()).stack(player.registryAccess(), 1); }
        catch (RuntimeException undecodable) {
            skip("undecodableTemplate", "a hinted template is not an item this build can name: " + undecodable.getMessage());
            return ItemStack.EMPTY;
        }
    }
    /** One line per reason per session: a diagnostic the next report can read instead of guessing. */
    private static void skip(String reason, String detail) {
        if (!NOTED.add(reason)) return;
        FeedMePackages.LOGGER.info("FMP material hints: skipped entries ({}) - {}", reason, detail);
    }
    /**
     * One line per reason per session. The dangerous branch (an increment for an unknown generation) used
     * to drop the packet in silence, which left "the cache is invisible to JEI" without any trace.
     */
    private static void note(String reason) {
        if (!NOTED.add(reason)) return;
        FeedMePackages.LOGGER.info("FMP material hints: dropped a packet ({}) - the client's material view stays {}", reason, active() ? "active" : "inactive");
    }
    private static ItemStack ammo(Player player, ItemStack weapon, ItemStack vanilla) {
        if (!active() || (!first && !vanilla.isEmpty() && !player.hasInfiniteMaterials())) return vanilla;
        if (!first && !vanilla.isEmpty() && player.getInventory().contains(vanilla)) return vanilla;
        var predicate = ((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles(weapon);
        for (var stack : stacks()) if (predicate.test(stack)) return stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
        return vanilla;
    }
}
