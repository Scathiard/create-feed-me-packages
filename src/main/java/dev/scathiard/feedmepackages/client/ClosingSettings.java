package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import java.util.*;

/** Bounded receipts survive screen close, never replay a draft into a later window. */
public final class ClosingSettings {
    public enum State { PENDING, APPLIED, REJECTED, UNCONFIRMED }
    public record Submission(PanelPackets.FinalSettings request,int tick,State state,CacheActions.Result result) {}
    private final LinkedHashMap<UUID,Submission> recent=new LinkedHashMap<>();
    public boolean begin(PanelPackets.FinalSettings request,int tick) {
        var id=request.settings().window();if(recent.containsKey(id))return false;
        recent.put(id,new Submission(request,tick,State.PENDING,null));
        while(recent.size()>4)recent.remove(recent.keySet().iterator().next());
        return true;
    }
    public void accept(PanelPackets.FinalSettingsReceipt reply) {
        var s=recent.get(reply.window());if(s==null)return;
        var r=s.request().settings();
        if(r.session().equals(reply.session()) && r.sequence()==reply.sequence())recent.put(reply.window(),
                new Submission(s.request(),s.tick(),reply.result()==CacheActions.Result.OK?State.APPLIED:State.REJECTED,reply.result()));
    }
    public void tick(int tick) {
        recent.replaceAll((id,s)->s.state()==State.PENDING && (long)tick-s.tick()>100
                ? new Submission(s.request(),s.tick(),State.UNCONFIRMED,null):s);
    }
    public Submission get(UUID window){return recent.get(window);}
    public void clear(){recent.clear();}
}
