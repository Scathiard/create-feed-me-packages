package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReturnTests {
    @GameTest(template = "empty")
    public static void returnNeedsAddressAndCarrierBeforeDeducting(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        // Stone: 64 stack. max=1 group = 64 items; put 100 -> overage 36 (within level-1 capacity 128).
        edit.filter(0, key); edit.thresholds(0, 1, 1); edit.insert(0, key, 100);
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        // No return address -> no dispatch, no deduction.
        ReturnService.check(player);
        helper.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount() == 100, "No-address return deducted stock");
        // Address set but no carrier (core run has no transport add-on) -> still no deduction.
        ledger.setReturnAddress(handle.cacheId(), "ReturnStation");
        ReturnService.check(player);
        helper.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount() == 100, "Carrier-less return deducted stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void returnAddressPersistsAcrossReload(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        ledger.setReturnAddress(handle.cacheId(), "FMP@origin");
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
        helper.assertTrue(saved.problem().isEmpty() && "FMP@origin".equals(saved.returnAddress(handle.cacheId())), "Return address did not persist");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void returnAddressSavesThroughIntentPath(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        // The real client sends SET_RETURN_ADDRESS with slot=-1 through CacheActions (cache-level, no cell).
        var view = CacheActions.open(player);
        var before = ledger.find(handle.cacheId());
        var result = CacheActions.execute(player, new CacheActions.Intent(view.session(), before.state().revision(),
                CacheActions.Action.SET_RETURN_ADDRESS, -1, -1, -1, "FMP@origin"));
        helper.assertTrue(result == CacheActions.Result.OK, "Intent-path return address was rejected: " + result);
        helper.assertTrue("FMP@origin".equals(ledger.returnAddress(handle.cacheId())), "Intent path did not save the return address");
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
        helper.assertTrue(saved.problem().isEmpty() && "FMP@origin".equals(saved.returnAddress(handle.cacheId())), "Saved return address did not survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noReturnMarkerSurvivesUpgrade(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        // max=-1 = "no return": a semantic marker that survives upgrades (the client displays it as
        // the capacity full position, so the thumb follows new capacities automatically).
        edit.filter(0, key); edit.thresholds(0, 0, -1); edit.upgrade();
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        var cell = ledger.find(handle.cacheId()).state().cells().get(0);
        helper.assertTrue(cell.maximum() == -1, "No-return marker should survive the upgrade: " + cell.maximum());
        helper.succeed();
    }

    private record Held(net.minecraft.server.level.ServerPlayer player, CacheLedger ledger, CacheHandle handle,
                        CacheRecord before, ReturnEscrow hold) {}
    private static Held held(GameTestHelper h, boolean cached) {
        var p = TestPlayers.create(h, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(p).handle(); var ledger = CacheLedger.get(p.getServer());
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        var stone = ItemVariantKey.of(new ItemStack(Items.STONE), p.registryAccess());
        var carrier = new ItemStack(Items.PAPER);
        edit.filter(0, stone); edit.insert(0, stone, 100);
        if (cached) { var key = ItemVariantKey.of(carrier, p.registryAccess()); edit.filter(1, key); edit.insert(1, key, 3); }
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish())); before = ledger.find(handle.cacheId());
        var hold = new ReturnEscrow(java.util.UUID.randomUUID(), handle, before.state().revision(), 0, stone, 7,
                carrier, cached ? -1 : 7, cached ? 1 : -1, "phantom", "fixture", false);
        ledger.beginReturn(hold, before, p.registryAccess());
        return new Held(p, ledger, handle, before, hold);
    }
    @GameTest(template = "empty") public static void uncertainReturnLocksSourcesAndSurvivesReload(GameTestHelper h) {
        var f=held(h,true);
        h.assertTrue(AccessGate.resolve(f.player).status()==AccessGate.Status.RETURN_HELD
                && dev.scathiard.feedmepackages.consumption.MaterialTransaction.open(f.player).isEmpty(), "Held return remained spendable");
        boolean blocked=false;
        try { f.ledger.replace(f.handle,f.before.state().revision(),f.before); } catch(IllegalStateException expected) { blocked=true; }
        h.assertTrue(blocked,"Direct cache transaction bypassed return hold");
        blocked=false;
        try { f.ledger.prepareOrdinaryUpgrade(f.before,2); } catch(IllegalStateException expected) { blocked=true; }
        h.assertTrue(blocked,"Manufacturing bypassed return hold");
        var reloaded=CacheLedger.load(f.ledger.save(new CompoundTag(),f.player.registryAccess()),f.player.registryAccess());
        h.assertTrue(reloaded.problem().isEmpty() && reloaded.returnEscrow(f.handle.cacheId()).id().equals(f.hold.id())
                && reloaded.find(f.handle.cacheId()).state().equals(f.before.state()),"Reload lost held original stock");
        reloaded.finishReturn(reloaded.returnEscrow(f.handle.cacheId()),f.player.registryAccess());
        h.assertTrue(reloaded.find(f.handle.cacheId()).state().cells().get(0).amount()==93
                && reloaded.find(f.handle.cacheId()).state().cells().get(1).amount()==2,"Settlement did not debit cargo and carrier exactly once");
        boolean duplicate=false;try {reloaded.finishReturn(f.hold,f.player.registryAccess());}catch(IllegalStateException expected){duplicate=true;}
        h.assertTrue(duplicate,"Repeated receipt settled twice");
        f.ledger.cancelRejectedReturn(f.ledger.rejectReturn(f.hold));h.succeed();
    }
    @GameTest(template = "empty") public static void recoveryRequiresAuthorityEvidenceAndExactHandoff(GameTestHelper h) {
        var f=held(h,true);var source=f.player.createCommandSourceStack().withSuppressedOutput();
        var low=source.withPermission(0);var admin=source.withPermission(2);
        var recovery=dev.scathiard.feedmepackages.logistics.ReturnRecovery.Result.class;
        h.assertTrue(dev.scathiard.feedmepackages.logistics.ReturnRecovery.confirm(low,f.handle.cacheId(),f.hold.id(),true,"inspected")
                ==dev.scathiard.feedmepackages.logistics.ReturnRecovery.Result.FORBIDDEN,"Unprivileged recovery allowed");
        h.assertTrue(dev.scathiard.feedmepackages.logistics.ReturnRecovery.confirm(admin,f.handle.cacheId(),f.hold.id(),true,"")
                ==dev.scathiard.feedmepackages.logistics.ReturnRecovery.Result.EVIDENCE_REQUIRED,"Evidence-free recovery allowed");
        h.assertTrue(dev.scathiard.feedmepackages.logistics.ReturnRecovery.confirm(admin,f.handle.cacheId(),java.util.UUID.randomUUID(),true,"inspected")
                ==dev.scathiard.feedmepackages.logistics.ReturnRecovery.Result.STALE,"Wrong handoff receipt allowed");
        h.assertTrue(f.ledger.find(f.handle.cacheId())==f.before,"Refused recovery changed source");
        h.assertTrue(dev.scathiard.feedmepackages.logistics.ReturnRecovery.confirm(admin,f.handle.cacheId(),f.hold.id(),false,"native registration rejected")
                ==dev.scathiard.feedmepackages.logistics.ReturnRecovery.Result.REFUNDED,"Confirmed rejection failed to release cached carrier");
        h.assertTrue(f.ledger.find(f.handle.cacheId())==f.before && AccessGate.resolve(f.player).active(),"Rejection created or deducted cargo");h.succeed();
    }
    @GameTest(template = "empty") public static void rejectedCarrierWaitsForSpaceAndNeverDuplicates(GameTestHelper h) {
        var f=held(h,false);for(int i=0;i<f.player.getInventory().getContainerSize();i++)f.player.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
        f.ledger.rejectReturn(f.hold);ReturnService.resumeRejected(f.player);
        h.assertTrue(f.ledger.returnEscrow(f.handle.cacheId())!=null && f.ledger.find(f.handle.cacheId())==f.before,"Full inventory erased pending carrier");
        f.player.getInventory().setItem(7,ItemStack.EMPTY);ReturnService.resumeRejected(f.player);ReturnService.resumeRejected(f.player);
        h.assertTrue(f.ledger.returnEscrow(f.handle.cacheId())==null && f.player.getInventory().getItem(7).is(Items.PAPER)
                && f.player.getInventory().getItem(7).getCount()==1 && f.ledger.find(f.handle.cacheId())==f.before,"Carrier refund was lost or duplicated");h.succeed();
    }
    @GameTest(template = "empty") public static void corruptReturnRecordLocksLedgerWithoutReplacingStock(GameTestHelper h) {
        var f=held(h,true);var tag=f.ledger.save(new CompoundTag(),f.player.registryAccess());
        tag.getList("return_handoffs",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putInt("amount",9999);
        var bad=CacheLedger.load(tag,f.player.registryAccess());h.assertTrue(!bad.problem().isEmpty()
                && bad.save(new CompoundTag(),f.player.registryAccess()).equals(tag),"Corrupt return was silently dropped");
        f.ledger.cancelRejectedReturn(f.ledger.rejectReturn(f.hold));h.succeed();
    }
}
