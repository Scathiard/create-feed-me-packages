package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.lang.reflect.Method;

/** Optional native inventory menus only; resolving an entity here does not grant inventory access. */
public final class MaidMenus {
    private MaidMenus() {}
    private record Bridge(Class<?> type, Method maid) {}
    private static final class Api {
        private static final Bridge BRIDGE = discover();
        private static Bridge discover() {
            try {
                var type = Class.forName("com.github.tartaricacid.touhoulittlemaid.inventory.container.MaidMainContainer");
                return new Bridge(type, type.getMethod("getMaid"));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                FeedMePackages.LOGGER.warn("FMP maid panel unavailable: native menu API changed; native consumption is unaffected", unavailable);
                return null;
            }
        }
    }
    public static boolean supports(AbstractContainerMenu menu) {
        return MaidCompatibility.enabled() && Api.BRIDGE != null && Api.BRIDGE.type().isInstance(menu);
    }
    public static LivingEntity target(AbstractContainerMenu menu) {
        if (!supports(menu)) return null;
        try {
            return Api.BRIDGE.maid().invoke(menu) instanceof LivingEntity maid ? maid : null;
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException("Cannot resolve the native maid menu target", broken);
        }
    }
}
