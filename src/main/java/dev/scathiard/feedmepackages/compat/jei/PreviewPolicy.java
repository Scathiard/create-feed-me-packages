package dev.scathiard.feedmepackages.compat.jei;

/**
 * Preview rule v3 - "clickable means it will work; if it cannot work, grey it and say what is missing".
 *
 * <p>This REPLACES the previous always-allow rule, which was itself the correction of a wrong premise: the
 * grey button seen at 02:03 was NOT a misjudgement (that client really had no iron plates anywhere: the
 * cache held spruce logs + iron ingots, the player carried 9 iron ingots, and the worn backpack held iron
 * blocks), so releasing everything made the button lie. Only two things may refuse:
 * <ul>
 *   <li>an estimate that both MISSES and can name the concrete shortfall (the player learns what to get);</li>
 *   <li>a verdict we can prove here and now (NO_SPACE / UNSUPPORTED / TOO_COMPLEX).</li>
 * </ul>
 * Everything else is released, including a MISSING estimate that cannot name anything - the one safety valve
 * kept, because what cannot be named cannot honestly justify a refusal.
 *
 * <p>Kept free of game and JEI types so the whole truth table is unit-testable.
 */
public final class PreviewPolicy {
    /** The client's own estimate, reduced to the axes the preview cares about. */
    public enum Estimate { OK, MISSING, NO_SPACE, UNSUPPORTED, TOO_COMPLEX, INACTIVE }
    /** What the preview did with one query; it is also the log field the user reads. */
    public enum Decision { ALLOW_NATIVE, ALLOW_ESTIMATE_OK, ALLOW_UNPROVABLE, REFUSE }
    private PreviewPolicy() {}

    /**
     * @param nativePreviewOk the displaced handler / JEI's generic transfer said it can do this click
     * @param estimate        this client's own estimate (crafting grid + player slots + cache view)
     * @param missingNamed    whether the estimate could name the concrete shortfall
     */
    public static Decision decide(boolean nativePreviewOk, Estimate estimate, boolean missingNamed) {
        if (nativePreviewOk) return Decision.ALLOW_NATIVE;
        // An estimate we never got (or one that can name nothing) proves nothing: release.
        if (estimate == null || estimate == Estimate.INACTIVE) return Decision.ALLOW_UNPROVABLE;
        if (estimate == Estimate.OK) return Decision.ALLOW_ESTIMATE_OK;
        if (estimate == Estimate.MISSING) return missingNamed ? Decision.REFUSE : Decision.ALLOW_UNPROVABLE;
        return Decision.REFUSE;
    }

    /** The result key to refuse with; null whenever the decision allows the click. */
    public static String reason(Decision decision, Estimate estimate) {
        if (decision != Decision.REFUSE) return null;
        if (estimate == null) return "invalid_request";
        return switch (estimate) {
            case MISSING -> "missing_material";
            case NO_SPACE -> "no_space";
            case UNSUPPORTED -> "unsupported_recipe";
            case TOO_COMPLEX -> "too_complex";
            default -> "invalid_request";
        };
    }
}
