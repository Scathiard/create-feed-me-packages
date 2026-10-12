package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Additive capacity provenance; cannot make an unbounded or unexplained saved quantity valid. */
final class CapacityCodec {
    static final String KEY = "capacity_rules";
    private CapacityCodec() {}
    static CompoundTag write(CacheState<?> state) {
        var tag=new CompoundTag();tag.putInt("version",1);tag.putInt("slots",state.capacity().slots());tag.putInt("groups",state.capacity().groups());
        var retained=new ListTag();
        state.retainedCeilings().forEach((slot,ceiling)->{var row=new CompoundTag();row.putInt("slot",slot);row.putInt("ceiling",ceiling);retained.add(row);});
        tag.put("retained",retained);var controls=new ListTag();
        state.requestedControls().forEach((slot,c)->{var row=new CompoundTag();row.putInt("slot",slot);row.putInt("min",c.minimum());row.putInt("max",c.maximum());
            row.putInt("lower",c.settings().lower());row.putInt("upper",c.settings().upper());row.putInt("batch",c.settings().batch());row.putInt("pending",c.settings().pendingLimit());controls.add(row);});
        tag.put("requested",controls);return tag;
    }
    static <K extends MaterialVariant> CacheState<K> read(CompoundTag holder, UUID id, int level, long revision,
            List<Cell<K>> cells, Map<UUID,SupplyOrder<K>> orders) {
        if (!holder.contains(KEY)) return new CacheState<>(id,level,revision,cells,orders);
        require(holder,KEY,Tag.TAG_COMPOUND);var tag=holder.getCompound(KEY);
        fields(tag,Set.of("version","slots","groups","retained","requested"));
        for(var key:List.of("version","slots","groups"))require(tag,key,Tag.TAG_INT);
        if(tag.getInt("version")!=1)throw new IllegalArgumentException("Unknown capacity provenance version");
        var capacity=new CacheCapacity(tag.getInt("slots"),tag.getInt("groups"));
        var retained=new HashMap<Integer,Integer>();var requested=new HashMap<Integer,CellControls>();
        for(var entry:rows(tag,"retained")){
            var row=(CompoundTag)entry;fields(row,Set.of("slot","ceiling"));require(row,"slot",Tag.TAG_INT);require(row,"ceiling",Tag.TAG_INT);
            if(retained.put(row.getInt("slot"),row.getInt("ceiling"))!=null)throw new IllegalArgumentException("Duplicate retained slot");
        }
        for(var entry:rows(tag,"requested")){
            var row=(CompoundTag)entry;var keys=Set.of("slot","min","max","lower","upper","batch","pending");fields(row,keys);
            for(var key:keys)require(row,key,Tag.TAG_INT);
            var controls=new CellControls(row.getInt("min"),row.getInt("max"),new CellSettings(row.getInt("lower"),row.getInt("upper"),row.getInt("batch"),row.getInt("pending")));
            if(requested.put(row.getInt("slot"),controls)!=null)throw new IllegalArgumentException("Duplicate requested slot");
        }
        return new CacheState<>(id,level,revision,cells,orders,capacity,retained,requested);
    }
    private static ListTag rows(CompoundTag tag,String name) {
        require(tag,name,Tag.TAG_LIST);var list=(ListTag)tag.get(name);
        if(list.size()>CacheCapacity.MAX_SLOTS || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid capacity provenance rows");
        return list;
    }
    private static void fields(CompoundTag tag,Set<String> keys){if(!keys.containsAll(tag.getAllKeys()))throw new IllegalArgumentException("Unknown capacity provenance fields");}
    private static void require(CompoundTag tag,String key,int type){if(!tag.contains(key,type))throw new IllegalArgumentException("Missing or mistyped capacity field: "+key);}
}
