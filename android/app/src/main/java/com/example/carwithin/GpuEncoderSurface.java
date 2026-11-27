package com.example.carwithin;

import android.graphics.Bitmap;
import android.opengl.*;
import android.view.Surface;
import java.nio.*;

/** Sender-thread-only Bitmap upload to the encoder. No CPU RGB-to-YUV conversion. */
final class GpuEncoderSurface implements AutoCloseable {
    private final Surface input;
    private final int width,height;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext context=EGL14.EGL_NO_CONTEXT;
    private EGLSurface window=EGL14.EGL_NO_SURFACE;
    private int program,texture,position,uv,sampler;
    private boolean textureReady;
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    GpuEncoderSurface(Surface input,int width,int height) {
        this.input=input;this.width=width;this.height=height;
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions=new int[2];
            require(display!=EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display,versions,0,versions,1),"eglInitialize");
            int[] attributes={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            require(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0,"eglChooseConfig(recordable)");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            require(context!=EGL14.EGL_NO_CONTEXT,"eglCreateContext");
            window=EGL14.eglCreateWindowSurface(display,configs[0],input,new int[]{EGL14.EGL_NONE},0);
            require(window!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context),"eglMakeCurrent");
            String vertex="attribute vec2 aPosition;attribute vec2 aUv;varying vec2 vUv;void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=aUv;}";
            String fragment="precision mediump float;uniform sampler2D uTexture;varying vec2 vUv;void main(){gl_FragColor=texture2D(uTexture,vUv);}";
            int vs=shader(GLES20.GL_VERTEX_SHADER,vertex),fs=0;
            try {
                fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
                program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
                int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
                require(linked[0]!=0,"GL link: "+GLES20.glGetProgramInfoLog(program));
            } finally {GLES20.glDeleteShader(vs);if(fs!=0)GLES20.glDeleteShader(fs);}
            position=GLES20.glGetAttribLocation(program,"aPosition");uv=GLES20.glGetAttribLocation(program,"aUv");
            sampler=GLES20.glGetUniformLocation(program,"uTexture");
            require(position>=0 && uv>=0,"GL attributes");
            int[] names=new int[1];GLES20.glGenTextures(1,names,0);texture=names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            // Bitmap first row is its top. Flip texture coordinates, not the displayed picture.
            vertices.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
            checkGl("setup");
        } catch(RuntimeException e){close();throw e;}
    }
    void submit(Bitmap bitmap,long ptsUs,boolean changed) {
        GLES20.glViewport(0,0,width,height);GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        if(!textureReady){GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);textureReady=true;}
        else if(changed)GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap);
        GLES20.glUniform1i(sampler,0);
        vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(position);
        vertices.position(2);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);checkGl("draw");
        require(EGLExt.eglPresentationTimeANDROID(display,window,ptsUs*1000),"eglPresentationTimeANDROID");
        require(EGL14.eglSwapBuffers(display,window),"eglSwapBuffers");
    }
    private static int shader(int kind,String text) {
        int shader=GLES20.glCreateShader(kind);GLES20.glShaderSource(shader,text);GLES20.glCompileShader(shader);
        int[] compiled=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if(compiled[0]==0){String reason=GLES20.glGetShaderInfoLog(shader);GLES20.glDeleteShader(shader);throw new IllegalStateException("GL shader: "+reason);}
        return shader;
    }
    private static void require(boolean ok,String operation){if(!ok)throw new IllegalStateException(operation+" failed");}
    private static void checkGl(String operation){int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException(operation+" GL=0x"+Integer.toHexString(error));}
    public void close() {
        if(display!=EGL14.EGL_NO_DISPLAY){
            if(context!=EGL14.EGL_NO_CONTEXT && window!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context)){
                if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);
                if(program!=0)GLES20.glDeleteProgram(program);
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglReleaseThread();EGL14.eglTerminate(display);
        }
        window=EGL14.EGL_NO_SURFACE;context=EGL14.EGL_NO_CONTEXT;display=EGL14.EGL_NO_DISPLAY;texture=0;program=0;textureReady=false;
        input.release();
    }
}
