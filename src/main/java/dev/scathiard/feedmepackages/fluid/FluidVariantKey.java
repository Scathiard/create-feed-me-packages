package dev.scathiard.feedmepackages.fluid;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.scathiard.feedmepackages.domain.MaterialVariant;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.neoforged.neoforge.fluids.FluidStack;
import java.nio.charset.StandardCharsets;

/** Exact persistent fluid identity. Amount is stored separately, in mB; no mutable stack escapes. */
public final class FluidVariantKey implements MaterialVariant {
    public static final int MILLIBUCKETS_PER_GROUP = 8000;
    public static final int MAX_BYTES = 1024;
    private final CompoundTag template;
    private final String canonical;

    private FluidVariantKey(CompoundTag template) {
        this.template = template.copy();
        canonical = new StringTagVisitor().visit(this.template);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Fluid template exceeds 1024 bytes");
    }

    public static FluidVariantKey of(FluidStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty fluid template");
        var one = stack.copyWithAmount(1);
        var key = new FluidVariantKey((CompoundTag) one.save(registries));
        if (!FluidStack.matches(one, key.stack(registries, 1)))
            throw new IllegalArgumentException("Fluid components cannot round-trip without alteration");
        return key;
    }

    public static FluidVariantKey decode(String encoded, HolderLookup.Provider registries) {
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Fluid template exceeds 1024 bytes");
        try {
            var tag = TagParser.parseTag(encoded);
            if (!tag.contains("amount", Tag.TAG_INT) || tag.getInt("amount") != 1)
                throw new IllegalArgumentException("Fluid template must contain exactly one mB");
            var stack = FluidStack.parse(registries, tag)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown fluid template"));
            var verified = of(stack, registries);
            if (!verified.template.equals(tag))
                throw new IllegalArgumentException("Fluid template has unknown or noncanonical data");
            return verified;
        } catch (CommandSyntaxException invalid) {
            throw new IllegalArgumentException("Malformed fluid template", invalid);
        }
    }

    @Override public int stackSize() { return MILLIBUCKETS_PER_GROUP; }

    public FluidStack stack(HolderLookup.Provider registries, int amount) {
        if (amount < 1) throw new IllegalArgumentException("Materialized fluid amount must be positive");
        return FluidStack.parse(registries, template.copy())
                .orElseThrow(() -> new IllegalArgumentException("Fluid template no longer decodes")).copyWithAmount(amount);
    }

    public String encoded() { return canonical; }
    @Override public boolean equals(Object other) { return other instanceof FluidVariantKey key && canonical.equals(key.canonical); }
    @Override public int hashCode() { return canonical.hashCode(); }
    @Override public String toString() { return template.getString("id"); }
}
