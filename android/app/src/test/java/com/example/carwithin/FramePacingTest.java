package com.example.carwithin;
import org.junit.Test;
import static org.junit.Assert.*;

public class FramePacingTest {
    @Test public void cadenceFollowsConfiguredFps() {
        assertEquals(33,FramePacing.intervalMs(30));assertEquals(50,FramePacing.intervalMs(20));
        assertEquals(100,FramePacing.intervalMs(10));assertEquals(200,FramePacing.intervalMs(5));
    }
    @Test public void decodingTimeIsSubtractedFromInterval() {
        assertEquals(23,FramePacing.delayMs(100,110,30));
        assertEquals(30,FramePacing.delayMs(100,120,20));
    }
    @Test public void slowDecodingDoesNotQueueCatchupBurst() {
        assertEquals(1,FramePacing.delayMs(100,180,30));
    }
    @Test public void captureFitsOutputWhileCropKeepsOversampling() {
        assertEquals(848,FramePacing.captureEdge(StreamProfile.defaults()));
        assertEquals(1280,FramePacing.captureEdge(new StreamProfile(848,480,30,1500000,0,StreamProfile.Scale.CROP)));
    }
    @Test(expected=IllegalArgumentException.class) public void invalidFpsRejected() {FramePacing.intervalMs(0);}
    @Test public void lateFreshFrameIsNotHeldBehindAnUnnecessaryDuplicate() {
        // Last submit 100ms, next slot 150ms, new video picture arrives at 158ms.
        assertFalse(FramePacing.shouldSubmit(150,150,100,false,20));
        assertTrue(FramePacing.shouldSubmit(158,150,100,true,20));
    }
    @Test public void freshFramesStillRespectConfiguredMaximumRate() {
        assertFalse(FramePacing.shouldSubmit(132,133,100,true,30));
        assertTrue(FramePacing.shouldSubmit(133,133,100,true,30));
    }
    @Test public void staticAndPausedPicturesStillGetPeriodicFrames() {
        assertFalse(FramePacing.shouldSubmit(199,150,100,false,20));
        assertTrue(FramePacing.shouldSubmit(200,150,100,false,20));
        assertFalse(FramePacing.shouldSubmit(499,300,100,false,5));
        assertTrue(FramePacing.shouldSubmit(500,300,100,false,5));
    }
    @Test public void schedulerJitterDoesNotAccumulateIntoLostFrames() {
        long due=0;
        for(int frame=0;frame<20;frame++)due=FramePacing.nextDeadline(due+2,due,20);
        assertEquals(1000,due); // Adding the period to "now" would drift to 1040ms.
    }
    @Test public void longStallReanchorsWithoutCatchupBurst() {
        assertEquals(550,FramePacing.nextDeadline(500,100,20));
    }
    @Test public void slightlyLateFreshFrameKeepsNextRegularSlot() {
        assertEquals(200,FramePacing.nextDeadline(158,150,20));
    }
}
