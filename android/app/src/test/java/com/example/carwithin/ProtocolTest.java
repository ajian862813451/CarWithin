package com.example.carwithin;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Test public void firstIdrHeaderMatchesCapture() throws Exception {
        byte[] nal=new byte[1500];nal[3]=1;nal[4]=0x27;
        List<byte[]> p=new ArrayList<>();new NinebotProtocol().sendFrame(nal,0,true,false,p::add);
        assertEquals(2,p.size());assertEquals(1400,p.get(0).length);
        assertEquals("90 20 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 11 00 04",NinebotProtocol.hex(p.get(0),20));
        assertEquals(0xa0,p.get(1)[1]&255);assertEquals(9,p.get(1)[17]);
        assertArrayEquals(nal,Avc.join(Arrays.copyOfRange(p.get(0),20,p.get(0).length),Arrays.copyOfRange(p.get(1),20,p.get(1).length)));
    }
    @Test public void timestampExtensionWrapsTenBitsNotAddition() throws Exception {
        List<byte[]> p=new ArrayList<>();new NinebotProtocol().sendFrame(new byte[1],1034,false,false,p::add);
        assertEquals("90 a0 00 00 00 00 04 0a 00 00 00 00 00 00 00 01 00 1a 0a 04",NinebotProtocol.hex(p.get(0),20));
    }
    @Test public void repeatedKeyframeUsesNewSequenceButSameData() throws Exception {
        List<byte[]> p=new ArrayList<>();new NinebotProtocol().sendFrame(new byte[1381],485,true,true,p::add);
        assertEquals(4,p.size());for(int i=0;i<4;i++)assertEquals(i,p.get(i)[3]);
        assertArrayEquals(Arrays.copyOfRange(p.get(0),4,1400),Arrays.copyOfRange(p.get(2),4,1400));
    }
    @Test public void sequenceWrapsAt65536() throws Exception {
        NinebotProtocol n=new NinebotProtocol();List<byte[]> last=new ArrayList<>();
        for(int i=0;i<65537;i++)n.sendFrame(new byte[1],i,false,false,p->{if((p[2]&255)==0&&(p[3]&255)==0)last.add(p);});
        assertEquals(2,last.size());assertEquals(0,last.get(1)[3]);
    }
    @Test public void heartbeatBytesMatchCaptureAndReplyRequiresCorrectSourceId() {
        byte[] p=NinebotProtocol.heartbeat(1);
        assertEquals("85 cc 00 04 00 00 00 00 00 00 00 00 01 00 00 00 02 00 00 00",NinebotProtocol.hex(p,20));
        assertEquals(-1,NinebotProtocol.heartbeatReply(p));p[7]=1;assertEquals(1,NinebotProtocol.heartbeatReply(p));
        p[16]=3;assertEquals(-1,NinebotProtocol.heartbeatReply(p));
        assertEquals(-1,NinebotProtocol.heartbeatReply(new byte[0]));
    }
    @Test public void sevenInchHeartbeatReplyAcceptsCapturedVariants() {
        byte[] reply=Arrays.copyOf(NinebotProtocol.heartbeat(7),24);
        reply[3]=5;reply[7]=1;
        assertEquals(7,NinebotProtocol.heartbeatReply(reply));
        assertEquals(0,NinebotProtocol.kcpConversation(reply));
        reply[20]=3;
        assertEquals(3,NinebotProtocol.kcpConversation(reply));
        assertEquals(7,NinebotProtocol.heartbeatReply(reply));
        reply[7]=0;
        assertEquals(-1,NinebotProtocol.heartbeatReply(reply));
        reply[7]=1;reply[3]=4;
        assertEquals(-1,NinebotProtocol.heartbeatReply(reply));
        reply[3]=5;reply[16]=3;
        assertEquals(-1,NinebotProtocol.heartbeatReply(reply));
    }
    @Test public void avccConvertsToAnnexBAndFindsNalTypes() {
        byte[] b=Avc.annexB(new byte[]{0,0,0,2,0x67,0x42,0,0,0,1,0x68});
        assertArrayEquals(new byte[]{0,0,0,1,0x67,0x42,0,0,0,1,0x68},b);
        assertTrue(Avc.hasNal(b,7));assertTrue(Avc.hasNal(b,8));assertFalse(Avc.hasNal(b,5));
        assertArrayEquals(b,Avc.annexB(b));
    }
    @Test(expected=IllegalArgumentException.class) public void malformedAvccIsRejected() {Avc.annexB(new byte[]{0,0,0,12,0x65});}
    @Test public void kcpPushHeaderMatchesSevenInchCapture() {
        List<byte[]> out=new ArrayList<>();
        Kcp k=new Kcp(3,(d,n)->out.add(Arrays.copyOf(d,n)));
        k.setNodelay(1,10,2,true);
        k.send(new byte[]{0x11,0x22,0x33,0x44});
        k.update(0);
        assertFalse(out.isEmpty());
        byte[] p=out.get(0);
        assertEquals(3,p[0]&255);assertEquals(0,p[1]&255);assertEquals(0,p[2]&255);assertEquals(0,p[3]&255); // conv=3 little-endian
        assertEquals(81,p[4]&255); // cmd PUSH, matches 0x51 in capture
        assertEquals(0,p[5]&255); // frg of a single-segment message
        assertEquals(0,p[12]&255);assertEquals(0,p[13]&255);assertEquals(0,p[14]&255);assertEquals(0,p[15]&255); // sn=0
        assertEquals(0,p[16]&255);assertEquals(0,p[17]&255); // una=0
        assertEquals(4,(p[20]&255)|((p[21]&255)<<8)); // len=4 little-endian
        assertEquals(0x11,p[24]&255);assertEquals(0x44,p[27]&255); // payload carried verbatim
    }
    @Test public void kcpFragmentsRtpAndRetriesUntilAcknowledged() {
        List<byte[]> out=new ArrayList<>();
        Kcp k=new Kcp(4,(d,n)->out.add(Arrays.copyOf(d,n)));
        k.setNodelay(1,10,2,true);k.setMtu(1400);
        byte[] rtp=new byte[1400];rtp[0]=(byte)0x90;
        k.send(rtp);k.update(0);
        assertEquals(2,out.size());
        assertEquals(1400,out.get(0).length);assertEquals(48,out.get(1).length);
        assertEquals(4,out.get(0)[0]);assertEquals(1,out.get(0)[5]);assertEquals(0,out.get(1)[5]);
        byte[] first=out.get(0);
        byte[] ack=new byte[24];ack[0]=4;ack[4]=82;ack[7]=40;ack[12]=1;
        k.input(ack);
        assertEquals(1,k.waitSnd());
        k.update(300);
        assertTrue(out.size()>2);
        assertArrayEquals(Arrays.copyOfRange(first,24,first.length),
            Arrays.copyOfRange(out.get(out.size()-1),24,out.get(out.size()-1).length));
        ack[12]=0;ack[16]=2;k.input(ack);
        assertEquals(0,k.waitSnd());
    }
    @Test public void kcpAckClearsRetransmitQueue() {
        List<byte[]> out=new ArrayList<>();
        Kcp k=new Kcp(3,(d,n)->out.add(Arrays.copyOf(d,n)));
        k.setNodelay(1,10,2,true);
        k.send(new byte[]{1,2,3,4});k.update(0);
        assertEquals(1,k.waitSnd());
        byte[] ack=new byte[24];
        ack[0]=3;ack[4]=(byte)82;ack[16]=1; // conv=3, cmd=ACK, una=1 (sn 0 acknowledged)
        k.input(ack);
        assertEquals(0,k.waitSnd());
    }
    @Test public void exactIdentityRequired() {
        Target t=new Target("DIRECT-9c4hTest","02:00:00:00:00:AB");
        assertTrue(t.matches("DIRECT-9c4hTest","02:00:00:00:00:ab"));
        assertFalse(t.matches("DIRECT-9c4hTest-other","02:00:00:00:00:ab"));
        assertFalse(t.matches("DIRECT-9c4hTest","02:00:00:00:00:ac"));
        assertTrue(t.matchesOwner("other","DIRECT-9c4hTest","02:00:00:00:00:ab"));
        assertFalse(t.matchesOwner("other","DIRECT-9c4hTest","02:00:00:00:00:ac"));
    }
}
