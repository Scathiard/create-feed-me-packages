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
 * Screenshot-only client review (mode {@code ui}, user 2026-10-13): the controls page and the logistics panel in
 * both languages, the pendant hover tooltip, and the pendant icon as the GUI really draws it. It asserts nothing
 * about behaviour - it drives the real vanilla / FMP screens through the real client, so every picture shows the
 * shipped code and not a mock.
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

    private static void next() { phase++; changed = ticks; }
}
