package com.example.carwithin;

import android.graphics.*;
import java.util.Locale;

public final class TestPattern {
    public static final int WIDTH=848,HEIGHT=480,FPS=20;
    private static final int[] COLORS={0xffeaeaea,0xffffff00,0xff00ffff,0xff00dd33,0xffff00ff,0xffff3030,0xff3030ff};
    public static void draw(Canvas canvas,Paint paint,int frame) { draw(canvas,paint,frame,20); }
    public static void draw(Canvas canvas,Paint paint,int frame,int fps) {
        canvas.drawColor(0xff101a2b);
        for(int i=0;i<COLORS.length;i++) {
            paint.setColor(COLORS[i]);canvas.drawRect(i*WIDTH/7f,0,(i+1)*WIDTH/7f,130,paint);
        }
        paint.setColor(Color.WHITE);paint.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));
        paint.setTextSize(43);canvas.drawText("CARWITHIN",32,204,paint);
        paint.setTextSize(25);canvas.drawText("H.264  |  TARGET "+fps+" FPS",32,254,paint);
        paint.setTextSize(33);canvas.drawText(String.format(Locale.US,"FRAME %06d  TIME %05.2fs",frame,frame/(double)fps),32,320,paint);
        paint.setColor(0xff29d5a1);float x=32+(frame*9)%700;
        canvas.drawRoundRect(x,360,x+76,415,12,12,paint);
        paint.setColor(Color.WHITE);paint.setTextSize(20);canvas.drawText("P2P / UDP 5012 + RTCP 5013",32,458,paint);
    }
}
