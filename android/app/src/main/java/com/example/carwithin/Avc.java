package com.example.carwithin;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class Avc {
    private Avc() {}
    /** Android encoder output may be Annex-B or 4-byte length-prefixed AVC. Refuse unknown framing. */
    public static byte[] annexB(byte[] bytes) {
        if(bytes.length==0)return bytes;
        if(startLength(bytes,0)>0)return bytes.clone();
        ByteArrayOutputStream out=new ByteArrayOutputStream();int at=0;
        while(at<bytes.length) {
            if(bytes.length-at<4)throw new IllegalArgumentException("H.264 长度前缀不完整");
            long size=((bytes[at]&255L)<<24)|((bytes[at+1]&255L)<<16)|((bytes[at+2]&255L)<<8)|(bytes[at+3]&255L);
            at+=4;
            if(size<=0 || size>bytes.length-at)throw new IllegalArgumentException("未知 H.264 封装");
            out.write(new byte[]{0,0,0,1},0,4);out.write(bytes,at,(int)size);at+=(int)size;
        }
        return out.toByteArray();
    }
    public static byte[] bytes(ByteBuffer b) {ByteBuffer c=b.duplicate();byte[] out=new byte[c.remaining()];c.get(out);return out;}
    public static boolean hasNal(byte[] b,int type) {
        for(int i=0;i<b.length-3;i++) {
            int n=startLength(b,i);
            if(n>0 && i+n<b.length && (b[i+n]&31)==type)return true;
        }
        return false;
    }
    public static byte[] join(byte[] a,byte[] b) {
        byte[] c=new byte[a.length+b.length];System.arraycopy(a,0,c,0,a.length);System.arraycopy(b,0,c,a.length,b.length);return c;
    }
    public static byte[] parameterSet(byte[] data, int type) {
        for (int i = 0; i < data.length - 3; i++) {
            int prefix = startLength(data, i);
            if (prefix == 0 || i + prefix >= data.length || (data[i + prefix] & 31) != type) continue;
            int end = i + prefix + 1;
            while (end < data.length && startLength(data, end) == 0) end++;
            return java.util.Arrays.copyOfRange(data, i, end);
        }
        return null;
    }
    private static int startLength(byte[] b,int i) {
        if(i+2<b.length && b[i]==0 && b[i+1]==0) {
            if(b[i+2]==1)return 3;
            if(i+3<b.length && b[i+2]==0 && b[i+3]==1)return 4;
        }
        return 0;
    }
}
