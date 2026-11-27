package com.example.carwithin;

import java.util.Arrays;

/** Wire format derived from the user's successful iOS capture, not a generic RFC 6184 packetizer. */
public final class NinebotProtocol {
    public static final int VIDEO_PORT=5012, CONTROL_PORT=5013, HEADER=20, CHUNK=1380;
    private int sequence;
    public interface Sink { void send(byte[] packet) throws Exception; }
    public int sequence() { return sequence; }
    public void sendFrame(byte[] annexB, long timestampMs, boolean key, boolean repeatKey, Sink sink) throws Exception {
        if (annexB.length==0) return;
        for (int copy=0;copy<(key&&repeatKey?2:1);copy++) {
            for (int off=0;off<annexB.length;off+=CHUNK) {
                int size=Math.min(CHUNK,annexB.length-off);
                boolean first=off==0, last=off+size==annexB.length;
                byte[] p=new byte[HEADER+size];
                p[0]=(byte)0x90;p[1]=(byte)(0x20|(last?0x80:0));
                put16(p,2,sequence++ & 0xffff); put32(p,4,timestampMs);
                p[15]=1;
                p[17]=(byte)((key?1:2)|(first?0x10:0)|(last?8:0));
                int ext=((int)timestampMs&0x3ff)|0x400;
                p[18]=(byte)ext;p[19]=(byte)(ext>>8);
                System.arraycopy(annexB,off,p,HEADER,size);sink.send(p);
            }
        }
    }
    public static byte[] heartbeat(int counter) {
        byte[] p=new byte[20];p[0]=(byte)0x85;p[1]=(byte)0xcc;p[3]=4;
        for(int i=0;i<4;i++)p[12+i]=(byte)(counter>>>(8*i));
        p[16]=2;return p;
    }
    public static byte[] bye() { return new byte[]{(byte)0x80,(byte)0xcb,0,0}; }
    /** Match a full subtype-5 reply, not arbitrary UDP traffic. Source endpoint is checked by connected socket. */
    public static int heartbeatReply(byte[] p) {
        if((p.length!=20 && p.length!=24) || (p[0]&255)!=0x85 || (p[1]&255)!=0xcc || p[2]!=0 || p[3]!=(p.length==24?5:4)
            || p[4]!=0 || p[5]!=0 || p[6]!=0 || p[7]!=1
            || p[8]!=0 || p[9]!=0 || p[10]!=0 || p[11]!=0
            || p[16]!=2 || p[17]!=0 || p[18]!=0 || p[19]!=0)return -1;
        return (p[12]&255)|((p[13]&255)<<8)|((p[14]&255)<<16)|((p[15]&255)<<24);
    }
    public static long kcpConversation(byte[] reply) {
        if(reply.length!=24 || heartbeatReply(reply)<0)throw new IllegalArgumentException("七寸心跳格式无效");
        return (reply[20]&255L)|((reply[21]&255L)<<8)|((reply[22]&255L)<<16)|((reply[23]&255L)<<24);
    }
    static void put16(byte[] p,int i,int n) {p[i]=(byte)(n>>>8);p[i+1]=(byte)n;}
    static void put32(byte[] p,int i,long n) {for(int k=0;k<4;k++)p[i+k]=(byte)(n>>>(24-k*8));}
    public static String hex(byte[] p,int max) {
        StringBuilder b=new StringBuilder();for(byte x:Arrays.copyOf(p,Math.min(max,p.length))) b.append(String.format("%02x ",x&255));return b.toString().trim();
    }
}
