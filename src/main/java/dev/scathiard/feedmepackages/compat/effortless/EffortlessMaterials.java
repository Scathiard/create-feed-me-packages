package dev.scathiard.feedmepackages.compat.effortless;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Item-only requests can spend only the matching default-component cache variant. */
public final class EffortlessMaterials {
    private static Supplier<List<ItemStack>> clientSources=List::of;
    private EffortlessMaterials() {}
    public static void clientSources(Supplier<List<ItemStack>> source) {clientSources=Objects.requireNonNull(source);}
    public static boolean enabled() {return OptionalMixins.effortlessCompatible() && FmpConfig.effortlessEnabled();}
    public static int count(Player player,Item item,int nativeCount) {
        if(!OptionalMixins.effortlessCompatible() || player.isSpectator())return nativeCount;
        var prototype=new ItemStack(item); if(prototype.isEmpty())return nativeCount;
        long cached=0;
        if(player instanceof ServerPlayer server) {
            if(!enabled() || !server.getServer().isSameThread())return nativeCount;
            cached=MaterialTransaction.open(server).map(tx->tx.cacheAvailable(prototype)).orElse(0);
        } else if(player.level().isClientSide) {
            for(var stack:clientSources.get())if(ItemStack.isSameItemSameComponents(stack,prototype))cached+=stack.getCount();
        }
        return (int)Math.min(Integer.MAX_VALUE,Math.max(0,(long)nativeCount)+cached);
    }
    /** The author has already consumed ordinary sources; return the total actually consumed. */
    public static int consume(Player player,Item item,int requested,int nativeTaken) {
        if(requested<=0 || nativeTaken<0 || nativeTaken>=requested || !enabled()
                || !(player instanceof ServerPlayer server) || !server.getServer().isSameThread())return nativeTaken;
        var prototype=new ItemStack(item); var tx=MaterialTransaction.open(server).orElse(null);
        if(tx==null || prototype.isEmpty())return nativeTaken;
        int amount=Math.min(requested-nativeTaken,tx.cacheAvailable(prototype));
        if(amount<=0 || !tx.takeCache(prototype,amount) || !tx.commit())return nativeTaken;
        EffortlessOperation.paidCache(server,tx.handle(),prototype.copyWithCount(amount));
        return nativeTaken+amount;
    }
}
