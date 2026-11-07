package dev.scathiard.feedmepackages.domain;

/** r018's reversible display timeline. It never delays use, owns stock or queues selections. */
public final class PalletMorph {
    public static final double DURATION = .3;
    private double from, target, since;
    public void snap(boolean fluid) { from=target=fluid?1:0; since=0; }
    public void target(boolean fluid,double now) {
        double next=fluid?1:0;
        if(next==target) return;
        from=progress(now);target=next;since=now;
    }
    public double progress(double now) {
        if(from==target) return target;
        double step=Math.max(0,now-since)/DURATION;
        return target>from ? Math.min(target,from+step) : Math.max(target,from-step);
    }
    public Pose pose(double now) { return at(progress(now)); }
    public static Pose at(double progress) {
        double q=Double.isFinite(progress)?Math.clamp(progress,0,1):0;
        return new Pose(q,17*ease(q,.12,.48),18.7*(1-ease(q,.55,1)),-90*ease(q,.02,.12));
    }
    public record Pose(double progress,double itemOffset,double fluidOffset,double gate) {
        public boolean itemReady() { return progress==0; }
        public boolean itemVisible() { return progress<.48; }
        public boolean fluidVisible() { return progress>.55; }
        public boolean trayVisible() { return progress<.12; }
    }
    private static double ease(double value,double start,double end) {
        double t=Math.clamp((value-start)/(end-start),0,1);return t*t*(3-2*t);
    }
}
