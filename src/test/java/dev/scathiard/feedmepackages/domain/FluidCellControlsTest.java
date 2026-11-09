package dev.scathiard.feedmepackages.domain;

import dev.scathiard.feedmepackages.client.CellControlDraft;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidCellControlsTest {
    @Test void partialBucketShortageAndPendingStillUseExactMillibuckets() {
        var ordinary = new CellSettings(8000,8000,1,-1,1);
        assertEquals(1,ordinary.requestable(7999,0));
        var batches = new CellSettings(8000,16000,8000,-1,8000);
        assertEquals(8000,batches.requestable(7999,0));
        assertEquals(0,batches.requestable(7999,1));
        assertEquals(0,batches.returnable(15999,15999,10000,0));
        assertEquals(8000,batches.returnable(24000,24000,10000,0));
        assertEquals(0,batches.returnable(24000,7999,10000,0));
    }
    @Test void packageCapacityControlsTheEditableLimitWithoutRoundingStoredLiquid() {
        assertEquals(10000,FluidCellControls.maximum(10000));
        assertEquals(9000,FluidCellControls.maximum(9500));
        assertEquals(1,FluidCellControls.maximum(999));
        assertEquals(1,FluidCellControls.maximum(0));
        var s=new CellSettings(-1,-1,8000,-1,8000);
        assertFalse(FluidCellControls.accepts(new CellSettings(-1,-1,11000,-1,8000),s,10000));
        assertFalse(FluidCellControls.accepts(new CellSettings(-1,-1,1001,-1,8000),s,10000));
        assertTrue(FluidCellControls.accepts(new CellSettings(-1,-1,10000,-1,8000),s,10000));
    }
    @Test void smallerTransportConfigurationPreservesOldSettingsAndAllowsIndependentReduction() {
        var old=new CellSettings(1000,9000,8000,-1,8000);
        assertTrue(FluidCellControls.accepts(old,old,4000));
        var first=new CellSettings(1000,9000,4000,-1,8000);
        assertTrue(FluidCellControls.accepts(first,old,4000));
        assertTrue(FluidCellControls.accepts(new CellSettings(1000,9000,4000,-1,4000),first,4000));
        assertFalse(FluidCellControls.accepts(new CellSettings(1000,9000,5000,-1,8000),old,4000));
    }
    @Test void scrollUsesBucketAndGroupAlignmentButCanReturnToLegacyDefault() {
        assertEquals(1000,CellControlDraft.wheel(1,1,1000,1,10000,false));
        assertEquals(1,CellControlDraft.wheel(1000,-1,1000,1,10000,false));
        assertEquals(8000,CellControlDraft.wheel(3000,1,8000,1,10000,false));
        assertEquals(8000,CellControlDraft.wheel(9000,-1,8000,1,10000,false));
        assertEquals(10000,CellControlDraft.wheel(8000,1,8000,1,10000,false));
    }
    @Test void linkedEndpointsStayOnWholeBucketsAndNeverDisableAStockDirection() {
        var near=new CellSettings(6000,8000,1,-1,1);
        var added=CellControlDraft.adjust(near,0,8000,16000,1000);
        assertEquals(new CellSettings(6000,14000,8000,-1,1),added);
        var lowEdge=CellControlDraft.adjust(new CellSettings(6000,8000,2000,-1,1),1,8000,16000,1000);
        assertEquals(new CellSettings(1000,8000,2000,-1,7000),lowEdge);
        assertFalse(lowEdge.conflicting());
        assertEquals(1000,CellControlDraft.adjust(lowEdge,2,7000,16000,1000).lower());
        assertEquals(8000,CellControlDraft.adjust(lowEdge,3,1000,16000,1000).upper());
        assertFalse(FluidCellControls.accepts(new CellSettings(1000,1000,1000,-1,1),near,10000));
    }
    @Test void largeFluidPackageSettingsAreNotBoundToNineItemStacks() {
        MaterialVariant fluid=new MaterialVariant() {
            public int stackSize(){return 8000;}
            public int batchLimit(){return Integer.MAX_VALUE;}
        };
        assertDoesNotThrow(()->new CellSettings(-1,-1,80000,-1,80000).validate(fluid,256000));
        assertThrows(IllegalArgumentException.class,()->new CellSettings(-1,-1,577,-1,1).validate(()->64,4096));
    }
}
