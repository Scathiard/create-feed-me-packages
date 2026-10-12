package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.Create;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import java.util.UUID;

/** A server-owned bearer, resolved again at every mutation. Never constructed from a network packet. */
public interface LogisticsEndpoint {
    LivingEntity entity();
    AccessGate.Result access();
    String address();
    boolean mayRequest(UUID network);

    static LogisticsEndpoint player(ServerPlayer player) {
        return new LogisticsEndpoint() {
            public LivingEntity entity() { return player; }
            public AccessGate.Result access() { return AccessGate.resolve(player); }
            public String address() { return SupplyService.address(player); }
            public boolean mayRequest(UUID network) { return Create.LOGISTICS.mayInteract(network, player); }
        };
    }
}
