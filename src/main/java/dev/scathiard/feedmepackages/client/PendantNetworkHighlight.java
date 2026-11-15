package dev.scathiard.feedmepackages.client;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import java.util.UUID;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Create 6.0.10 draws the outline of every device on the network of the player's main-hand, tuned stock link
 * ({@code LogisticallyLinkedClientHandler.tick}); its own gate accepts nothing but a tuned
 * {@code LogisticallyLinkedBlockItem}. R-020 asks for the same effect while a bound pendant is held, so this class
 * supplies the network id from the pendant and reuses Create's own public outline API with the parameters measured in
 * that method. Nothing is faked: no stand-in item, no borrowed identity - only the source of the network id is
 * extended, and the handler keeps its native behaviour whenever no bound pendant is held in the main hand.
 */
public final class PendantNetworkHighlight {
    /** Measured inside the native {@code tick()}: AABB inflation, line width, layer, interaction range and colours. */
    private static final double SHRINK = -0.0078125d;
    private static final float LINE_WIDTH = 0.03125f;
    private static final int LAYER = 2;
    private static final double INTERACT_RANGE = 64.0d;
    private static final int COLOR_A = 7376301;
    private static final int COLOR_B = 9481677;

    private PendantNetworkHighlight() {}

    /** The network of a bound pendant held in the main hand, or null when the highlight must stay native. */
    public static UUID boundNetwork() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        var stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof dev.scathiard.feedmepackages.item.PendantItem)) return null;
        return stack.get(dev.scathiard.feedmepackages.registry.FmpRegistries.NETWORK.get());
    }

    /**
     * Create's measured outline loop for one network, repeated every client tick: a rebound network follows at once, and
     * clearing the binding simply stops adding outlines, which is exactly how the native stock link fades out.
     */
    public static void outline(UUID frequency) {
        var player = Minecraft.getInstance().player;
        if (player == null || frequency == null) return;
        for (LogisticallyLinkedBehaviour linked : LogisticallyLinkedBehaviour.getAllPresent(frequency, false, true)) {
            var blockEntity = linked.blockEntity;
            if (blockEntity == null) continue;
            BlockPos pos = blockEntity.getBlockPos();
            VoxelShape shape = blockEntity.getBlockState().getShape(player.level(), pos);
            if (shape.isEmpty() || !player.canInteractWithBlock(pos, INTERACT_RANGE)) continue;
            var boxes = shape.toAabbs();
            for (int index = 0; index < boxes.size(); index++) {
                Outliner.getInstance()
                        .showAABB(Pair.of(linked, index), boxes.get(index).inflate(SHRINK).move(pos), LAYER)
                        .lineWidth(LINE_WIDTH)
                        .disableLineNormals()
                        .colored(AnimationTickHolder.getTicks() % 16 < 8 ? COLOR_A : COLOR_B);
            }
        }
    }
}
