package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.LogisticsSignal;
import dev.scathiard.feedmepackages.network.PanelPackets;
import net.minecraft.client.gui.GuiGraphics;
import java.util.*;

/** Only accepted snapshots can start a pulse; inventory quantity changes alone never do. */
final class CellFeedback {
    private CellFeedback() {}
    private record Pulse(LogisticsSignal.Kind kind,long started) {}
    private static final Map<Integer,Pulse> ACTIVE=new HashMap<>();
    static void clear() { ACTIVE.clear(); }
    static void accept(PanelPackets.Snapshot before,PanelPackets.Snapshot after,long now) {
        if (before==null || !before.window().equals(after.window()) || !Objects.equals(before.session(),after.session())
                || before.cells().size()!=after.cells().size()) { clear();return; }
        for(int i=0;i<after.cells().size();i++) {
            if (!LogisticsPanel.feedbackVisible(i)) { ACTIVE.remove(i); continue; }
            var old=before.cells().get(i);var cell=after.cells().get(i);
            if(!old.template().equals(cell.template()) || cell.signal().sequence()<old.signal().sequence()) ACTIVE.remove(i);
            else if(cell.signal().sequence()>old.signal().sequence()) ACTIVE.put(i,new Pulse(cell.signal().kind(),now));
        }
    }
    static void render(GuiGraphics g,int slot,int x,int y) {
        var pulse=ACTIVE.get(slot);if(pulse==null)return;
        var rows=BorderAnimation.sample(pulse.kind(),net.minecraft.Util.getMillis()-pulse.started(),18);
        if(rows.finished()) { ACTIVE.remove(slot);return; }
        CellBorders.texture(g,x,y,18,70,rows.from(),rows.to());
    }
}
