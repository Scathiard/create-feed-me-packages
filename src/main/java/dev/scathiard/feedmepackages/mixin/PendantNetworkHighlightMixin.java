package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedClientHandler;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

@Mixin(value = LogisticallyLinkedClientHandler.class, remap = false)
public abstract class PendantNetworkHighlightMixin {
    @Unique private static UUID fmp$displayFrequency;
    @Unique private static ItemStack fmp$displayLink;

    /** Give Create's client highlight pass a tuned display item. The player's actual hand is untouched. */
    @ModifyExpressionValue(method = "tick", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack fmp$highlightHeldPendant(ItemStack held) {
        UUID network = held.getItem() instanceof PendantItem ? held.get(FmpRegistries.NETWORK.get()) : null;
        if (network == null) return held;
        if (!network.equals(fmp$displayFrequency)) {
            fmp$displayLink = AllBlocks.STOCK_LINK.asStack();
            CustomData.update(DataComponents.BLOCK_ENTITY_DATA, fmp$displayLink, tag -> tag.putUUID("Freq", network));
            fmp$displayFrequency = network;
        }
        return fmp$displayLink;
    }
}
