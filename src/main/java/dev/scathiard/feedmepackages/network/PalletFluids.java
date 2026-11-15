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
    public static void clearClientPages() { PAGES.clear(); }
    private static final StockPages<Stock> PAGES = new StockPages<>();
    private static Consumer<Stock> receiver=s->{};
    private static final Map<ServerPlayer,Stock> LAST=new WeakHashMap<>();
    public static void receive(Consumer<Stock> value) { receiver=value; }
    public record Stock(UUID cache,List<String> templates,List<Integer> amounts, int offset, boolean last) implements CustomPacketPayload {
        public Stock(UUID cache,List<String> templates,List<Integer> amounts) { this(cache,templates,amounts,0,true); }
        public List<Stock> pages() {
            if (amounts.size() <= StockPages.SIZE) return List.of(this);
            return StockPages.split(amounts.size(), (from,to) -> new Stock(cache, templates.subList(from,to),
                    amounts.subList(from,to), from, to == amounts.size()));
        }
        public Stock receivePage() {
            return PAGES.accept(this, offset, last, amounts.size(), (a,b) -> a.cache.equals(b.cache),
                    parts -> new Stock(cache, StockPages.collect(parts, Stock::templates), StockPages.collect(parts, Stock::amounts)));
        }
        public static final Type<Stock> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"pallet_fluids"));
        public Stock {
            templates=List.copyOf(templates); amounts=List.copyOf(amounts);
            if(templates.size()!=amounts.size() || offset < 0 || amounts.stream().anyMatch(n->n<=0)) throw new IllegalArgumentException("Invalid fluid candidates");
        }
        public static final StreamCodec<RegistryFriendlyByteBuf,Stock> CODEC=StreamCodec.of((b,s)-> {
            if(s.amounts.size()>StockPages.SIZE) throw new IllegalArgumentException("Fluid stock requires paging");
            b.writeVarInt(s.offset); b.writeBoolean(s.last); b.writeUUID(s.cache);b.writeVarInt(s.templates.size());
            for(int i=0;i<s.templates.size();i++) { PanelPackets.text(b,s.templates.get(i),1024);b.writeVarInt(s.amounts.get(i)); }
        },b->{
            PanelPackets.bound(b,144*1024);int offset=b.readVarInt();boolean last=b.readBoolean();var cache=b.readUUID();int count=PanelPackets.size(b,StockPages.SIZE);
            var templates=new ArrayList<String>();var amounts=new ArrayList<Integer>();
            for(int i=0;i<count;i++) { templates.add(PanelPackets.text(b,1024));amounts.add(b.readVarInt()); }
            PanelPackets.end(b);return new Stock(cache,templates,amounts,offset,last);
        });
        @Override public Type<Stock> type() { return TYPE; }
    }
    public static void register(PayloadRegistrar registrar) { registrar.playToClient(Stock.TYPE,Stock.CODEC,(s,c)->{ var complete=s.receivePage(); if(complete!=null) receiver.accept(complete); }); }
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
        var stock=new Stock(cache,templates,amounts);if(!stock.equals(LAST.put(player,stock))) for (var page : stock.pages()) PacketDistributor.sendToPlayer(player,page);
    }
}
