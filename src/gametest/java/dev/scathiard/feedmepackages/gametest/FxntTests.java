package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntTransferContext;
import dev.scathiard.feedmepackages.compat.fxntstorage.FxntMaterials;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.*;
import java.util.List;

/** Uses the actual optional packet methods, not a copy of their transfer loop. */
@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FxntTests {
    @GameTest(template = "empty")
    public static void nativeMaximumRespectsEachBrowserSwitchAndCapturedOrigin(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        boolean jei = FmpConfig.jeiCraftingEnabled(), emi = FmpConfig.emiCraftingEnabled();
        try {
            for (boolean alias : new boolean[]{false, true}) for (int flags = 0; flags < 4; flags++) for (var viewer : dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.values()) {
                FmpConfig.JEI.set((flags & 1) != 0); FmpConfig.EMI.set((flags & 2) != 0); FmpConfig.refreshServer();
                var player = setup(helper, new ItemStack(Items.OAK_LOG), 8); player.getInventory().setItem(8, new ItemStack(Items.OAK_LOG));
                if (alias) {
                    @SuppressWarnings("unchecked") var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)player.level().getRecipeManager()
                            .byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
                    helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingService.place(player, recipe, false, false, true)
                            == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK, "Alias fixture failed");
                }
                var origin = viewer == dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN ? null
                        : new dev.scathiard.feedmepackages.network.TransferOrigin.Message(10 + flags, player.containerMenu.containerId, viewer, true);
                if (origin != null) dev.scathiard.feedmepackages.network.TransferOrigin.accept(player, origin);
                var queue = new java.util.ArrayList<Runnable>();
                var context = (net.neoforged.neoforge.network.handling.IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(FxntTests.class.getClassLoader(),
                        new Class<?>[]{net.neoforged.neoforge.network.handling.IPayloadContext.class}, (proxy, method, args) -> {
                            if (method.getName().equals("player")) return player;
                            if (method.getName().equals("enqueueWork")) { queue.add((Runnable)args[0]); return new java.util.concurrent.CompletableFuture<Void>(); }
                            throw new UnsupportedOperationException(method.getName());
                        });
                var packet = Class.forName("net.fxnt.fxntstorage.network.packet.TransferRecipePacket")
                        .getConstructor(ResourceLocation.class, List.class, boolean.class, int.class)
                        .newInstance(ResourceLocation.withDefaultNamespace("oak_planks"), List.of(1), true, 0);
                packet.getClass().getMethod("handle", net.neoforged.neoforge.network.handling.IPayloadContext.class).invoke(packet, context);
                // END arrives before the queued native work executes.
                if (origin != null) dev.scathiard.feedmepackages.network.TransferOrigin.accept(player,
                        new dev.scathiard.feedmepackages.network.TransferOrigin.Message(origin.nonce(), origin.menu(), viewer, false));
                helper.assertTrue(queue.size() == 1 && FxntTransferContext.current() == null, "Original enqueue count/context changed");
                queue.getFirst().run();
                boolean enabled = viewer.enabled();
                int grid = dev.scathiard.feedmepackages.consumption.CraftingService.grid(player.containerMenu).getItems().stream()
                        .filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(stock(player) == (enabled ? 0 : 8) && grid == (enabled ? 9 : 1)
                        && player.getInventory().getItem(8).isEmpty() && FxntTransferContext.current() == null,
                        "Native maximum ignored origin/config: " + viewer + " flags=" + flags + " alias=" + alias + " grid=" + grid + " cache=" + stock(player));
                helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.claim(player)
                        == dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN, "Consumed origin leaked to another packet");
            }
        } finally { FmpConfig.JEI.set(jei); FmpConfig.EMI.set(emi); FmpConfig.refreshServer(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void originWireAndOneShotScopeRejectStaleOrInvalidClaims(GameTestHelper helper) {
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8);
        var viewer = dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.EMI;
        var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var message = new dev.scathiard.feedmepackages.network.TransferOrigin.Message(1, player.containerMenu.containerId, viewer, true);
            dev.scathiard.feedmepackages.network.TransferOrigin.Message.CODEC.encode(wire, message);
            helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.Message.CODEC.decode(wire).equals(message), "Origin wire changed");
            for (int malformed = 0; malformed < 6; malformed++) {
                wire.clear();
                if (malformed != 5) {
                    wire.writeLong(malformed == 0 ? 0 : 1); wire.writeInt(malformed == 1 ? -1 : 0);
                    wire.writeByte(malformed == 2 ? 255 : 2); wire.writeByte(malformed == 3 ? 2 : 1);
                    if (malformed == 4) wire.writeByte(0);
                }
                boolean rejected = false;
                try { dev.scathiard.feedmepackages.network.TransferOrigin.Message.CODEC.decode(wire); }
                catch (IllegalArgumentException | IndexOutOfBoundsException | io.netty.handler.codec.DecoderException expected) { rejected = true; }
                helper.assertTrue(rejected, "Invalid origin decoded: " + malformed);
            }
            dev.scathiard.feedmepackages.network.TransferOrigin.accept(player, message);
            dev.scathiard.feedmepackages.network.TransferOrigin.accept(player, new dev.scathiard.feedmepackages.network.TransferOrigin.Message(2, message.menu(), viewer, false));
            helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.claim(player) == viewer, "Unrelated END cleared origin");
            helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.claim(player) == dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN, "Origin reused");
            dev.scathiard.feedmepackages.network.TransferOrigin.accept(player, message);
            var previousMenu = player.containerMenu; player.containerMenu = new net.minecraft.world.inventory.InventoryMenu(player.getInventory(), true, player);
            helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.claim(player) == dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN, "Same-id replacement menu inherited origin");
            player.containerMenu = previousMenu;
            dev.scathiard.feedmepackages.network.TransferOrigin.accept(player, message);
            dev.scathiard.feedmepackages.network.TransferOrigin.forget(player);
            helper.assertTrue(dev.scathiard.feedmepackages.network.TransferOrigin.claim(player) == dev.scathiard.feedmepackages.network.TransferOrigin.Viewer.UNKNOWN, "Logout retained origin");
        } finally { wire.release(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void availabilityTruthTableAndActualRuntimeAgree(GameTestHelper helper) {
        var states = new CompatibilityStatus.State[]{CompatibilityStatus.State.ABSENT, CompatibilityStatus.State.ABSENT,
                CompatibilityStatus.State.ABSENT, CompatibilityStatus.State.ABSENT, CompatibilityStatus.State.CONFIG_DISABLED,
                CompatibilityStatus.State.CONFIG_DISABLED, CompatibilityStatus.State.API_MISMATCH, CompatibilityStatus.State.READY};
        for (int bits = 0; bits < states.length; bits++)
            helper.assertTrue(CompatibilityStatus.classify((bits & 4) != 0, (bits & 2) != 0, (bits & 1) != 0) == states[bits],
                    "Availability decision differs for bits " + bits);
        var expected = ModList.get().isLoaded("fxntstorage") ? CompatibilityStatus.State.READY : CompatibilityStatus.State.ABSENT;
        helper.assertTrue(CompatibilityStatus.fxnt() == expected, "Mixin availability did not reach the runtime: " + CompatibilityStatus.fxnt());
        var tacz = ModList.get().isLoaded("tacz") ? CompatibilityStatus.State.READY : CompatibilityStatus.State.ABSENT;
        helper.assertTrue(CompatibilityStatus.tacz() == tacz, "TaCZ availability differs from installed native ports: " + CompatibilityStatus.tacz());
        var wand = ModList.get().isLoaded("constructionwand") ? CompatibilityStatus.State.READY : CompatibilityStatus.State.ABSENT;
        helper.assertTrue(CompatibilityStatus.wand() == wand, "Wand API registration state differs: " + CompatibilityStatus.wand());
        var gadgets = ModList.get().isLoaded("buildinggadgets2") ? CompatibilityStatus.State.READY : CompatibilityStatus.State.ABSENT;
        helper.assertTrue(CompatibilityStatus.gadgets() == gadgets, "Gadgets complete adapter state differs: " + CompatibilityStatus.gadgets());
        var effortless = EffortlessTests.mismatchFixture() ? CompatibilityStatus.State.API_MISMATCH
                : ModList.get().isLoaded("effortlessbuilding") ? CompatibilityStatus.State.READY : CompatibilityStatus.State.ABSENT;
        helper.assertTrue(CompatibilityStatus.effortless() == effortless, "Effortless complete adapter state differs: " + CompatibilityStatus.effortless());
        FeedMePackages.LOGGER.info("FMP_FXNT_AVAILABILITY side=server state={}", CompatibilityStatus.fxnt()); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void availabilityPayloadRejectsUnknownOrTrailingData(GameTestHelper helper) {
        var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            for (var state : CompatibilityStatus.State.values()) for (var emi : CompatibilityStatus.State.values())
                    for (var tacz : CompatibilityStatus.State.values()) for (var wand : CompatibilityStatus.State.values()) for (var gadgets : CompatibilityStatus.State.values()) for (var effortless : CompatibilityStatus.State.values()) for (var sophisticated : CompatibilityStatus.State.values()) {
                wire.clear(); var message = new CompatibilityStatus.Message(tacz, wand, gadgets, effortless, state, emi, sophisticated, state, sophisticated); CompatibilityStatus.Message.CODEC.encode(wire, message);
                helper.assertTrue(CompatibilityStatus.Message.CODEC.decode(wire).equals(message), "Availability payload changed state");
            }
            for (int malformed = 0; malformed < 19; malformed++) {
                wire.clear();
                if (malformed < 9) { wire.writeZero(malformed); wire.writeByte(255); wire.writeZero(8 - malformed); }
                else wire.writeZero(malformed == 18 ? 10 : malformed - 9);
                boolean rejected = false;
                try { CompatibilityStatus.Message.CODEC.decode(wire); }
                catch (IllegalArgumentException | IndexOutOfBoundsException | io.netty.handler.codec.DecoderException expected) { rejected = true; }
                helper.assertTrue(rejected, "Malformed availability payload accepted: " + malformed);
            }
        } finally { wire.release(); }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void disabledAdapterPreservesNativeInventoryAndCannotSpendCache(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 8); var ingredient = Ingredient.of(Items.STONE);
        boolean previous = FmpConfig.FXNT.get();
        try {
            FmpConfig.FXNT.set(false); FmpConfig.refreshServer();
            helper.assertTrue(CompatibilityStatus.fxnt() == CompatibilityStatus.State.CONFIG_DISABLED, "Disabled port still advertised ready");
            helper.assertTrue(FxntMaterials.countView(player.getInventory(), List.of(ingredient)) == player.getInventory(), "Disabled count replaced native inventory");
            helper.assertTrue(!place(player, ingredient, 1) && stock(player) == 8 && player.containerMenu.getSlot(1).getItem().isEmpty(), "Disabled packet spent cached items");
            player.getInventory().setItem(0, new ItemStack(Items.STONE, 2));
            helper.assertTrue(place(player, ingredient, 2) && stock(player) == 8 && player.getInventory().getItem(0).isEmpty()
                    && player.containerMenu.getSlot(1).getItem().getCount() == 2, "Disabled port blocked native inventory transfer");
        } finally { FmpConfig.FXNT.set(previous); FmpConfig.refreshServer(); }
        helper.succeed();
    }
    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("fxntstorage")) return true;
        FeedMePackages.LOGGER.info("FMP_FXNT_TEST skipped: optional 1.3.4 artifact absent"); helper.succeed(); return false;
    }
    private static ServerPlayer setup(GameTestHelper helper, ItemStack prototype, int count) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
        var edit = before.state().edit(); var key = ItemVariantKey.of(prototype, player.registryAccess());
        edit.filter(0, key); if (edit.insert(0, key, count) != count) throw new IllegalStateException("Fixture overflow");
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish())); return player;
    }
    private static long stock(ServerPlayer player) {
        var handle = AccessGate.resolve(player).handle(); return CacheLedger.get(player.getServer()).find(handle.cacheId()).state().cells().get(0).amount();
    }
    private static Object packet() throws ReflectiveOperationException {
        return Class.forName("net.fxnt.fxntstorage.network.packet.TransferRecipePacket")
                .getDeclaredConstructor(ResourceLocation.class, List.class, boolean.class, int.class)
                .newInstance(ResourceLocation.withDefaultNamespace("oak_planks"), List.of(1), false, 0);
    }
    private static boolean place(ServerPlayer player, Ingredient ingredient, int amount) throws ReflectiveOperationException {
        return place(player, ingredient, amount, null);
    }
    private static boolean place(ServerPlayer player, Ingredient ingredient, int amount, IItemHandlerModifiable backpack) throws ReflectiveOperationException {
        var packet = packet(); var method = packet.getClass().getDeclaredMethod("collectAndPlace", Ingredient.class, int.class, int.class,
                Inventory.class, IItemHandlerModifiable.class, List.class); method.setAccessible(true);
        return (Boolean) method.invoke(packet, ingredient, 0, amount, player.getInventory(), backpack, List.of(player.containerMenu.getSlot(1)));
    }
    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void realPacketDoesNotMaterializeOldRecipeBookAliases(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8);
        var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                player.level().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingService.place(player, recipe, false, false, true)
                == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK && stock(player) == 8, "Recipe-book alias fixture failed");
        var context = (net.neoforged.neoforge.network.handling.IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(FxntTests.class.getClassLoader(),
                new Class<?>[]{net.neoforged.neoforge.network.handling.IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) return player;
                    throw new UnsupportedOperationException("Unexpected payload context request: " + method.getName());
                });
        var packet = packet(); var method = packet.getClass().getDeclaredMethod("lambda$handle$1", net.neoforged.neoforge.network.handling.IPayloadContext.class);
        method.setAccessible(true); method.invoke(packet, context);
        helper.assertTrue(stock(player) == 7 && player.getInventory().items.stream().noneMatch(s -> s.is(Items.OAK_LOG))
                && dev.scathiard.feedmepackages.consumption.CraftingService.grid(player.containerMenu).getItems().stream()
                    .filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1,
                "Foreign packet cleared an unbacked alias into real inventory");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void realCountIsPureAndTransferDebitsOnlyShortage(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 8);
        var existing = new ItemStack(Items.STONE, 3); player.getInventory().setItem(0, existing);
        var untouched = new ItemStack(Items.BUNDLE); player.getInventory().setItem(1, untouched);
        var packet = packet(); var count = packet.getClass().getDeclaredMethod("getMaxCraftableItems", List.class, Inventory.class, IItemHandler.class);
        count.setAccessible(true);
        helper.assertTrue((Integer) count.invoke(packet, List.of(Ingredient.of(Items.STONE)), player.getInventory(), null) == 11
                && stock(player) == 8 && player.getInventory().getItem(0) == existing, "Native count mutated or missed the source view");
        helper.assertTrue(place(player, Ingredient.of(Items.STONE), 6) && stock(player) == 5
                && player.containerMenu.getSlot(1).getItem().getCount() == 6 && player.getInventory().getItem(0).isEmpty(), "Native transfer did not spend the exact shortfall");
        helper.assertTrue(player.getInventory().getItem(1) == untouched && FxntTransferContext.current() == null, "Unrelated stack identity/context changed");
        FeedMePackages.LOGGER.info("FMP_FXNT_TEST actual methods: count=11, real inventory=3, cache debit=3"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void fullInventoryDoesNotPreventSourceAccess(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 8);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        helper.assertTrue(place(player, Ingredient.of(Items.STONE), 2) && stock(player) == 6
                && player.containerMenu.getSlot(1).getItem().getCount() == 2, "Virtual sources required a real empty inventory slot");
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) helper.assertTrue(player.getInventory().getItem(slot).getCount() == 64, "Full real inventory changed");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void insufficientCacheIsNotPartiallyDebited(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2);
        helper.assertTrue(!place(player, Ingredient.of(Items.STONE), 3) && stock(player) == 2
                && player.containerMenu.getSlot(1).getItem().isEmpty() && FxntTransferContext.current() == null, "Failed native transfer spent cached items");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void cursorReserveIsExcludedExactlyOnce(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 8);
        var opened = CacheActions.open(player);
        helper.assertTrue(CacheActions.execute(player, new CacheActions.Intent(opened.session(), opened.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 0, 5, -1, "")) == CacheActions.Result.OK, "Cursor fixture failed");
        helper.assertTrue(!place(player, Ingredient.of(Items.STONE), 4) && stock(player) == 8, "Transfer spent a reserved cursor item");
        helper.assertTrue(place(player, Ingredient.of(Items.STONE), 3) && stock(player) == 5
                && player.containerMenu.getCarried().getCount() == 5, "Shared reservation gate was applied twice");
        CursorReservations.cancel(player); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void componentIdentityAndNativeLimitsRemainIntact(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var named = new ItemStack(Items.EGG); named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact egg"));
        var player = setup(helper, named, 32);
        var view = FxntMaterials.countView(player.getInventory(), List.of(Ingredient.of(Items.EGG)));
        helper.assertTrue(view.getItem(view.getContainerSize() - 1).getCount() == 16, "Preview exposed an overstack");
        helper.assertTrue(place(player, Ingredient.of(Items.EGG), 16) && stock(player) == 16
                && ItemStack.isSameItemSameComponents(player.containerMenu.getSlot(1).getItem(), named), "Exact components were lost");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void stalePreparedSourceCannotPublishGhostItems(GameTestHelper helper) {
        var player = setup(helper, new ItemStack(Items.STONE), 8); var slot = player.containerMenu.getSlot(1);
        var transfer = FxntMaterials.prepare(Ingredient.of(Items.STONE), 0, 2, player.getInventory(), List.of(slot));
        var view = transfer.inventory(); var stack = view.getItem(view.getContainerSize() - 1).split(2);
        TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        transfer.place(slot, stack, slot::setByPlayer);
        helper.assertTrue(!transfer.placed() && slot.getItem().isEmpty(), "Stale material view created physical items"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeFailureBeforePublicationRestoresSource(GameTestHelper helper) {
        var player = setup(helper, new ItemStack(Items.STONE), 8); var slot = player.containerMenu.getSlot(1);
        var physical = new ItemStack(Items.STONE, 2); player.getInventory().setItem(0, physical);
        var transfer = FxntMaterials.prepare(Ingredient.of(Items.STONE), 0, 3, player.getInventory(), List.of(slot));
        transfer.inventory().getItem(0).split(2);
        transfer.inventory().getItem(transfer.inventory().getContainerSize() - 1).split(1);
        try { transfer.place(slot, new ItemStack(Items.STONE, 3), stack -> { throw new IllegalStateException("expected native failure"); }); }
        catch (IllegalStateException expected) { helper.assertTrue(expected.getMessage().equals("expected native failure"), "Unexpected failure"); }
        helper.assertTrue(stock(player) == 8 && player.getInventory().getItem(0).getCount() == 2 && slot.getItem().isEmpty(), "A rejected destination lost items"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeFailureAfterPublicationDoesNotDuplicateRefund(GameTestHelper helper) {
        var player = setup(helper, new ItemStack(Items.STONE), 8); var slot = player.containerMenu.getSlot(1);
        var transfer = FxntMaterials.prepare(Ingredient.of(Items.STONE), 0, 2, player.getInventory(), List.of(slot));
        var view = transfer.inventory(); var stack = view.getItem(view.getContainerSize() - 1).split(2);
        try { transfer.place(slot, stack, value -> { slot.setByPlayer(value); throw new IllegalStateException("expected post-placement failure"); }); }
        catch (IllegalStateException expected) { helper.assertTrue(expected.getMessage().equals("expected post-placement failure"), "Unexpected failure"); }
        helper.assertTrue(transfer.placed() && stock(player) == 6 && slot.getItem().getCount() == 2, "Published inputs were refunded twice"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void threeSourcesUseTheOriginalHandlerAndPreserveUnrelatedIdentity(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2);
        player.getInventory().setItem(0, new ItemStack(Items.STONE));
        var unrelated = new ItemStack(Items.BUNDLE); player.getInventory().setItem(1, unrelated);
        var nativeWrites = new int[]{0};
        var backpack = new ItemStackHandler(200) {
            @Override protected void onContentsChanged(int slot) {
                // Model the worn item's own callback publishing new contents, without replacing it.
                unrelated.set(DataComponents.CUSTOM_NAME, Component.literal("Native backpack updated")); nativeWrites[0]++;
            }
        };
        backpack.setStackInSlot(4, new ItemStack(Items.STONE, 3)); nativeWrites[0] = 0;
        unrelated.remove(DataComponents.CUSTOM_NAME);
        helper.assertTrue(place(player, Ingredient.of(Items.STONE), 6, backpack) && stock(player) == 0
                && player.getInventory().getItem(0).isEmpty() && backpack.getStackInSlot(4).isEmpty()
                && player.containerMenu.getSlot(1).getItem().getCount() == 6, "Three-way transfer lost or duplicated a source");
        helper.assertTrue(nativeWrites[0] == 1 && player.getInventory().getItem(1) == unrelated
                && unrelated.has(DataComponents.CUSTOM_NAME) && FxntTransferContext.current() == null, "Foreign instance or native callback was replaced");
        FeedMePackages.LOGGER.info("FMP_FXNT_TEST three-way: inventory=1, cache=2, native backpack=3, grid=6"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void failedDraftDoesNotRefundUnpublishedForeignCopies(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2);
        player.getInventory().setItem(0, new ItemStack(Items.STONE));
        var backpack = new ItemStackHandler(200); backpack.setStackInSlot(0, new ItemStack(Items.STONE, 2));
        // Model fxnt's unpublished container copy; only its packet's final callback saves the item.
        var backing = net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 2)));
        try (var packetScope = new FxntTransferContext(List.of(), null, true)) {
            helper.assertTrue(!place(player, Ingredient.of(Items.STONE), 6, backpack), "Insufficient native transfer succeeded");
        }
        helper.assertTrue(stock(player) == 2 && player.getInventory().getItem(0).getCount() == 1
                && backing.getStackInSlot(0).getCount() == 2 && backpack.getStackInSlot(0).isEmpty()
                && player.containerMenu.getSlot(1).getItem().isEmpty(), "Unpublished foreign copy was refunded on top of unchanged backing items"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeBackpackFailureDoesNotInventPublicationOrSpendOwnSources(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2); player.getInventory().setItem(0, new ItemStack(Items.STONE));
        var armed = new boolean[]{false};
        var backpack = new ItemStackHandler(200) {
            @Override protected void onContentsChanged(int slot) { if (armed[0]) throw new IllegalStateException("expected backpack failure"); }
        };
        backpack.setStackInSlot(0, new ItemStack(Items.STONE, 3)); armed[0] = true;
        boolean failed = false;
        try { place(player, Ingredient.of(Items.STONE), 6, backpack); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            failed = "expected backpack failure".equals(expected.getCause().getMessage());
        }
        helper.assertTrue(failed && stock(player) == 2 && player.getInventory().getItem(0).getCount() == 1
                && backpack.getStackInSlot(0).isEmpty() && player.containerMenu.getSlot(1).getItem().isEmpty()
                && FxntTransferContext.current() == null, "Exception spent staged sources or guessed that a foreign draft had been persisted"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void staleAccessAfterNativeDebitDoesNotPublishCacheAliases(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2); player.getInventory().setItem(0, new ItemStack(Items.STONE));
        var handle = AccessGate.resolve(player).handle(); var armed = new boolean[]{false};
        var backpack = new ItemStackHandler(200) {
            @Override protected void onContentsChanged(int slot) { if (armed[0]) TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY); }
        };
        backpack.setStackInSlot(0, new ItemStack(Items.STONE, 3)); armed[0] = true;
        // This phase test explicitly supplies the publication observation; the client suite exercises its real hook.
        try (var packetScope = new FxntTransferContext(List.of(), null, true)) {
            place(player, Ingredient.of(Items.STONE), 6, backpack);
            helper.assertTrue(player.getInventory().getItem(0).getCount() == 1, "Refund happened before native publication");
            FxntTransferContext.nativePublished();
        }
        helper.assertTrue(CacheLedger.get(player.getServer()).find(handle.cacheId()).state().cells().get(0).amount() == 2
                && player.getInventory().getItem(0).getCount() == 4 && backpack.getStackInSlot(0).isEmpty()
                && player.containerMenu.getSlot(1).getItem().isEmpty(), "Stale access either minted cache items or lost native goods"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void copiedReadWithRejectedNativeWriteCannotMintItems(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2); player.getInventory().setItem(0, new ItemStack(Items.STONE));
        var armed = new boolean[]{false};
        var backpack = new ItemStackHandler(200) {
            @Override public ItemStack getStackInSlot(int slot) { return super.getStackInSlot(slot).copy(); }
            @Override public void setStackInSlot(int slot, ItemStack stack) { if (!armed[0]) super.setStackInSlot(slot, stack); }
        };
        backpack.setStackInSlot(0, new ItemStack(Items.STONE, 3)); armed[0] = true;
        place(player, Ingredient.of(Items.STONE), 6, backpack);
        helper.assertTrue(stock(player) == 2 && player.getInventory().getItem(0).getCount() == 1
                && backpack.getStackInSlot(0).getCount() == 3 && player.containerMenu.getSlot(1).getItem().isEmpty(), "A rejected native debit was treated as physical supply"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void mixedComponentNativeItemsAreNotTransformedByCacheSupply(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 2);
        var named = new ItemStack(Items.STONE, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my components"));
        var backpack = new ItemStackHandler(200); backpack.setStackInSlot(0, named.copy());
        try (var packetScope = new FxntTransferContext(List.of(), null, true)) {
            place(player, Ingredient.of(Items.STONE), 5, backpack);
            FxntTransferContext.nativePublished();
        }
        helper.assertTrue(stock(player) == 2 && player.getInventory().getItem(0).getCount() == 3
                && ItemStack.isSameItemSameComponents(player.getInventory().getItem(0), named)
                && player.containerMenu.getSlot(1).getItem().isEmpty(), "Foreign components were flattened into a cached variant"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void repeatedRecipeInputsSeeAllAvailableLegalStacks(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.STONE), 128);
        var ingredient = Ingredient.of(Items.STONE); var inputs = java.util.Collections.nCopies(8, ingredient);
        var view = FxntMaterials.countView(player.getInventory(), inputs);
        var clientView = FxntMaterials.preview(player.getInventory(), List.of(new ItemStack(Items.STONE, 128)), inputs);
        for (var source : List.of(view, clientView)) {
            int total = 0;
            for (int i = player.getInventory().getContainerSize(); i < source.getContainerSize(); i++) {
                var stack = source.getItem(i); total += stack.getCount();
                helper.assertTrue(stack.getCount() <= stack.getMaxStackSize(), "Repeated inputs exposed an illegal physical overstack");
            }
            helper.assertTrue(total == 128, "Recipe-wide count was truncated to one stack");
        }
        var packet = packet(); var count = packet.getClass().getDeclaredMethod("getMaxCraftableItems", List.class, Inventory.class, IItemHandler.class); count.setAccessible(true);
        helper.assertTrue((Integer) count.invoke(packet, inputs, player.getInventory(), null) == 16 && stock(player) == 128,
                "Native repeated-input maximum was still capped at one source stack"); helper.succeed();
    }
}
