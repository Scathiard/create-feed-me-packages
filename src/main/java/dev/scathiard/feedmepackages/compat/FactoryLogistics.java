package dev.scathiard.feedmepackages.compat;

import com.google.common.collect.Multimap;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.logistics.DispatchScope;
import dev.scathiard.feedmepackages.logistics.PackageDispatch;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Optional ordinary-item dispatch through the author's generic API; never translates fluid or composite cargo. */
public final class FactoryLogistics {
    private FactoryLogistics() {}
    private record Api(Method wrap, Method order, Method find, Method busy, Method count, Method send) {}
    private static final class Holder { private static final Api API = discover(); }
    private static boolean failed;
    public static boolean installed() { return ModList.get().isLoaded("create_factory_logistics"); }
    public static boolean ready() { return installed() && OptionalMixins.factoryCompatible() && Holder.API != null && !failed; }
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("ru.zznty.create_factory_abstractions." + name, false, FactoryLogistics.class.getClassLoader());
    }
    private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... args)
            throws ReflectiveOperationException {
        var method = owner.getMethod(name, args);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic)
            throw new NoSuchMethodException(owner.getName() + "." + name);
        return method;
    }
    private static Api discover() {
        try {
            var stack = type("api.generic.stack.GenericStack"); var order = type("generic.support.GenericOrder");
            var manager = type("generic.support.GenericLogisticsManager");
            return new Api(method(stack, "wrap", stack, true, ItemStack.class), method(order, "order", order, true, List.class),
                    method(manager, "findPackagersForRequest", Multimap.class, true, UUID.class, order, IdentifiedInventory.class, String.class),
                    method(type("generic.support.GenericPackageTarget"), "isTooBusyFor", boolean.class, false, RequestType.class),
                    method(type("generic.support.GenericRequest"), "getCount", int.class, false),
                    method(manager, "performPackageRequests", void.class, true, Multimap.class));
        } catch (ReflectiveOperationException | LinkageError mismatch) { fail(mismatch); return null; }
    }
    private static void fail(Throwable error) {
        if (!failed) FeedMePackages.LOGGER.warn("FMP Factory Logistics dispatch unavailable; existing inventory and parcels remain intact", error);
        failed = true;
    }
    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (ReflectiveOperationException | LinkageError failure) {
            fail(failure);
            // Once dispatch starts, the caller must keep its uncertain reservation even if an author call fails.
            throw new IllegalStateException("Factory Logistics native dispatch failed", failure);
        }
    }
    public static PackageDispatch.Plan prepare(UUID network, ItemStack template, int amount, String address) {
        if (!FmpConfig.factoryLogisticsEnabled() || !ready()) return null;
        var api = Holder.API;
        var stack = invoke(api.wrap(), null, template.copyWithCount(amount));
        var order = invoke(api.order(), null, List.of(stack));
        var requests = (Multimap<?, ?>) invoke(api.find(), null, network, order, null, address);
        if (requests.isEmpty()) return null;
        for (var target : requests.keySet()) if ((boolean) invoke(api.busy(), target, RequestType.RESTOCK)) return null;
        var counters = List.copyOf(requests.values());
        return new PackageDispatch.Plan() {
            public long remaining() {
                long total = 0;
                for (var request : counters) {
                    int count = (int) invoke(api.count(), request);
                    if (count < 0) throw new IllegalStateException("Negative native Factory request remainder");
                    total = Math.addExact(total, count);
                }
                return total;
            }
            public void send() { invoke(api.send(), null, requests); }
        };
    }
    public static void stamp(ItemStack box) {
        if (FmpConfig.factoryLogisticsEnabled() && ready()) DispatchScope.onCreated(box);
    }

    /** Closed native routing schema, safe to retain even after Factory is uninstalled. No arbitrary CUSTOM_DATA. */
    public static boolean onlyOrderMetadata(CustomData data) {
        var root = data.copyTag();
        if (!root.getAllKeys().equals(Set.of("Fragment")) || !root.contains("Fragment", Tag.TAG_COMPOUND)) return false;
        var fragment = root.getCompound("Fragment");
        var fields = Set.of("OrderId", "LinkIndex", "IsFinalLink", "Index", "IsFinal", "OrderContext");
        if (!fields.containsAll(fragment.getAllKeys())) return false;
        for (String key : List.of("OrderId", "LinkIndex", "Index")) if (!fragment.contains(key, Tag.TAG_INT)) return false;
        for (String key : List.of("IsFinalLink", "IsFinal")) if (!fragment.contains(key, Tag.TAG_BYTE)) return false;
        if (!fragment.contains("OrderContext")) return true;
        if (!fragment.contains("OrderContext", Tag.TAG_COMPOUND)) return false;
        CompoundTag context = fragment.getCompound("OrderContext");
        return context.getAllKeys().equals(Set.of("Entries", "Crafts"))
                && context.contains("Entries", Tag.TAG_LIST) && context.contains("Crafts", Tag.TAG_LIST);
    }
}
