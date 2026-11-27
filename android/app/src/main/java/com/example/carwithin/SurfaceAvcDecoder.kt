package com.example.carwithin

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Used by one CarLife session. Call feed, attach and close under that session's lock. */
internal class SurfaceAvcDecoder(private val callbackHandler: Handler) {
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    @Volatile private var activeDecoder: MediaCodec? = null
    private val rendered = AtomicLong()
    val framesRendered: Long get() = rendered.get()
    val needsKeyFrame: Boolean get() = surface?.isValid == true && codec == null
    var dropped = 0L
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun attach(next: Surface?) {
        if (next === surface) return
        closeCodec()
        surface = next
    }

    fun feed(data: ByteArray, key: Boolean, sps: ByteArray?, pps: ByteArray?, pts: Long, w: Int, h: Int) {
        val output = surface?.takeIf { it.isValid } ?: return
        if (codec == null) {
            if (!key || sps == null || pps == null) return
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                decoder.configure(format, output, null, 0)
                decoder.setOnFrameRenderedListener({ instance, _, _ ->
                    if (activeDecoder === instance) rendered.incrementAndGet()
                }, callbackHandler)
                decoder.start()
                codec = decoder
                activeDecoder = decoder
                width = w
                height = h
            } catch (failure: Exception) {
                decoder.release()
                throw failure
            }
        }
        val decoder = checkNotNull(codec)
        drain(decoder)
        val index = decoder.dequeueInputBuffer(10_000)
        if (index < 0) {
            dropped++
            closeCodec()
            return
        }
        val buffer = checkNotNull(decoder.getInputBuffer(index))
        buffer.clear()
        check(buffer.remaining() >= data.size) { "CarLife 视频帧大于解码缓冲区" }
        buffer.put(data)
        decoder.queueInputBuffer(index, 0, data.size, pts, 0)
        drain(decoder)
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                width = size(format, MediaFormat.KEY_WIDTH, "crop-left", "crop-right")
                height = size(format, MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom")
                continue
            }
            if (index < 0) return
            decoder.releaseOutputBuffer(index, info.size > 0)
        }
    }

    private fun size(format: MediaFormat, dimension: String, start: String, end: String): Int {
        if (!format.containsKey(end)) return format.getInteger(dimension)
        return format.getInteger(end) + 1 - if (format.containsKey(start)) format.getInteger(start) else 0
    }

    fun closeCodec() {
        val decoder = codec ?: return
        codec = null
        activeDecoder = null
        runCatching { decoder.stop() }
        decoder.release()
    }
}
