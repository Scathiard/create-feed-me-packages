package dev.scathiard.feedmepackages.mixin;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.MixinService;
import java.io.IOException;

/** Check only the native boundaries the scoped sources depend on; absent mods remain ordinary. */
final class MaidConsumptionPorts {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static final String MAID = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String INVENTORY = "Lnet/neoforged/neoforge/items/wrapper/CombinedInvWrapper;";
    private MaidConsumptionPorts() {}
    static boolean inspect(String target, String mixin) {
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            var node = provider.getClassNode(target);
            boolean ready;
            if (mixin.endsWith("MaidMealSourcesMixin")) {
                var start = method(node, "start", "(Lnet/minecraft/server/level/ServerLevel;" + MAID + "J)V");
                ready = calls(start, "getAvailableBackpackInv", "()" + INVENTORY) == 1
                        && calls(start, "extractItem", "(IIZ)" + STACK) == 1
                        && calls(start, "findStackSlot", "(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;)I") == 1;
            } else if (mixin.endsWith("MaidBowSourcesMixin")) {
                var arrow = method(node, "getArrow", "(" + MAID + "F)Lnet/minecraft/world/entity/projectile/AbstractArrow;");
                var find = method(node, "findArrow", "(" + MAID + ")I");
                ready = calls(arrow, "getAvailableInv", "(Z)" + INVENTORY) == 1
                        && calls(arrow, "setStackInSlot", "(I" + STACK + ")V") == 1
                        && calls(find, "getAvailableInv", "(Z)" + INVENTORY) == 1
                        && calls(find, "findStackSlot", "(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;)I") == 1;
            } else {
                var maid = provider.getClassNode(ROOT + "entity.passive.EntityMaid");
                var crossbow = provider.getClassNode(ROOT + "entity.task.TaskCrossBowAttack");
                ready = method(maid, "getProjectile", "(" + STACK + ")" + STACK) != null
                        && method(crossbow, "hasArrow", "(" + MAID + ")Z") != null;
            }
            if (!ready) LogUtils.getLogger().warn("FMP maid consumption source disabled for {}: native method shape changed", target);
            return ready;
        } catch (ClassNotFoundException absent) { return false; }
        catch (IOException unreadable) {
            LogUtils.getLogger().warn("FMP maid consumption source disabled for {}: cannot inspect native class", target, unreadable);
            return false;
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }
    private static long calls(MethodNode method, String name, String descriptor) {
        if (method == null) return 0;
        long count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode invoke
                && invoke.name.equals(name) && invoke.desc.equals(descriptor)) count++;
        return count;
    }
}
