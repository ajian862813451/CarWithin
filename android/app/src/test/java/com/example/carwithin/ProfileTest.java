package com.example.carwithin;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileTest {
    @Test public void experimentalNinebot43AndTailingHaveIndependentProfiles() {
        StreamProfile archived=StreamProfile.jpeg43();String stored=archived.encode();
        assertEquals(archived,StreamProfile.decode(stored));assertEquals(stored,StreamProfile.decode(stored).encode());
        assertEquals(StreamProfile.NINEBOT_JPEG43_ENABLED,archived.isAvailable());assertTrue(StreamProfile.tailing43().isAvailable());
        assertTrue(StreamProfile.defaults().isAvailable());assertTrue(StreamProfile.sevenInch().isAvailable());
        assertTrue(StreamProfile.sevenInch().label().contains("（实验）"));
    }
    @Test public void h264PresetsShareFrameRateAndBitrate() {
        StreamProfile five = StreamProfile.defaults();
        StreamProfile seven = StreamProfile.sevenInch();
        assertEquals(new StreamProfile(848,480,20,2000000,0,StreamProfile.Scale.FIT),five);
        assertEquals(new StreamProfile(1024,600,25,2000000,0,StreamProfile.Scale.FIT,StreamProfile.Codec.H264,80,StreamProfile.Transport.KCP),seven);
        assertEquals(StreamProfile.Transport.DIRECT,five.transport());
        assertEquals(StreamProfile.Transport.KCP,seven.transport());
        assertEquals(31,seven.avcLevel());
    }
    @Test public void transportSurvivesEncodeRoundTripAndLegacyConfigsStayDirect() {
        StreamProfile seven=StreamProfile.sevenInch();
        assertEquals(seven,StreamProfile.decode(seven.encode()));
        assertEquals(StreamProfile.Transport.DIRECT,StreamProfile.decode("848,480,20,2000000,0,0").transport());
        assertEquals(StreamProfile.Transport.DIRECT,StreamProfile.decode("848,480,20,2000000,0,0,0,80").transport());
    }

    @Test public void h264LevelAccountsForFrameRateAndMacroblockPadding() {
        assertFalse(StreamProfile.defaults().requiresLevel4());
        assertEquals(30,StreamProfile.defaults().avcLevel());
        assertEquals(30,new StreamProfile(848,480,20,1500000,0,StreamProfile.Scale.FIT).avcLevel());
        assertEquals(31,new StreamProfile(848,480,30,1500000,0,StreamProfile.Scale.FIT).avcLevel());
        assertTrue(new StreamProfile(1920,1080,20,3000000,0,StreamProfile.Scale.FIT).requiresLevel4());
    }
    @Test public void portraitFitsWithoutStretching() {
        assertArrayEquals(new float[]{264,0,584,480},StreamProfile.defaults().rect(640,960),.01f);
    }
    @Test public void cropFillsAndExtendsPastBounds() {
        StreamProfile p=new StreamProfile(848,480,20,1500000,0,StreamProfile.Scale.CROP);
        assertArrayEquals(new float[]{0,-396,848,876},p.rect(640,960),.01f);
    }
    @Test public void rotationChangesAspectBeforeFitting() {
        StreamProfile p=new StreamProfile(848,480,20,1500000,90,StreamProfile.Scale.FIT);
        assertArrayEquals(new float[]{64,0,784,480},p.rect(640,960),.01f);
    }
    @Test public void stretchUsesWholeCanvas() {
        StreamProfile p=new StreamProfile(800,480,10,1000000,0,StreamProfile.Scale.STRETCH);
        assertArrayEquals(new float[]{0,0,800,480},p.rect(640,960),.01f);
    }
    @Test(expected=IllegalArgumentException.class) public void oddSizeRejected(){new StreamProfile(849,480,20,1500000,0,StreamProfile.Scale.FIT);}
    @Test(expected=IllegalArgumentException.class) public void excessivePixelsRejected(){new StreamProfile(1920,1920,20,1500000,0,StreamProfile.Scale.FIT);}
    @Test public void candidateHintDoesNotImplyIdentityOrResolution() {
        assertTrue(DeviceCatalog.ninebotCandidate("DIRECT-9c4hTest"));
        assertTrue(DeviceCatalog.ninebotCandidate("DIRECT-9c4hOther"));
        assertFalse(DeviceCatalog.ninebotCandidate("DIRECT-9c4h"));
        assertFalse(DeviceCatalog.ninebotCandidate("DIRECT-YD001"));
        Target bound=new Target("DIRECT-9c4hTest","02:00:00:00:00:ab");
        assertFalse(bound.matches("DIRECT-9c4hOther","02:00:00:00:00:ac"));
    }
}
