package dev.scathiard.feedmepackages.compat.maid.phantom;

import dev.scathiard.feedmepackages.compat.maid.phantom.v102.*;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;

/** Shared launch/return-queue ports; flight behavior stays in the matching native-version adapter. */
public final class PhantomMaidCompat {
    private PhantomMaidCompat() {}
    public static boolean modern() { return dev.scathiard.feedmepackages.compat.maid.phantom.v103.Phantom103Flight.enabled(); }
    public static boolean classic() { return OptionalMixins.phantom100FlightCompatible(); }
    public static boolean enabled() { return modern() || classic() || Phantom102Gate.enabled(); }
    public static boolean flightReady() {
        return modern() ? OptionalMixins.phantom103FlightCompatible()
                : (classic() || Phantom102Gate.enabled() && OptionalMixins.phantom102FlightCompatible())
                    && MaidPhantomTasks.available() && MaidPhantomFlight.available();
    }
    public static boolean ready() {
        return flightReady() && OptionalMixins.phantom102Compatible() && (classic() || OptionalMixins.phantom102BeltCompatible())
                && portReady();
    }
    public static boolean portReady() {
        return modern() ? OptionalMixins.phantom103PortCompatible() && OptionalMixins.phantom103BeltCompatible()
                : OptionalMixins.phantom102PortReturnCompatible() && MaidPhantomPortReturn.available();
    }
}
