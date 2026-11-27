// Derived from CarNine (c) 2026 xiaolu5hao, Apache-2.0. Modified for CarWithin. See bundled notices.
package com.example.carwithin.vendor.media;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * CarLife 车机侧收到的是标准 Annex-B H.264 字节流（首帧前带 SPS/PPS）。
 *
 * <p>这里用 <b>同步 MediaCodec + ByteBuffer 输出</b>（不依赖 Surface），把每帧解成 YUV，
 * 自己转成 {@link Bitmap} 交给界面绘制。之所以不用 Surface：
 * 之前直接渲染到 SurfaceView 时，屏幕全黑（Surface 在布局变化后失效而 MediaCodec 静默丢帧），
 * 软件路径能保证画面一定画得出来，而且顺便能算出平均亮度，用来判断“手机到底有没有在发画面”。
 */
public final class H264Decoder {

    public interface Listener {
        void onDecoderReady(int width, int height);

        void onFrameRendered(long totalFrames, int luminance);

        /** 解好的一帧（ARGB_8888）。注意：Bitmap 会被复用，绘制方必须立即拷贝或同步绘制。 */
        void onBitmap(Bitmap bitmap);

        void onDecoderError(String message);
    }

    private static final int COLOR_FormatYUV420Flexible = 0x7F420888;

    private final Listener listener;

    private MediaCodec codec;
    private MediaCodec.BufferInfo bufferInfo;
    private boolean started;
    private boolean released;
    private int width;
    private int height;

    private byte[] sps;
    private byte[] pps;

    private int[] argb;
    private Bitmap bitmap;
    private long frameIndex;
    private int lastLuma = -1;

    public H264Decoder(Listener listener) {
        this.listener = listener;
    }

    public boolean isStarted() {
        return started;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getLastLuma() {
        return lastLuma;
    }

    /** 送一帧（access unit）进来解码。 */
    public void feed(byte[] data, int offset, int length, long ptsUs, int fallbackWidth, int fallbackHeight) {
        if (released || data == null || length <= 0) {
            return;
        }
        try {
            if (!started) {
                if (!scanConfig(data, offset, length)) {
                    return; // 还没拿到 SPS/PPS：先丢帧
                }
                if (!startDecoder(fallbackWidth, fallbackHeight)) {
                    return;
                }
            }
            queue(data, offset, length, ptsUs);
            drain();
        } catch (Throwable t) {
            listener.onDecoderError("解码异常：" + t);
            release();
        }
    }

    // ------------------------------------------------------------ 初始化

    private boolean scanConfig(byte[] b, int off, int len) {
        int end = off + len - 4;
        for (int i = off; i < end; i++) {
            int nalStart;
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                nalStart = i + 3;
            } else if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                nalStart = i + 4;
            } else {
                continue;
            }
            if (nalStart >= off + len) {
                break;
            }
            int type = b[nalStart] & 0x1F;
            if (type == 7 && sps == null) {
                sps = sliceWithStartCode(b, i, nalStart, off + len);
            } else if (type == 8 && pps == null) {
                pps = sliceWithStartCode(b, i, nalStart, off + len);
            }
            if (sps != null && pps != null) {
                return true;
            }
        }
        return sps != null && pps != null;
    }

    /** 从起始码到下一个起始码之前的这段 NAL（含起始码）。 */
    private static byte[] sliceWithStartCode(byte[] b, int startCodeAt, int nalAt, int end) {
        int next = end;
        for (int i = nalAt + 1; i < end - 3; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && (b[i + 2] == 1 || (b[i + 2] == 0 && b[i + 3] == 1))) {
                next = i;
                break;
            }
        }
        byte[] out = new byte[next - startCodeAt];
        System.arraycopy(b, startCodeAt, out, 0, out.length);
        return out;
    }

    private boolean startDecoder(int fallbackWidth, int fallbackHeight) {
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc",
                    Math.max(16, fallbackWidth), Math.max(16, fallbackHeight));
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            codec.configure(format, null, null, 0);
            codec.start();
            bufferInfo = new MediaCodec.BufferInfo();
            started = true;
            width = fallbackWidth;
            height = fallbackHeight;
            listener.onDecoderReady(fallbackWidth, fallbackHeight);
            return true;
        } catch (Throwable t) {
            listener.onDecoderError("解码器启动失败：" + t);
            release();
            return false;
        }
    }

    // ------------------------------------------------------------ 解码

    private void queue(byte[] data, int off, int len, long ptsUs) {
        int index = codec.dequeueInputBuffer(8000);
        if (index < 0) {
            drain();
            index = codec.dequeueInputBuffer(40000);
        }
        if (index < 0) {
            throw new IllegalStateException("解码器输入缓冲区超时");
        }
        ByteBuffer in = codec.getInputBuffer(index);
        if (in == null || in.capacity() < len) {
            throw new IllegalStateException("CarLife 视频帧大于解码器输入缓冲区");
        }
        in.clear();
        in.put(data, off, len);
        codec.queueInputBuffer(index, 0, len, ptsUs, 0);
    }

    private void drain() {
        while (true) {
            int out = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                width = f.getInteger(MediaFormat.KEY_WIDTH);
                height = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-bottom")) {
                    width = f.getInteger("crop-right") - (f.containsKey("crop-left") ? f.getInteger("crop-left") : 0) + 1;
                    height = f.getInteger("crop-bottom") - (f.containsKey("crop-top") ? f.getInteger("crop-top") : 0) + 1;
                }
                android.util.Log.d("CarNine/H264", "输出画布 " + width + "x" + height
                        + " color-format=" + f.getInteger(MediaFormat.KEY_COLOR_FORMAT));
                continue;
            }
            if (out < 0) {
                return;
            }
            if (bufferInfo.size > 0) {
                render(out);
                frameIndex++;
                listener.onFrameRendered(frameIndex, lastLuma);
            }
            codec.releaseOutputBuffer(out, false);
        }
    }

    /** 把 YUV 输出转成 Bitmap（复用同一个 Bitmap，避免每帧分配）。 */
    private void render(int index) {
        Image image = null;
        try {
            image = codec.getOutputImage(index);
        } catch (Throwable ignored) {
        }
        if (image == null) {
            return;
        }
        try {
            android.graphics.Rect crop = image.getCropRect();
            int w = crop.width();
            int h = crop.height();
            ensureBitmap(w, h);
            Image.Plane[] planes = image.getPlanes();
            Image.Plane yp = planes[0];
            Image.Plane up = planes[1];
            Image.Plane vp = planes[2];
            ByteBuffer yb = yp.getBuffer();
            ByteBuffer ub = up.getBuffer();
            ByteBuffer vb = vp.getBuffer();
            int yRowStride = yp.getRowStride();
            int yPixStride = yp.getPixelStride();
            int uRowStride = up.getRowStride();
            int uPixStride = up.getPixelStride();
            int vRowStride = vp.getRowStride();
            int vPixStride = vp.getPixelStride();

            long sumY = 0;
            int idx = 0;
            for (int y = 0; y < h; y++) {
                int sourceY = crop.top + y;
                int yBase = sourceY * yRowStride;
                int uBase = (sourceY >> 1) * uRowStride;
                int vBase = (sourceY >> 1) * vRowStride;
                for (int x = 0; x < w; x++) {
                    int sourceX = crop.left + x;
                    int Y = yb.get(yBase + sourceX * yPixStride) & 0xFF;
                    int U = ub.get(uBase + (sourceX >> 1) * uPixStride) & 0xFF;
                    int V = vb.get(vBase + (sourceX >> 1) * vPixStride) & 0xFF;
                    sumY += Y;
                    argb[idx++] = yuv2rgb(Y, U, V);
                }
            }
            lastLuma = (int) (sumY / (long) (w * h));
            bitmap.setPixels(argb, 0, w, 0, 0, w, h);
            listener.onBitmap(bitmap);
        } finally {
            image.close();
        }
    }

    private void ensureBitmap(int w, int h) {
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            argb = new int[w * h];
        }
    }

    /** BT.601 limited range YUV → ARGB。 */
    private static int yuv2rgb(int y, int u, int v) {
        int c = y - 16;
        if (c < 0) {
            c = 0;
        }
        int d = u - 128;
        int e = v - 128;
        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    public void release() {
        released = true;
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
            argb = null;
        }
        started = false;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
    }
}
