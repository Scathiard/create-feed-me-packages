package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Uses author preview packets and vanilla client interaction networking in an isolated fresh world. */
final class WandClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final String ROOT = "dev.polaris_light.constructionwand.";
    private static final BlockPos SUPPORT = new BlockPos(0, -60, 3), TARGET = SUPPORT.above();
    private static int stage, ticks, changed;
    private static CompletableFuture<Void> work;
    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        require(ticks - changed < 600, "Wand client timeout stage " + stage);
        switch (stage) {
            case 0 -> {
                MC.player.closeContainer(); MC.setScreen(null);
                server(p -> {
                    p.getInventory().clearContent();
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("constructionwand:iron_wand"))));
                    ConsumptionTests.seed(p, 0, new ItemStack(Items.STONE), 3);
                    p.serverLevel().setBlockAndUpdate(SUPPORT, Blocks.STONE.defaultBlockState());
                    // Flat world's grass surface is y=-60. Aim at the top face, not the front face;
                    // the author's render loop continuously replaces previews using the real crosshair.
                    p.serverLevel().removeBlock(TARGET, false); p.connection.teleport(.5, -60, .5, 0, 12);
                    p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 1 -> {
                if (ticks - changed < 40 || MC.player.getMainHandItem().isEmpty()) return false;
                require(chatCount("wand_") == 0, "Healthy wand adapter printed a warning");
                var packet = (CustomPacketPayload)Class.forName(ROOT + "network.PacketRequestPreview")
                        .getConstructor(BlockHitResult.class, ItemStack.class).newInstance(hit(), MC.player.getMainHandItem());
                PacketDistributor.sendToServer(packet); next();
            }
            case 2 -> {
                if (!preview().contains(TARGET)) return false;
                server(p -> require(ConsumptionTests.stock(p, 0) == 3 && p.serverLevel().isEmptyBlock(TARGET), "Preview consumed stock or built a block"));
                TemplateClientReview.capture("wand-native-cache-preview"); next();
            }
            case 3 -> { MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit()); next(); }
            case 4 -> {
                if (ticks - changed < 20 || !MC.level.getBlockState(TARGET).is(Blocks.STONE)) return false;
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 2 && p.getMainHandItem().getDamageValue() == 1,
                            "Native client interaction failed to spend exactly one cached item and durability");
                    FmpConfig.WAND.set(false); FmpConfig.refreshServer();
                    p.serverLevel().removeBlock(TARGET, false);
                    p.getInventory().setItem(9, new ItemStack(Items.STONE)); p.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 5 -> {
                if (ticks - changed < 60 || chatCount("wand_config_disabled") != 1) return false;
                TemplateClientReview.capture("wand-disabled-warning");
                MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit()); next();
            }
            case 6 -> {
                if (ticks - changed < 30 || !MC.level.getBlockState(TARGET).is(Blocks.STONE)) return false;
                require(chatCount("wand_config_disabled") == 1, "Disabled warning repeated");
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 2 && p.getInventory().getItem(9).isEmpty(), "Disabled adapter broke native source or spent cache");
                    FmpConfig.WAND.set(true); FmpConfig.refreshServer();
                }); next();
            }
            case 7 -> {
                if (ticks - changed < 40) return false;
                FeedMePackages.LOGGER.info("FMP_WAND_CLIENT_ACCOUNT initial=3 preview=3 placed=1 finalCache=2 disabledNative=1"); return true;
            }
        }
        return false;
    }
    private static BlockHitResult hit() { return new BlockHitResult(Vec3.atCenterOf(SUPPORT).add(0,.5,0), Direction.UP, SUPPORT, false); }
    private static java.util.Set<?> preview() throws ReflectiveOperationException {
        var render = Class.forName(ROOT + "client.ClientHandler").getField("renderBlockPreview").get(null);
        return (java.util.Set<?>)render.getClass().getField("previewBlocks").get(render);
    }
    private static int chatCount(String suffix) throws ReflectiveOperationException {
        var chat = MC.gui.getChat(); var field = chat.getClass().getDeclaredField("allMessages"); field.setAccessible(true);
        int count = 0;
        for (Object value : (java.util.List<?>)field.get(chat)) {
            var content = ((net.minecraft.client.GuiMessage)value).content().getContents();
            if (content instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().startsWith("gui.create_feed_me_packages.compat." + suffix)) count++;
        }
        return count;
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_WAND_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
