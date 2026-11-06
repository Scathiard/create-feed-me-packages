package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.scathiard.feedmepackages.compat.maid.phantom.v103.Phantom103Flight;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.UUID;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** The 1.0.3 port queues a real return carrier on its belt; only its maid UUID lookup needs extending. */
@Pseudo
@Mixin(targets="com.yision.phantom.block.phantomport.PhantomPortReturnQueue",remap=false)
public abstract class Phantom103PortReturnMixin {
    @Coerce
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService;targetState(Lnet/minecraft/server/MinecraftServer;Lcom/yision/phantom/entity/courier/AirCourierEntity$Mission;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/yision/phantom/logistics/courier/AirCourierDeliveryService$TargetState;"),remap=false)
    private Object fmp$maidReturn(MinecraftServer server, @Coerce Object mission, ResourceKey<Level> dimension,
            BlockPos port, UUID id, ItemStack box, Operation<Object> original) {
        Object state=Phantom103Flight.carrierAvailability(server,mission,port,id);
        return state == null ? original.call(server,mission,dimension,port,id,box) : state;
    }
}
