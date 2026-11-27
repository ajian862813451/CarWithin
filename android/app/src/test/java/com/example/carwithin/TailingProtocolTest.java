package com.example.carwithin;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TailingProtocolTest {
    @Test public void auxiliaryReconfigureAndHeartbeatRemainFramed()throws Exception {
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        stream.write(TailingProtocol.auxiliary(16,7,new byte[204]));
        stream.write(TailingProtocol.auxiliary(112,8,null));
        stream.write(TailingProtocol.auxiliary(64,9,null));
        stream.write(TailingProtocol.auxiliary(16,10,new byte[376]));
        stream.write(TailingProtocol.auxiliary(112,11,null));
        InputStream in=new ByteArrayInputStream(stream.toByteArray()){@Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(n,3));}};
        TailingProtocol.Auxiliary a=TailingProtocol.readAuxiliary(in);assertEquals(16,a.command());assertEquals(7,a.token());assertEquals(204,a.payload().length);
        assertEquals(112,TailingProtocol.readAuxiliary(in).command());assertEquals(64,TailingProtocol.readAuxiliary(in).command());
        a=TailingProtocol.readAuxiliary(in);assertEquals(16,a.command());assertEquals(10,a.token());assertEquals(376,a.payload().length);
        assertEquals(112,TailingProtocol.readAuxiliary(in).command());assertEquals(-1,in.read());
    }
    @Test public void auxiliaryHeaderHasVariablePayloadLength()throws Exception {
        assertEquals(204,TailingProtocol.auxiliarySize(new byte[]{16,0,(byte)204,0,0,0,0,0}));
        assertEquals(376,TailingProtocol.auxiliarySize(new byte[]{16,0,120,1,0,0,0,0}));
    }
    @Test public void capturedHeartbeatIsExactAndStreamsCanFragment()throws Exception {
        byte[] captured={0,0,0,112,16,0,0,0,16,0,0,112,0,0,0,0};
        assertArrayEquals(captured,TailingProtocol.message(0x70000000,null));
        InputStream fragmented=new ByteArrayInputStream(captured){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(1,len));}};
        assertEquals(0x70000000,TailingProtocol.read(fragmented).command());
    }
    @Test public void coalescedControlFramesStaySeparate()throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(TailingProtocol.message(0x10000,null));out.write(TailingProtocol.message(0x10010,"{}"));
        InputStream in=new ByteArrayInputStream(out.toByteArray());assertEquals(0x10000,TailingProtocol.read(in).command());assertArrayEquals(new byte[]{123,125},TailingProtocol.read(in).payload());assertEquals(-1,in.read());
    }
    @Test(expected=IOException.class) public void corruptLengthFailsBeforeAllocating()throws Exception {
        byte[] p=TailingProtocol.message(1,null);p[7]=127;TailingProtocol.read(new ByteArrayInputStream(p));
    }
    @Test(expected=EOFException.class) public void partialHeaderFails()throws Exception {TailingProtocol.read(new ByteArrayInputStream(new byte[10]));}
    @Test public void videoUsesLengthPrefixAndExactFeedback()throws Exception {
        byte[] jpeg={(byte)255,(byte)216,1,2,(byte)255,(byte)217};ByteArrayOutputStream out=new ByteArrayOutputStream();TailingProtocol.writeJpeg(out,jpeg);
        assertArrayEquals(new byte[]{6,0,0,0},Arrays.copyOf(out.toByteArray(),4));assertArrayEquals(jpeg,Arrays.copyOfRange(out.toByteArray(),4,10));
        assertTrue(TailingProtocol.feedback(new byte[]{114,0,0,0,0,0,0,0}));assertFalse(TailingProtocol.feedback(new byte[]{114,0,0,0,0,0,0,1}));
    }
    @Test public void distinctJpegProtocolsAndLegacyProfilesPersist() {
        StreamProfile p=StreamProfile.tailing43();assertEquals(p,StreamProfile.decode(p.encode()));assertNotEquals(p,StreamProfile.jpeg43());
        assertEquals(StreamProfile.Transport.DIRECT,StreamProfile.decode("480,272,10,1500000,0,0,1,80").transport());
        assertEquals(StreamProfile.Transport.KCP,StreamProfile.decode("1024,600,25,2000000,0,0,0,80,1").transport());
        Target t=new Target("TL-DASH-000001","02:00:00:00:10:01");
        assertFalse(t.matches("TL-DASH-000002","02:00:00:00:10:02"));assertFalse(DeviceCatalog.tailingCandidate("TL-DASH-"));
    }
    @Test(expected=IllegalArgumentException.class) public void tailingCannotUseH264() {
        new StreamProfile(480,272,20,1500000,0,StreamProfile.Scale.FIT,StreamProfile.Codec.H264,80,StreamProfile.Transport.TAILING_TCP);
    }
}
