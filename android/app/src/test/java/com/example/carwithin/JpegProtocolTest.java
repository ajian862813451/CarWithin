package com.example.carwithin;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.Assert.*;

public class JpegProtocolTest {
    @Test public void captureHeaderAndReassemblyDoNotUseH264Flags()throws Exception {
        byte[] jpg=new byte[2805];new Random(3).nextBytes(jpg);jpg[0]=(byte)255;jpg[1]=(byte)216;jpg[2803]=(byte)255;jpg[2804]=(byte)217;
        List<byte[]> ps=new ArrayList<>();new NinebotJpegProtocol(0x6d9c3fc3).sendFrame(jpg,0xc464f246L,ps::add);
        assertEquals(3,ps.size());assertEquals(1420,ps.get(0).length);
        byte[] expected={(byte)0x90,0x20,0,0,(byte)0xc4,0x64,(byte)0xf2,0x46,0x6d,(byte)0x9c,0x3f,(byte)0xc3,0,0,0,1,0,1,0,0};
        assertArrayEquals(expected,Arrays.copyOf(ps.get(0),20));
        assertEquals(0xa0,ps.get(2)[1]&255);assertEquals(1,ps.get(2)[17]);
        ByteArrayOutputStream recovered=new ByteArrayOutputStream();for(byte[] p:ps)recovered.write(p,20,p.length-20);assertArrayEquals(jpg,recovered.toByteArray());
    }
    @Test public void heartbeatMatchesCapturedMpvAndZeroCounterReply() {
        assertArrayEquals(new byte[]{(byte)0x85,(byte)0xcc,0,3,0,0,0,1,'m','p','v',0,0,0,0,0},NinebotJpegProtocol.heartbeat(0));
        byte[] reply={(byte)0x85,(byte)0xcc,0,4,0,0,0,1,0,0,0,0,0,0,0,0,2,0,0,0};assertEquals(0,NinebotProtocol.heartbeatReply(reply));
        assertEquals(0x78,NinebotJpegProtocol.heartbeat(0x12345678)[12]&255);
    }
    @Test public void compoundByeContainsSenderAndCapturedReason() {
        byte[] p=new NinebotJpegProtocol(0x12345678).bye(9,0);
        assertEquals(72,p.length);assertEquals(200,p[1]&255);assertEquals(203,p[29]&255);assertEquals(10,p[31]);
        assertArrayEquals(new byte[]{0x12,0x34,0x56,0x78},Arrays.copyOfRange(p,32,36));assertEquals(21,p[36]);
    }
    @Test public void savedCodecRoundTripAndLegacyMigration() {
        StreamProfile p=StreamProfile.jpeg43();assertEquals(p,StreamProfile.decode(p.encode()));
        StreamProfile old=StreamProfile.decode("848,480,20,8000000,90,1");assertEquals(StreamProfile.Codec.H264,old.codec());assertEquals(8000000,old.bitrate());assertEquals(90,old.rotation());
    }
    @Test(expected=IllegalArgumentException.class) public void brokenJpegRejected()throws Exception{new NinebotJpegProtocol(1).sendFrame(new byte[4],0,p->{});}
    @Test public void sequenceWrapsAcrossFrames()throws Exception {
        NinebotJpegProtocol protocol=new NinebotJpegProtocol(1);List<byte[]> last=new ArrayList<>();byte[] jpg={(byte)255,(byte)216,(byte)255,(byte)217};
        for(int i=0;i<65536;i++)protocol.sendFrame(jpg,i,p->{});protocol.sendFrame(jpg,65536,last::add);assertEquals(0,last.get(0)[2]);assertEquals(0,last.get(0)[3]);
    }
}
