package dev.scathiard.feedmepackages.client;

/** Shared GUI-pixel geometry for artwork, mouse routing and tests. */
public final class CellControlGeometry {
    private CellControlGeometry() {}
    public static final int WIDTH = 144, HEIGHT = 57, TRACK_X = 36, TRACK_Y = 37, SPAN = 71;
    public static PanelLayout.Rect field(PanelLayout.Rect r, int field) {
        return switch (field) {
            case 0 -> new PanelLayout.Rect(r.x()+8, r.y()+8, 48, 18);
            case 1 -> new PanelLayout.Rect(r.x()+88, r.y()+8, 48, 18);
            case 2 -> new PanelLayout.Rect(r.x()+8, r.y()+31, 24, 18);
            case 3 -> new PanelLayout.Rect(r.x()+112, r.y()+31, 24, 18);
            case 4 -> new PanelLayout.Rect(r.x()+63, r.y()+8, 18, 18);
            default -> throw new IllegalArgumentException("Unknown control");
        };
    }
    public static int thumb(PanelLayout.Rect r, int value, int capacity) {
        int clamped = Math.clamp((long)(value < 0 ? capacity : value), 0, Math.max(1,capacity));
        return r.x()+TRACK_X+(int)Math.round((double)clamped*SPAN/Math.max(1,capacity));
    }
    public static int draggedValue(int start, double delta, int capacity) {
        return (int)Math.clamp(Math.round(start + delta * capacity / SPAN), 0, capacity);
    }
    public static int draggedValue(int start, double delta, int capacity, int stackSize) {
        if (stackSize <= 1) return draggedValue(start, delta, capacity);
        double raw = Math.clamp(start + delta * capacity / SPAN, 0, capacity);
        long group = Math.round(raw / stackSize) * stackSize;
        // A small plateau, never an entire step: at most one GUI pixel or 1/8 stack.
        double radius = Math.min((double) capacity / SPAN, stackSize / 8d);
        // Endpoints switch logistics off. Only deliberate travel to the edge may reach them.
        if (group > 0 && group < capacity && Math.abs(raw - group) <= radius) return (int) group;
        return (int) Math.round(raw);
    }
}
