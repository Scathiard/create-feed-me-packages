package dev.scathiard.feedmepackages.compat.fluidlogistics;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Optional public API calls on private copies only. No carrier extraction, foreign registration or refresh. */
public final class FluidPackages {
    private FluidPackages() {}
    public enum Status { MISSING, READY, INCOMPATIBLE }
    private static final java.util.Set<String> NATIVE_PACKAGES = java.util.Set.of(
            "fluidlogistics:fluid_package", "fluidlogistics:fluid_package_exposed",
            "fluidlogistics:fluid_package_weathered", "fluidlogistics:fluid_package_oxidized");
    /** Only the canonical native parcel we actually persist may wait for its missing registry. */
    public static boolean unavailableResidual(net.minecraft.nbt.CompoundTag raw) {
        return !ModList.get().isLoaded("fluidlogistics")
                && raw.contains("id", net.minecraft.nbt.Tag.TAG_STRING)
                && NATIVE_PACKAGES.contains(raw.getString("id"))
                && raw.contains("count", net.minecraft.nbt.Tag.TAG_INT) && raw.getInt("count") == 1;
    }
    private record Api(Method family, Method read, Method writeTank, Method create, Method capacity, Method key, Method keyFluid) {}
    private static final class Holder {
        private static final Api API = discover();
    }
    private static boolean failed;
    public static Status status() {
        if (!ModList.get().isLoaded("fluidlogistics")) return Status.MISSING;
        return Holder.API != null && !failed ? Status.READY : Status.INCOMPATIBLE;
    }

    private static Api discover() {
        if (!ModList.get().isLoaded("fluidlogistics")) return null;
        try {
            String base = "com.yision.fluidlogistics.content.logistics.fluidPackage.";
            return new Api(method(base + "FluidPackageItem", "isFluidPackage", boolean.class, ItemStack.class),
                    method(base + "FluidPackageContentHelper", "getSingleContainedFluid", FluidStack.class, ItemStack.class),
                    method(base + "CompressedTankItem", "setFluid", void.class, ItemStack.class, FluidStack.class),
                    method(base + "FluidPackageContentHelper", "createCanonicalPackage", ItemStack.class, FluidStack.class),
                    method(base + "CompressedTankItem", "getCapacity", int.class),
                    method("com.yision.fluidlogistics.api.packager.PackageResourceTypes", "createFluidKey", ItemStack.class, FluidStack.class),
                    method("com.yision.fluidlogistics.api.packager.PackageResourceTypes", "getFluid", FluidStack.class, ItemStack.class));
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            fail(incompatible); return null;
        }
    }
    private static Method method(String type, String name, Class<?> result, Class<?>... parameters) throws ReflectiveOperationException {
        var method = Class.forName(type, false, FluidPackages.class.getClassLoader()).getMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException(type + "." + name);
        return method;
    }
    private static void fail(Throwable error) {
        if (!failed) FeedMePackages.LOGGER.warn("FMP FluidLogistics public interface unavailable; fluid parcels remain with their carrier", error);
        failed = true;
    }

    public static boolean isFamily(ItemStack box) {
        if (box.isEmpty() || status() != Status.READY) return false;
        try { return (boolean) Holder.API.family().invoke(null, box.copy()); }
        catch (ReflectiveOperationException | LinkageError unavailable) { fail(unavailable); return false; }
    }

    /** Empty means unrecognized, never an empty parcel which FMP may consume. */
    public static FluidStack read(ItemStack box) {
        if (!dev.scathiard.feedmepackages.logistics.ParcelEnvelope.recognized(box)
                || !isFamily(box)) return FluidStack.EMPTY;
        try { return ((FluidStack) Holder.API.read().invoke(null, box.copy())).copy(); }
        catch (ReflectiveOperationException | LinkageError unavailable) { fail(unavailable); return FluidStack.EMPTY; }
    }

    public static int capacity() {
        if (status() != Status.READY) return 0;
        try { return Math.max(0, (int) Holder.API.capacity().invoke(null)); }
        catch (ReflectiveOperationException | LinkageError unavailable) { fail(unavailable); return 0; }
    }

    /** Author's request identity only. Never store this key in a player slot or the FMP item cache. */
    public static ItemStack requestKey(FluidStack fluid) {
        if (fluid.isEmpty() || status() != Status.READY) return ItemStack.EMPTY;
        try {
            var normalized = fluid.copyWithAmount(1);
            var key = ((ItemStack) Holder.API.key().invoke(null, normalized.copy())).copy();
            var actual = (FluidStack) Holder.API.keyFluid().invoke(null, key.copy());
            return key.getCount() == 1 && FluidStack.matches(normalized, actual) ? key : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | LinkageError unavailable) { fail(unavailable); return ItemStack.EMPTY; }
    }

    public static ItemStack create(FluidStack fluid) {
        if (fluid.isEmpty() || fluid.getAmount() > capacity()) throw new IllegalArgumentException("Unsupported fluid parcel amount");
        try {
            var box = ((ItemStack) Holder.API.create().invoke(null, fluid.copy())).copy();
            if (!FluidStack.matches(fluid, read(box))) throw new IllegalArgumentException("Native fluid parcel altered its contents");
            return box;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            fail(unavailable); throw new IllegalArgumentException("Fluid parcel factory unavailable", unavailable);
        }
    }

    /** Reduce only the fluid component on the original inner tank's copy; preserve other package/tank components. */
    public static ItemStack remainder(ItemStack original, int consumed) {
        var fluid = read(original);
        if (fluid.isEmpty() || consumed < 0 || consumed > fluid.getAmount()) throw new IllegalArgumentException("Invalid fluid parcel debit");
        int remaining = fluid.getAmount() - consumed;
        if (remaining == 0) return ItemStack.EMPTY;
        var result = original.copy();
        if (consumed > 0) {
            var tank = PackageItem.getContents(original).getStackInSlot(0).copy();
            try { Holder.API.writeTank().invoke(null, tank, fluid.copyWithAmount(remaining)); }
            catch (ReflectiveOperationException | LinkageError unavailable) {
                fail(unavailable); throw new IllegalArgumentException("Fluid parcel edit unavailable", unavailable);
            }
            var contents = NonNullList.withSize(9, ItemStack.EMPTY); contents.set(0, tank);
            result.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(contents));
            if (!FluidStack.matches(fluid.copyWithAmount(remaining), read(result)))
                throw new IllegalArgumentException("Fluid parcel remainder did not preserve the exact variant");
        }
        result.remove(FmpRegistries.FLUID_PARCEL_SEAL.get());
        return result;
    }
}
