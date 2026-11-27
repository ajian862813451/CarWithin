package com.example.carwithin;

import java.nio.charset.StandardCharsets;

/** N1 90 Android capture: complete JPEG sliced in RTP, not RFC 2435 or the H.264 extension. */
public final class NinebotJpegProtocol {
    public static final int CHUNK=1400,HEADER=20;
    private final int ssrc;
    private int sequence;
    private long packets,octets;
    public NinebotJpegProtocol(int ssrc){this.ssrc=ssrc;}
    public void sendFrame(byte[] jpeg,long timestamp,NinebotProtocol.Sink sink)throws Exception {
        if(jpeg.length<4 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216 || (jpeg[jpeg.length-2]&255)!=255 || (jpeg[jpeg.length-1]&255)!=217)
            throw new IllegalArgumentException("JPEG首尾标记不完整");
        for(int off=0;off<jpeg.length;off+=CHUNK) {
            int n=Math.min(CHUNK,jpeg.length-off);byte[] p=new byte[HEADER+n];
            p[0]=(byte)0x90;p[1]=(byte)(0x20|(off+n==jpeg.length?0x80:0));
            NinebotProtocol.put16(p,2,sequence++&0xffff);NinebotProtocol.put32(p,4,timestamp);NinebotProtocol.put32(p,8,ssrc);
            p[15]=1;p[17]=1;System.arraycopy(jpeg,off,p,HEADER,n);sink.send(p);packets++;octets+=n;
        }
    }
    public static byte[] heartbeat(int counter) {
        byte[] p=new byte[16];p[0]=(byte)0x85;p[1]=(byte)0xcc;p[3]=3;p[7]=1;
        p[8]='m';p[9]='p';p[10]='v';for(int i=0;i<4;i++)p[12+i]=(byte)(counter>>>(i*8));return p;
    }
    /** Compound SR + BYE, keeping the observed jlibrtp reason; SR carries this sender's actual counts. */
    public byte[] bye(long timestamp,long unixMillis) {
        byte[] reason="jlibrtp says bye bye!".getBytes(StandardCharsets.US_ASCII);
        int byeLength=44;byte[] p=new byte[28+byeLength]; // Captured jlibrtp BYE includes extra zero-filled space.
        p[0]=(byte)0x80;p[1]=(byte)200;p[3]=6;NinebotProtocol.put32(p,4,ssrc);
        NinebotProtocol.put32(p,8,unixMillis/1000+2208988800L);NinebotProtocol.put32(p,12,(unixMillis%1000)*0x100000000L/1000);
        NinebotProtocol.put32(p,16,timestamp);NinebotProtocol.put32(p,20,packets);NinebotProtocol.put32(p,24,octets);
        p[28]=(byte)0x81;p[29]=(byte)203;NinebotProtocol.put16(p,30,byeLength/4-1);NinebotProtocol.put32(p,32,ssrc);
        p[36]=(byte)reason.length;System.arraycopy(reason,0,p,37,reason.length);return p;
    }
}
