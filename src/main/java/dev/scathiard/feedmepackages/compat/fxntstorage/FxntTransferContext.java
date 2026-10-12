package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.crafting.Ingredient;
import dev.scathiard.feedmepackages.network.TransferOrigin.Viewer;
import java.util.ArrayList;
import java.util.List;

/** Nested calls and exceptions restore the previous context; nothing persists between packets. */
public final class FxntTransferContext implements AutoCloseable {
    private static final ThreadLocal<FxntTransferContext> CURRENT = new ThreadLocal<>();
    private final FxntTransferContext previous;
    public final List<Ingredient> ingredients;
    public final FxntMaterials.Transfer transfer;
    private final boolean nativePacket;
    private final Viewer viewer;
    private boolean published;
    private final List<FxntMaterials.Transfer> failedTransfers = new ArrayList<>();
    public FxntTransferContext(List<Ingredient> ingredients, FxntMaterials.Transfer transfer) {
        this(ingredients, transfer, false);
    }
    public FxntTransferContext(List<Ingredient> ingredients, FxntMaterials.Transfer transfer, boolean nativePacket) {
        this(ingredients, transfer, nativePacket, current() == null ? Viewer.UNKNOWN : current().viewer);
    }
    public FxntTransferContext(List<Ingredient> ingredients, FxntMaterials.Transfer transfer, boolean nativePacket, Viewer viewer) {
        this.ingredients = List.copyOf(ingredients); this.transfer = transfer; previous = CURRENT.get(); CURRENT.set(this);
        this.nativePacket = nativePacket; this.viewer = viewer;
    }
    public static FxntTransferContext current() { return CURRENT.get(); }
    public static boolean cacheAllowed() { return (current() == null ? Viewer.UNKNOWN : current().viewer).enabled(); }
    public static void deferFailed(FxntMaterials.Transfer transfer) {
        for (var scope = current(); scope != null; scope = scope.previous) if (scope.nativePacket) {
            scope.failedTransfers.add(transfer); return;
        }
    }
    /** Observed only after the original packet's original setDataChanged callback returns. */
    public static void nativePublished() {
        for (var scope = current(); scope != null; scope = scope.previous) if (scope.nativePacket) {
            scope.published = true; return;
        }
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        if (nativePacket && published) for (var transfer : failedTransfers) transfer.returnPublishedNativeDebits();
    }
}
