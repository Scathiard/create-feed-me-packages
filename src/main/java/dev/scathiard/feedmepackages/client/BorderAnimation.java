package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind;

/** Pure row mask: instantaneous light, then either two flashes or a directional dark wipe. */
public final class BorderAnimation {
    private BorderAnimation() {}
    public record Rows(int from, int to, boolean finished) {}
    public static Rows sample(Kind kind, long ageMillis, int height) {
        if (height < 1) throw new IllegalArgumentException("Empty border");
        long age=Math.max(0,ageMillis);
        if (kind == Kind.NONE || age >= (kind == Kind.REQUEST ? 300 : 700)) return new Rows(0,0,true);
        if (kind == Kind.REQUEST) return new Rows(0,age < 100 || age >= 200 ? height : 0,false);
        int dark=(int)(age*height/700);
        return kind == Kind.ARRIVAL ? new Rows(dark,height,false) : new Rows(0,height-dark,false);
    }
}
