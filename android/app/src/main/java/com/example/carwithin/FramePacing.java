package com.example.carwithin;

/** Shared cadence subtracts processing time, without queueing catch-up work. */
public final class FramePacing {
    private FramePacing() {}
    public static long intervalMs(int fps) {
        if(fps<5 || fps>30)throw new IllegalArgumentException("FPS须为5–30");
        return Math.round(1000.0/fps);
    }
    public static long delayMs(long start,long end,int fps) {
        return Math.max(1,intervalMs(fps)-Math.max(0,end-start));
    }
    /** Leave an idle slot for a late fresh frame instead of filling it with stale content. */
    public static boolean shouldSubmit(long now,long nextDue,long lastSubmitted,boolean fresh,int fps) {
        long repeatAfter=Math.max(100,intervalMs(fps)*2);
        return now>=nextDue && (fresh || lastSubmitted<0 || now-lastSubmitted>=repeatAfter);
    }
    /** Small scheduler jitter keeps the cadence grid; a long stall starts a new grid. */
    public static long nextDeadline(long now,long due,int fps) {
        long interval=intervalMs(fps);
        return now-due>interval/2?now+interval:due+interval;
    }
    public static int captureEdge(StreamProfile profile) {
        return profile.scale()==StreamProfile.Scale.CROP?1280:Math.min(1280,Math.max(640,Math.max(profile.width(),profile.height())));
    }
}
