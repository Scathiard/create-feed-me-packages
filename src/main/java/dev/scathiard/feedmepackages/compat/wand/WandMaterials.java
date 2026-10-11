package dev.scathiard.feedmepackages.compat.wand;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Registers the author's container API. No foreign types in class descriptors, no general item capability. */
public final class WandMaterials {
    private static final String ROOT = "dev.polaris_light.constructionwand.";
    private static volatile boolean ready;
    private static boolean initialized;
    private WandMaterials() {}

    public static boolean ready() { return ready; }
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded("constructionwand")) return;
        try {
            Class<?> api = Class.forName(ROOT + "api.IContainerHandler"), trace = Class.forName(ROOT + "containers.ContainerTrace");
            var matches = api.getMethod("matches", Player.class, ItemStack.class, ItemStack.class);
            var signature = api.getMethod("getSignature", Player.class, ItemStack.class);
            var count = api.getMethod("countItems", Player.class, trace, ItemStack.class, ItemStack.class);
            var use = api.getMethod("useItems", Player.class, trace, ItemStack.class, ItemStack.class, int.class);
            Map<Method, Class<?>> expected = Map.of(matches, boolean.class, signature, int.class, count, int.class, use, int.class);
            if (!api.isInterface() || api.getMethods().length != expected.size()
                    || expected.entrySet().stream().anyMatch(e -> e.getKey().getReturnType() != e.getValue()))
                throw new NoSuchMethodException("Container handler contract changed");
            Object manager = Class.forName(ROOT + "ConstructionWand").getField("containerManager").get(null);
            var register = manager.getClass().getMethod("register", api);
            if (register.getReturnType() != boolean.class) throw new NoSuchMethodException("Container registration contract changed");
            Object adapter = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FMP active worn cache source";
                    default -> throw new UnsupportedOperationException(method.toString());
                };
                if (method.equals(matches)) return active(args[0], (ItemStack) args[2]);
                if (method.equals(signature)) {
                    int value = System.identityHashCode(args[1]); return value == -1 ? 0 : value;
                }
                if (method.equals(count)) return available(args[0], (ItemStack) args[2], (ItemStack) args[3]);
                if (method.equals(use)) return consume(args[0], (ItemStack) args[2], (ItemStack) args[3], (int) args[4]);
                throw new UnsupportedOperationException(method.toString());
            });
            ready = (boolean) register.invoke(manager, adapter);
            if (!ready) throw new IllegalStateException("Container registration was rejected");
            FeedMePackages.LOGGER.info("FMP Construction Wand native container source registered");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            ready = false;
            FeedMePackages.LOGGER.warn("FMP Construction Wand source disabled: native container API unavailable", incompatible);
        }
    }

    private static boolean active(Object actor, ItemStack container) {
        if (!ready || !FmpConfig.constructionWandEnabled() || !(actor instanceof ServerPlayer player)
                || !player.getServer().isSameThread() || container == null || container.isEmpty()) return false;
        var access = AccessGate.resolve(player);
        return access.active() && access.terminals().size() == 1 && access.terminals().getFirst().stack() == container;
    }
    private static int available(Object actor, ItemStack material, ItemStack container) {
        if (material == null || material.isEmpty() || !active(actor, container)) return 0;
        return MaterialTransaction.open((ServerPlayer) actor).map(tx -> tx.cacheAvailable(material)).orElse(0);
    }
    private static int consume(Object actor, ItemStack material, ItemStack container, int requested) {
        if (requested <= 0 || material == null || material.isEmpty() || !active(actor, container)) return requested;
        var tx = MaterialTransaction.open((ServerPlayer) actor).orElse(null);
        if (tx == null) return requested;
        int amount = Math.min(requested, tx.cacheAvailable(material));
        return amount > 0 && tx.takeCache(material, amount) && tx.commit() ? requested - amount : requested;
    }
}
