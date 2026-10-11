package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.UUID;

/** Native dispatch plans share accounting, but each owner keeps its own request and packager types. */
public final class PackageDispatch {
    private PackageDispatch() {}
    public interface Plan {
        long remaining();
        void send();
    }
    public static Plan prepare(UUID network, ItemStack template, int amount, String address, boolean fluid) {
        var order = PackageOrderWithCrafts.simple(List.of(new BigItemStack(template, amount)));
        var requests = LogisticsManager.findPackagersForRequest(network, order, null, address);
        if (requests.isEmpty() || requests.keySet().stream().anyMatch(p -> p.isTooBusyFor(RequestType.RESTOCK))) return null;
        // The native dispatcher may remove completed requests from its multimap. Retain their mutable counters.
        var counters = List.copyOf(requests.values());
        return new Plan() {
            public long remaining() { return counters.stream().mapToLong(r -> r.getCount()).sum(); }
            public void send() { LogisticsManager.performPackageRequests(requests); }
        };
    }
}
