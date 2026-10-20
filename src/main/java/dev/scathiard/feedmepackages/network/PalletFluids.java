package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;
import java.util.function.Consumer;

/** Read-only fluid candidates. Not merged into any item/ammunition/crafting material source. */
public final class PalletFluids {
    private PalletFluids() {}
    private static Consumer<Stock> receiver=s->{};
    private static final Map<ServerPlayer,Stock> LAST=new WeakHashMap<>();
    public static void receive(Consumer<Stock> value) { receiver=value; }
    public record Stock(UUID cache,List<String> templates,List<Integer> amounts) implements CustomPacketPayload {
        public static final Type<Stock> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"pallet_fluids"));
        public Stock {
            templates=List.copyOf(templates); amounts=List.copyOf(amounts);
            if(templates.size()!=amounts.size() || templates.size()>128 || amounts.stream().anyMatch(n->n<=0)) throw new IllegalArgumentException("Invalid fluid candidates");
        }
        public static final StreamCodec<RegistryFriendlyByteBuf,Stock> CODEC=StreamCodec.of((b,s)-> {
            b.writeUUID(s.cache);b.writeVarInt(s.templates.size());
            for(int i=0;i<s.templates.size();i++) { PanelPackets.text(b,s.templates.get(i),1024);b.writeVarInt(s.amounts.get(i)); }
        },b->{
            PanelPackets.bound(b,144*1024);var cache=b.readUUID();int count=PanelPackets.size(b,128);
            var templates=new ArrayList<String>();var amounts=new ArrayList<Integer>();
            for(int i=0;i<count;i++) { templates.add(PanelPackets.text(b,1024));amounts.add(b.readVarInt()); }
            PanelPackets.end(b);return new Stock(cache,templates,amounts);
        });
        @Override public Type<Stock> type() { return TYPE; }
    }
    public static void register(PayloadRegistrar registrar) { registrar.playToClient(Stock.TYPE,Stock.CODEC,(s,c)->receiver.accept(s)); }
    public static void tick(ServerPlayer player) {
        if(player.tickCount%4!=0) return;
        var access=AccessGate.resolve(player); var templates=new ArrayList<String>();var amounts=new ArrayList<Integer>();
        UUID cache=access.active()?access.handle().cacheId():new UUID(0,0);
        if(access.active() && PalletService.holding(player)) {
            var cells=CacheLedger.get(player.getServer()).find(cache).fluids().state().cells();
            for(int i=0;i<cells.size();i++) { var cell=cells.get(i);int count=(int)Math.min(Integer.MAX_VALUE,Math.max(0,cell.amount()-FluidActions.reserved(cache,i)));
                if(cell.filter()!=null && count>0) { templates.add(cell.filter().encoded());amounts.add(count); }
            }
        }
        var stock=new Stock(cache,templates,amounts);if(!stock.equals(LAST.put(player,stock))) PacketDistributor.sendToPlayer(player,stock);
    }
}
