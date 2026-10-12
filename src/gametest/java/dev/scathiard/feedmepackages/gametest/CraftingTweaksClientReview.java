package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Window-local input -> author's widgets/keys -> actual packets -> server conservation. */
final class CraftingTweaksClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final List<String> ACTIONS = List.of("rotate", "balance", "spread", "clear", "craftstack",
            "transfer", "transfer-all", "refill", "compress", "decompress");
    private static int stage, ticks, changed, round, action;
    private static CompletableFuture<Void> work;
    private static List<ItemStack> before;
    private static boolean table() { return round % 2 == 1; }
    private static String action() { return ACTIONS.get(action); }
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 500, "Crafting Tweaks client stalled at " + round + "/" + action + "/" + stage);
        switch (stage) {
            case 0 -> {
                if (round == 0) org.lwjgl.glfw.GLFW.glfwSetWindowSize(MC.getWindow().getWindow(), 960, 800);
                bind("keyTransferStack", 87); bind("keyDecompressStack", 76);
                bind("keyRotate", 82); bind("keyBalance", 66); bind("keySpread", 71); bind("keyClear", 72);
                MC.options.guiScale().set(1 + round / 2); MC.resizeDisplay();
                if (table()) server(p -> {
                    var pos = new BlockPos(0, -60, 2); p.serverLevel().setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                    p.connection.teleport(.5, -60, .5, 0, 0);
                    p.openMenu(new SimpleMenuProvider((id, inv, player) -> new CraftingMenu(id, inv,
                            ContainerLevelAccess.create(p.level(), pos)), Component.literal("Native Crafting Tweaks review")));
                });
                else MC.setScreen(new InventoryScreen(MC.player));
                next();
            }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?> screen) || !LogisticsPanel.recipeReady()
                        || TemplateClientReview.snapshot() == null || ticks - changed < 15) return false;
                require(table() == (screen.getMenu() instanceof CraftingMenu), "Wrong native menu type");
                require(MC.getWindow().getGuiScale() == 1 + round / 2, "Requested GUI scale was clamped");
                var buttons = buttons();
                // The author's InventoryMenu provider deliberately hides all tweak buttons.
                require(buttons.size() == (table() ? 3 : 0), "Unexpected native button count: " + buttons.size());
                if (table()) verifyAnchors();
                var bounds = TemplateClientReview.hostLayout().bounds();
                for (var button : buttons) {
                    require(button.getX() >= 0 && button.getY() >= 0 && button.getX() + button.getWidth() <= screen.width
                            && button.getY() + button.getHeight() <= screen.height, "Native button outside viewport");
                    require(!bounds.contains(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0),
                            "Native button overlaps cache panel");
                }
                TemplateClientReview.capture("craftingtweaks-scale" + (1 + round / 2) + (table() ? "-3x3" : "-2x2"));
                server(CraftingTweaksClientReview::prepare); next();
            }
            case 2 -> {
                if (ticks - changed < 12) return false;
                switch (action()) {
                    case "rotate" -> { if (table()) button("Rotate", false); else press(82); }
                    case "balance" -> { if (table()) button("Balance", false); else press(66); }
                    case "spread" -> { if (table()) button("Balance", true); else press(71); }
                    case "clear" -> { if (table()) button("Clear", false); else press(72); }
                    case "craftstack" -> { var xy = slot(0); TemplateClientReview.move(xy[0], xy[1]); stage = 7; changed = ticks; return false; }
                    case "transfer", "transfer-all" -> { var xy = inventory(8); TemplateClientReview.move(xy[0], xy[1]); stage = 5; changed = ticks; return false; }
                    case "refill" -> press(258);
                    case "compress", "decompress" -> { var xy = inventory(8); TemplateClientReview.move(xy[0], xy[1]); stage = 6; changed = ticks; return false; }
                }
                next();
            }
            case 5 -> {
                if (ticks - changed < 3) return false;
                boolean shift = action().equals("transfer-all");
                windowKey(87, true); if (shift) windowKey(340, true);
                try {
                    Object mapping = Class.forName("net.blay09.mods.craftingtweaks.client.ModKeyMappings").getField("keyTransferStack").get(null);
                    require((boolean)Class.forName("net.blay09.mods.kuma.api.ManagedKeyMapping").getMethod("isActiveAndDown").invoke(mapping), "Native W binding inactive");
                    var xy = inventory(8); TemplateClientReview.click(xy[0], xy[1]);
                } finally { if (shift) windowKey(340, false); windowKey(87, false); }
                stage = 3; changed = ticks;
            }
            case 6 -> {
                if (ticks - changed < 3) return false;
                press(action().equals("compress") ? 75 : 76); stage = 3; changed = ticks;
            }
            case 7 -> {
                if (ticks - changed < 3) return false;
                var xy = slot(0); TemplateClientReview.mouse(xy[0], xy[1], 1, 1); TemplateClientReview.mouse(xy[0], xy[1], 1, 0);
                stage = 3; changed = ticks;
            }
            case 3 -> {
                if (ticks - changed < 15) return false;
                server(CraftingTweaksClientReview::verify); next();
            }
            case 4 -> {
                FeedMePackages.LOGGER.info("FMP_CRAFTING_TWEAKS_CLIENT_ACTION_PASSED scale={} grid={} action={}", 1 + round / 2, table() ? 3 : 2, action());
                action++;
                if (action < ACTIONS.size()) { server(CraftingTweaksClientReview::prepare); stage = 2; changed = ticks; }
                else {
                    MC.player.closeContainer(); action = 0; round++; stage = 0; changed = ticks;
                    if (round == 6) return true;
                }
            }
        }
        return false;
    }
    private static void prepare(ServerPlayer p) {
        CursorReservations.cancel(p); CraftingReservations.cancel(p, p.containerMenu);
        p.containerMenu.setCarried(ItemStack.EMPTY); CraftingService.grid(p.containerMenu).clearContent(); p.getInventory().clearContent();
        var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); edit.extract(0, Math.toIntExact(record.state().cells().getFirst().amount()));
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        ConsumptionTests.seed(p, 0, new ItemStack(Items.OAK_PLANKS), 8); p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        @SuppressWarnings("unchecked") var recipe = (RecipeHolder<CraftingRecipe>)p.getServer().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("stick")).orElseThrow();
        if (!action().equals("refill")) {
            require(CraftingService.place(p, recipe, true, false, true) == CraftingService.Result.OK, "Cannot prepare mixed-source grid");
            if (action().equals("balance")) try (var scope = CraftingReservations.beginGridEdit(p, p.containerMenu)) {
                var grid = CraftingService.grid(p.containerMenu); var occupied = new ArrayList<Integer>();
                for (int i = 0; i < grid.getContainerSize(); i++) if (!grid.getItem(i).isEmpty()) occupied.add(i);
                grid.setItem(occupied.get(0), new ItemStack(Items.OAK_PLANKS, 3)); grid.setItem(occupied.get(1), new ItemStack(Items.OAK_PLANKS, 7));
            }
        } else {
            p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
            try { Class.forName("net.blay09.mods.craftingtweaks.api.CraftingTweaksAPI").getMethod("setLastCraftedRecipe",
                    net.minecraft.world.entity.player.Player.class, RecipeHolder.class).invoke(null, p, recipe); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        if (action().startsWith("transfer")) { p.getInventory().setItem(8, new ItemStack(Items.COBBLESTONE, 12)); p.getInventory().setItem(7, new ItemStack(Items.COBBLESTONE, 8)); }
        if (action().equals("compress")) p.getInventory().setItem(8, new ItemStack(table() ? Items.IRON_INGOT : Items.CLAY_BALL, table() ? 27 : 4));
        if (action().equals("decompress")) p.getInventory().setItem(8, new ItemStack(Items.IRON_BLOCK, 2));
        before = CraftingService.grid(p.containerMenu).getItems().stream().map(ItemStack::copy).toList();
        p.containerMenu.slotsChanged(CraftingService.grid(p.containerMenu)); p.containerMenu.broadcastFullState();
    }
    private static void verify(ServerPlayer p) {
        var grid = CraftingService.grid(p.containerMenu).getItems();
        switch (action()) {
            case "rotate" -> require(!dev.scathiard.feedmepackages.consumption.MaterialTransaction.matches(before, grid), "Rotate click did nothing");
            case "balance" -> require(grid.stream().filter(s -> !s.isEmpty()).allMatch(s -> s.getCount() == 5), "Balance click did not redistribute 3/7");
            case "spread" -> require(grid.stream().filter(s -> !s.isEmpty()).count() == (table() ? 9 : 4), "Shift-balance did not spread");
            case "clear" -> require(grid.stream().allMatch(ItemStack::isEmpty), "Clear click did nothing");
            case "craftstack" -> require(p.containerMenu.getCarried().is(Items.STICK) && p.containerMenu.getCarried().getCount() == 20, "Right-click did not craft the prepared stack");
            case "transfer", "transfer-all" -> require(count(grid, Items.COBBLESTONE) == (action().equals("transfer") ? 12 : 20), "W/Shift-W click missed native grid");
            case "refill" -> require(count(grid, Items.OAK_PLANKS) == 10 && count(p.getInventory().items, Items.OAK_PLANKS) == 0, "Native refill shortcut did not fill");
            case "compress" -> require(count(p.getInventory().items, table() ? Items.IRON_BLOCK : Items.CLAY) == (table() ? 3 : 1), "Compression shortcut failed");
            case "decompress" -> require(count(p.getInventory().items, Items.IRON_INGOT) == 18, "Decompression shortcut failed");
        }
        CraftingReservations.validate(p);
        int planks = ConsumptionTests.stock(p, 0) + count(p.getInventory().items, Items.OAK_PLANKS)
                + count(CraftingReservations.realGrid(p, p.containerMenu), Items.OAK_PLANKS);
        int sticks = count(p.getInventory().items, Items.STICK) + (p.containerMenu.getCarried().is(Items.STICK) ? p.containerMenu.getCarried().getCount() : 0);
        require(planks * 2 + sticks == (action().equals("refill") ? 36 : 20), "Native client operation duplicated/lost stock");
    }
    private static List<AbstractWidget> buttons() throws ReflectiveOperationException {
        var type = Class.forName("net.blay09.mods.craftingtweaks.client.GuiTweakButton");
        return MC.screen.children().stream().filter(type::isInstance).map(AbstractWidget.class::cast).toList();
    }
    private static void verifyAnchors() throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        String root = "net.blay09.mods.craftingtweaks.";
        Object handler = Class.forName(root + "client.CraftingTweaksClientProviderManager").getMethod("getGridGuiHandler", AbstractContainerScreen.class).invoke(null, screen);
        Object grid = ((Optional<?>)Class.forName(root + "CraftingTweaksProviderManager").getMethod("getDefaultCraftingGrid", AbstractContainerMenu.class).invoke(null, screen.getMenu())).orElseThrow();
        var expected = new ArrayList<AbstractWidget>();
        Class.forName(root + "api.GridGuiHandler").getMethod("createButtons", AbstractContainerScreen.class, Class.forName(root + "api.CraftingGrid"), Consumer.class)
                .invoke(handler, screen, grid, (Consumer<AbstractWidget>)expected::add);
        var actual = buttons(); require(expected.size() == actual.size(), "Native anchor reference count changed");
        for (int i = 0; i < actual.size(); i++) require(actual.get(i).getX() == expected.get(i).getX() && actual.get(i).getY() == expected.get(i).getY(),
                "Native button was moved twice: actual=" + actual.get(i).getX() + "," + actual.get(i).getY() + " native=" + expected.get(i).getX() + "," + expected.get(i).getY());
    }
    private static void button(String kind, boolean shift) throws ReflectiveOperationException {
        var field = Class.forName("net.blay09.mods.craftingtweaks.client.GuiTweakButton").getDeclaredField("tweak"); field.setAccessible(true);
        for (var button : buttons()) if (((Enum<?>)field.get(button)).name().equals(kind)) {
            if (shift) windowKey(340, true);
            try { TemplateClientReview.click(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0); }
            finally { if (shift) windowKey(340, false); }
            return;
        }
        throw new IllegalStateException("Missing native button " + kind);
    }
    private static void bind(String name, int key) throws ReflectiveOperationException {
        Object mapping = Class.forName("net.blay09.mods.craftingtweaks.client.ModKeyMappings").getField(name).get(null);
        // NeoForge registers ordinary KeyMappings. Use the same setter as Controls, not Kuma's
        // unsupported virtual-binding setter. These bindings belong only to this review profile.
        var field = Class.forName("net.blay09.mods.kuma.VanillaManagedKeyMapping").getDeclaredField("mapping"); field.setAccessible(true);
        ((net.minecraft.client.KeyMapping)field.get(mapping)).setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(key));
        net.minecraft.client.KeyMapping.resetMapping();
    }
    private static int[] inventory(int index) { var s = (AbstractContainerScreen<?>)MC.screen;
        return slot(s.getMenu().slots.stream().filter(x -> x.container == MC.player.getInventory() && x.getContainerSlot() == index).findFirst().orElseThrow().index); }
    private static int[] slot(int index) { var screen = (AbstractContainerScreen<?>)MC.screen; var slot = screen.getMenu().getSlot(index);
        return new int[]{screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8}; }
    private static int count(List<ItemStack> list, Item item) { return list.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void press(int key) { windowKey(key, true); windowKey(key, false); }
    /** Send only to this isolated client window; modifiers are released in the same frame. */
    private static void windowKey(int key, boolean down) {
        int vk = key == 340 ? 0x10 : key == 258 ? 0x09 : key;
        int scan = switch (key) { case 340 -> 0x2a; case 258 -> 0x0f; case 87 -> 0x11; case 75 -> 0x25;
            case 76 -> 0x26; case 82 -> 0x13; case 66 -> 0x30; case 71 -> 0x22; case 72 -> 0x23; default -> throw new IllegalArgumentException("Unmapped test key"); };
        var hwnd = new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(MC.getWindow().getWindow())));
        com.sun.jna.platform.win32.User32.INSTANCE.SendMessage(hwnd, down ? 0x100 : 0x101,
                new com.sun.jna.platform.win32.WinDef.WPARAM(vk), new com.sun.jna.platform.win32.WinDef.LPARAM(1L | ((long)scan << 16) | (down ? 0 : 0xc0000000L)));
    }
    private static void server(Consumer<ServerPlayer> operation) { work = MC.getSingleplayerServer().submit(() -> operation.accept(MC.getSingleplayerServer().getPlayerList().getPlayer(MC.player.getUUID()))); }
    private static void next() { stage++; changed = ticks; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
