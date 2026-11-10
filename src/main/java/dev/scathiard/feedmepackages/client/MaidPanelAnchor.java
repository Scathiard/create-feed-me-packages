package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import java.lang.reflect.Method;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;

/** Uses the maid's own exclusion areas, not unrelated widgets added by other mods. */
final class MaidPanelAnchor {
    private MaidPanelAnchor() {}
    private static final class Api {
        static final Method AREAS = discover();
        private static Method discover() {
            try {
                return Class.forName("com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui")
                        .getMethod("getExclusionArea");
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                FeedMePackages.LOGGER.warn("FMP maid layout areas unavailable; using the native menu edge", unavailable);
                return null;
            }
        }
    }

    static int left(AbstractContainerScreen<?> screen) {
        int left = screen.getGuiLeft();
        var method = Api.AREAS;
        if (method == null || !method.getDeclaringClass().isInstance(screen)) return left;
        try {
            if (method.invoke(screen) instanceof Iterable<?> areas)
                for (var value : areas)
                    if (value instanceof Rect2i area && area.getY() < screen.getGuiTop() + screen.getYSize()
                            && area.getY() + area.getHeight() > screen.getGuiTop())
                        left = Math.min(left, area.getX());
            return left;
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Cannot read the maid menu's layout areas", failed);
        }
    }
}
