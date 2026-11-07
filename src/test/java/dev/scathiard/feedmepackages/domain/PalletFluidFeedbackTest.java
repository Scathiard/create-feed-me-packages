package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PalletFluidFeedbackTest {
    @Test void jetOnlyFollowsSuccessfulUseAndStopsImmediatelyOnSelectionChange() {
        var f=new PalletFluidFeedback();
        assertEquals(0,f.jetRadius(10)); f.success(10);
        assertEquals(0,f.jetRadius(10)); assertTrue(f.jetRadius(10.08)>0);
        f.success(10.09); assertTrue(f.jetRadius(10.12)>0);
        f.cancel(10.12); assertEquals(0,f.jetRadius(10.12));
        assertTrue(f.pose(10.13).active(),"Valve may return, but must not emit the next selected fluid");
        f.success(11); assertEquals(0,f.jetRadius(11.25));
        f.reset(); assertEquals(0,f.jetRadius(11.1));
    }
    @Test void authoredValvePressHoldsThenReturnsWithoutFurtherEvents() {
        var f=new PalletFluidFeedback();
        assertEquals(PalletFluidFeedback.Pose.REST,f.pose(10));
        f.success(10);
        assertFalse(f.pose(10).active());
        assertEquals(.375*.75,f.pose(10.04).middle(),1e-8);
        assertEquals(.375,f.pose(10.08).middle(),1e-8);
        assertEquals(.75,f.pose(10.08).tip(),1e-8);
        assertEquals(.75,f.pose(10.208).tip(),1e-8);
        assertEquals(.375,f.pose(10.224).tip(),1e-8);
        assertEquals(PalletFluidFeedback.Pose.REST,f.pose(10.25));
    }
    @Test void repeatedReceiptsContinueFromCurrentCompressionAndNeverQueue() {
        var f=new PalletFluidFeedback();f.success(0);
        for(double now:new double[]{.03,.05,.08,.12,.20}) {
            double before=f.pose(now).tip();f.success(now);
            assertEquals(before,f.pose(now).tip(),1e-10);
            for(int i=0;i<=24;i++) assertTrue(f.pose(now+i*.01).compression()<=1);
        }
        assertEquals(PalletFluidFeedback.Pose.REST,f.pose(.45));
    }
    @Test void selectionChangeReturnsTheValveSmoothly() {
        var f=new PalletFluidFeedback();f.success(0);double before=f.pose(.06).tip();f.cancel(.06);
        assertEquals(before,f.pose(.06).tip(),1e-10);
        assertTrue(f.pose(.09).tip()>0 && f.pose(.09).tip()<before);
        assertEquals(PalletFluidFeedback.Pose.REST,f.pose(.13));
        f.success(.2);f.reset();assertEquals(PalletFluidFeedback.Pose.REST,f.pose(.23));
    }
}
