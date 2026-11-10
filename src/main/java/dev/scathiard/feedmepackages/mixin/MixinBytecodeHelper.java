package dev.scathiard.feedmepackages.mixin;

import java.io.IOException;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.service.MixinService;

/**
 * Shared ASM bytecode inspection utilities used by {@link OptionalMixins} and its domain shape
 * inspectors during Mixin bootstrap.
 *
 * <p>All methods inspect raw {@link ClassNode} structures via {@link MixinService} without loading
 * optional mod classes into the JVM classloader.
 */
public final class MixinBytecodeHelper {
    private MixinBytecodeHelper() {}

    static ClassNode getClassNode(String className) throws ClassNotFoundException, IOException {
        return MixinService.getService().getBytecodeProvider().getClassNode(className);
    }

    static boolean hasMethod(ClassNode target, String name, String descriptor) {
        return target.methods.stream()
                .anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }

    static int calls(
            ClassNode target,
            String name,
            String descriptor,
            String owner,
            String callName,
            String callDescriptor) {
        int count = 0;
        for (var method : target.methods) {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) {
                continue;
            }
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(owner)
                        && call.name.equals(callName)
                        && call.desc.equals(callDescriptor)) {
                    count++;
                }
            }
        }
        return count;
    }

    static boolean hasCodecCall(ClassNode target, String name, String descriptor, String invocation) {
        for (var method : target.methods) {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) {
                continue;
            }
            int matches = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("com/mojang/serialization/Codec")
                        && call.name.equals(invocation)
                        && call.desc.equals("(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")) {
                    matches++;
                }
            }
            return matches == 1;
        }
        return false;
    }
}
