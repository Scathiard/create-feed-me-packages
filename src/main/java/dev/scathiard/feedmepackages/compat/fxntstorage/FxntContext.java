package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/**
 * The per-call context of ONE of their transfer calls: which materials this call may borrow for. Their client
 * preview knows the recipe, their server placement knows one ingredient at a time, and their max-count helper
 * knows the whole list - all three set it here, and the wrapper reads it. It is a transient call context (and
 * is cleared at the end of the call), never a home for the wrapper itself.
 */
public final class FxntContext {
    private static final ThreadLocal<List<Ingredient>> MATERIALS = new ThreadLocal<>();
    private FxntContext() {}

    public static void materials(List<Ingredient> materials) { MATERIALS.set(materials); }
    public static List<Ingredient> materials() { return MATERIALS.get(); }
    public static void clear() { MATERIALS.remove(); }
}