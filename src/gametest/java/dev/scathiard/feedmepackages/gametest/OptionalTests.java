package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.gametest.framework.*;
import net.neoforged.fml.ModList;
import java.util.*;

/** Selection is separate from execution: absence never calls GameTestHelper.succeed(). */
@net.neoforged.fml.common.EventBusSubscriber(modid=FeedMePackages.MOD_ID)
@net.neoforged.neoforge.gametest.GameTestHolder(FeedMePackages.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class OptionalTests {
    private OptionalTests() {}
    @net.neoforged.bus.api.SubscribeEvent
    public static void requireProfile(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event){
        // GameTest registers explicit required-profile cases so its runner owns the failure result.
        if(event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer)return;
        checkRequired(System.getProperty("fmp.requiredOptionalTests",""),id->ModList.get().isLoaded(id));
    }
    public static void checkRequired(String required,java.util.function.Predicate<String> loaded){
        for(String id:required.split(","))if(!id.isEmpty() && !loaded.test(id))throw new IllegalStateException("Required test mod was not loaded: "+id);
    }
    @GameTestGenerator public static Collection<TestFunction> requiredProfiles() {
        var tests=new ArrayList<TestFunction>();
        for(String id:System.getProperty("fmp.requiredOptionalTests","").split(","))if(!id.isEmpty())
            tests.add(new TestFunction("profile","required_"+id,FeedMePackages.MOD_ID+":empty",100,0,true,h->{
                h.assertTrue(ModList.get().isLoaded(id),"Required test mod was not loaded: "+id);h.succeed();
            }));
        return tests;
    }
    static Collection<TestFunction> select(Class<?> type,String mod,String... names) {
        boolean installed=ModList.get().isLoaded(mod);
        FeedMePackages.LOGGER.info("FMP_OPTIONAL_SELECTION suite={} mod={} status={} count={}",type.getSimpleName(),mod,installed?"SELECTED":"SKIPPED_ABSENT",names.length);
        if(!installed)return List.of();
        var result=new ArrayList<TestFunction>();
        for(String name:names)result.add(new TestFunction("optional",type.getSimpleName().toLowerCase(Locale.ROOT)+"."+name.toLowerCase(Locale.ROOT),
                FeedMePackages.MOD_ID+":empty",100,0,true,h->{
                    try{type.getMethod(name,GameTestHelper.class).invoke(null,h);}
                    catch(ReflectiveOperationException failure){var cause=failure.getCause();if(cause instanceof Error e)throw e;if(cause instanceof RuntimeException e)throw e;throw new IllegalStateException("Optional test failed: "+name,cause==null?failure:cause);}
                }));
        return result;
    }
}
