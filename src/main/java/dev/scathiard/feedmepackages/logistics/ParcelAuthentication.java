package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.world.item.ItemStack;

public final class ParcelAuthentication {
    private ParcelAuthentication() {}
    private static String material(ParcelSeal seal, ItemStack box, HolderLookup.Provider registries) {
        // Validate the whole carrier before Create's lossy nine-slot projection, without changing the signed format.
        if (!ParcelEnvelope.recognized(box)) throw new IllegalArgumentException("Unrecognized parcel envelope");
        String contents = new StringTagVisitor().visit(PackageItem.getContents(box).serializeNBT(registries));
        if (contents.length() > 16384) throw new IllegalArgumentException("Package content template is too large");
        return seal.parcelId() + "\n" + seal.cacheId() + "\n" + seal.playerId() + "\n" + seal.requestId()
                + "\n" + seal.filterRevision() + "\n" + seal.arrived() + "\n" + seal.address().length() + ":" + seal.address()
                + "\n" + seal.variant().length() + ":" + seal.variant() + "\n" + contents;
    }
    public static void apply(CacheLedger ledger, HolderLookup.Provider registries, ItemStack box, ParcelSeal seal) {
        if (box.has(FmpRegistries.FLUID_PARCEL_SEAL.get())) throw new IllegalArgumentException("Ambiguous parcel kind");
        box.set(FmpRegistries.PARCEL_SEAL.get(), seal.signed(seal.arrived(), ledger.authenticate(material(seal, box, registries))));
    }
    public static boolean valid(CacheLedger ledger, HolderLookup.Provider registries, ItemStack box, ParcelSeal seal) {
        if (box.has(FmpRegistries.FLUID_PARCEL_SEAL.get()) || !ParcelEnvelope.items(box)
                || !PackageItem.getAddress(box).equals(seal.address())) return false;
        try {
            return ledger.authentic(material(seal, box, registries), seal.signature());
        } catch (IllegalArgumentException rejectedContents) {
            // Oversized or unencodable external input remains with its original carrier.
            return false;
        }
    }

    public static void applyFluid(CacheLedger ledger, HolderLookup.Provider registries, ItemStack box, ParcelSeal seal) {
        if (box.has(FmpRegistries.PARCEL_SEAL.get())) throw new IllegalArgumentException("Ambiguous parcel kind");
        box.set(FmpRegistries.FLUID_PARCEL_SEAL.get(), seal.signed(seal.arrived(), ledger.authenticate("fluid-v1\n" + material(seal, box, registries))));
    }
    public static boolean validFluid(CacheLedger ledger, HolderLookup.Provider registries, ItemStack box, ParcelSeal seal) {
        if (box.has(FmpRegistries.PARCEL_SEAL.get()) || box.getCount() != 1 || !PackageItem.isPackage(box)
                || !PackageItem.getAddress(box).equals(seal.address())) return false;
        try { return ledger.authentic("fluid-v1\n" + material(seal, box, registries), seal.signature()); }
        catch (IllegalArgumentException rejectedContents) { return false; }
    }
}
