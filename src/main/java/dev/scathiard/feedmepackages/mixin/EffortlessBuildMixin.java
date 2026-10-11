package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.compat.effortless.EffortlessOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.Map;

@Pseudo
@Mixin(targets="neoforge.nl.requios.effortlessbuilding.network.PacketHandler",remap=false)
public abstract class EffortlessBuildMixin {
    private static final String PLACE="handlePlaceBuildMode(Lneoforge/nl/requios/effortlessbuilding/network/PlaceBuildModePacket;Lnet/minecraft/server/level/ServerPlayer;)V";
    @WrapMethod(method=PLACE,remap=false)
    private static void fmp$batch(@Coerce Object packet,ServerPlayer player,Operation<Void> original) {
        try(var scope=EffortlessOperation.open(player)) {original.call(packet,player);}
    }
    @WrapOperation(method=PLACE,at=@At(value="INVOKE",target="Lneoforge/nl/requios/effortlessbuilding/buildmode/BuildSettings;canPlaceAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lneoforge/nl/requios/effortlessbuilding/buildmode/BuildSettings$ReplaceMode;Lnet/minecraft/world/item/ItemStack;)Z"),remap=false)
    private static boolean fmp$position(Level level,BlockPos pos,@Coerce Object mode,ItemStack filter,Operation<Boolean> original) {
        var scope=EffortlessOperation.current();if(scope!=null)scope.position(pos);
        return original.call(level,pos,mode,filter);
    }
    @WrapOperation(method=PLACE,at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),remap=false)
    private static boolean fmp$placed(ServerLevel level,BlockPos pos,BlockState state,int flags,Operation<Boolean> original) {
        var scope=EffortlessOperation.current();if(scope==null)return original.call(level,pos,state,flags);
        var before=BlockSnapshot.create(level.dimension(),level,pos);boolean result=false;
        try {result=original.call(level,pos,state,flags);return result;}
        finally {scope.placement(before,state,level.getBlockEntity(pos),state.getBlock().asItem(),result);}
    }
    @WrapOperation(method=PLACE,at=@At(value="INVOKE",target="Lneoforge/nl/requios/effortlessbuilding/utilities/InventoryHelper;giveOrDropItems(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;I)V"),remap=false)
    private static void fmp$return(Player player,Item item,int amount,Operation<Void> original) {
        var scope=EffortlessOperation.current();
        if(scope!=null && player instanceof ServerPlayer server && scope.deferReturn(server,()->original.call(player,item,amount)))return;
        original.call(player,item,amount);
    }
    @WrapOperation(method=PLACE,at=@At(value="INVOKE",target="Lneoforge/nl/requios/effortlessbuilding/utilities/InventoryHelper;consumeItems(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;I)I"),remap=false)
    private static int fmp$charge(Player player,Item item,int amount,Operation<Integer> original) {
        var scope=EffortlessOperation.current();if(scope==null || !(player instanceof ServerPlayer server) || !scope.owns(server))return original.call(player,item,amount);
        boolean before=scope.billing(true);
        try {return original.call(player,item,amount);}finally {scope.billing(before);}
    }
    @WrapOperation(method=PLACE,at=@At(value="INVOKE",target="Lneoforge/nl/requios/effortlessbuilding/utilities/UndoManager;recordOperation(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;Ljava/util/Map;)V"),remap=false)
    private static void fmp$history(ServerPlayer player,ResourceKey<Level> dimension,Map<BlockPos,?> changes,Operation<Void> original) {
        var scope=EffortlessOperation.current();if(scope!=null && scope.owns(player))scope.settle(changes);
        if(!changes.isEmpty())original.call(player,dimension,changes);
    }
}
