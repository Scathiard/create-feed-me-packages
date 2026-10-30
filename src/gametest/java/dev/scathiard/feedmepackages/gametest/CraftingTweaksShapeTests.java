package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.interaction.*;
import dev.scathiard.feedmepackages.consumption.*;
import dev.scathiard.feedmepackages.network.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

@GameTestHolder("create_feed_me_packages") @PrefixGameTestTemplate(false)
public final class CraftingTweaksShapeTests {
    @GameTestGenerator public static Collection<TestFunction> selectedTests(){return OptionalTests.select(CraftingTweaksShapeTests.class,"craftingtweaks","everyNativeMessageMustMatch","missingAccountingRemovesVirtualSources");}
    public static void everyNativeMessageMustMatch(GameTestHelper h) throws Exception {
        var nodes=new HashMap<String,ClassNode>();
        for(var name:OptionalMixins.CRAFTING_TWEAK_MESSAGES){
            try(var in=CraftingTweaksShapeTests.class.getClassLoader().getResourceAsStream("net/blay09/mods/craftingtweaks/network/"+name+".class")){
                h.assertTrue(in!=null,"Native editor class absent");var node=new ClassNode();new ClassReader(in).accept(node,0);nodes.put(name,node);
            }
        }
        h.assertTrue(OptionalMixins.craftingTweaksShape(nodes) && OptionalMixins.craftingTweaksCompatible(),"Locked native editor failed complete shape check");
        for(var name:OptionalMixins.CRAFTING_TWEAK_MESSAGES){
            var node=nodes.get(name);var method=node.methods.stream().filter(m->m.name.equals("handle")).findFirst().orElseThrow();
            String descriptor=method.desc;int access=method.access;
            method.desc="()V";h.assertTrue(!OptionalMixins.craftingTweaksShape(nodes),"Changed descriptor accepted: "+name);method.desc=descriptor;
            method.access &= ~Opcodes.ACC_STATIC;h.assertTrue(!OptionalMixins.craftingTweaksShape(nodes),"Instance handler accepted: "+name);method.access=access;
            var extra=new MethodNode(Opcodes.ACC_STATIC,"handle","()V",null,null);node.methods.add(extra);
            h.assertTrue(!OptionalMixins.craftingTweaksShape(nodes),"Ambiguous overload accepted: "+name);node.methods.remove(extra);
            nodes.remove(name);h.assertTrue(!OptionalMixins.craftingTweaksShape(nodes),"Partial manifest accepted: "+name);nodes.put(name,node);
        }
        h.succeed();
    }
    public static void missingAccountingRemovesVirtualSources(GameTestHelper h) throws Exception {
        var field=OptionalMixins.class.getDeclaredField("craftingTweaksReady");field.setAccessible(true);var previous=field.get(null);
        var p=TestPlayers.create(h,FmpRegistries.PENDANT.toStack());ConsumptionTests.seed(p,0,new ItemStack(Items.OAK_LOG),8);
        try{
            field.set(null,false);
            h.assertTrue(!CraftingService.enabled(p) && MaterialTransaction.openCrafting(p).isEmpty(),"Missing accounting still exposed cache crafting");
            var hints=MaterialHints.next(p);h.assertTrue(!hints.craftingEnabled(),"Server advertised virtual crafting with missing accounting");
            var v=CacheActions.open(p);
            var intent=new CacheActions.Intent(v.session(),v.record().state().revision(),CacheActions.Action.TAKE_CURSOR,0,1,-1,"");
            h.assertTrue(CacheActions.execute(p,intent)!=CacheActions.Result.OK && p.containerMenu.getCarried().isEmpty(),"Unaccounted cursor alias created");
            h.assertTrue(ConsumptionTests.stock(p,0)==8,"Rejected preview charged stock");
            var take=new CacheActions.Intent(v.session(),v.record().state().revision(),CacheActions.Action.TAKE_INVENTORY,0,1,-1,"");
            h.assertTrue(CacheActions.execute(p,take)==CacheActions.Result.OK && p.getInventory().countItem(Items.OAK_LOG)==1,"Mismatch blocked physical inventory route");
        }finally{field.set(null,previous);}
        h.succeed();
    }
}
