package com.example.stillvideo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Longest side of the output video. Raise it if your phone's encoder can take more. */
const val MAX_SIDE = 1920

/** A still image never changes, so we only write this many frames however long the video is. */
private const val MAX_FRAMES = 600
private const val TIMEOUT_US = 10_000L

/** Decodes the image (EXIF rotation applied) and downsamples it so its longest side is <= MAX_SIDE. */
fun decodeScaled(cr: ContentResolver, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val s = min(1f, MAX_SIDE / max(w, h).toFloat())
        dec.setTargetSize(max(2, (w * s).roundToInt()), max(2, (h * s).roundToInt()))
    }

/** H.264 needs even dimensions. Also flattens transparency onto black. */
fun prepare(src: Bitmap): Bitmap {
    val w = max(2, src.width and 1.inv())
    val h = max(2, src.height and 1.inv())
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        drawColor(Color.BLACK)
        drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return out
}

private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

/** ARGB -> planar YUV 4:2:0, BT.709 limited range. Done once; every frame reuses it. */
private fun toYuv(bmp: Bitmap): Yuv {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val y = ByteArray(w * h)
    val u = ByteArray(w * h / 4)
    val v = ByteArray(w * h / 4)
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        y[i] = (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte()
    }
    val cw = w / 2
    for (j in 0 until h / 2) for (i in 0 until cw) {
        var r = 0; var g = 0; var b = 0
        for (dj in 0..1) for (di in 0..1) {
            val p = px[(2 * j + dj) * w + 2 * i + di]
            r += (p shr 16) and 255
            g += (p shr 8) and 255
            b += p and 255
        }
        r /= 4; g /= 4; b /= 4
        u[j * cw + i] = (((-26 * r - 86 * g + 112 * b + 128) shr 8) + 128).toByte()
        v[j * cw + i] = (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).toByte()
    }
    return Yuv(y, u, v)
}

private fun copyPlane(plane: Image.Plane, src: ByteArray, w: Int, h: Int) {
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride
    if (pixStride == 1) {
        if (rowStride == w) {
            buf.position(0); buf.put(src, 0, w * h)
        } else {
            for (row in 0 until h) { buf.position(row * rowStride); buf.put(src, row * w, w) }
        }
    } else {
        for (row in 0 until h) {
            val base = row * rowStride
            val s = row * w
            for (col in 0 until w) buf.put(base + col * pixStride, src[s + col])
        }
    }
}

private fun fill(img: Image, yuv: Yuv, w: Int, h: Int) {
    val p = img.planes
    copyPlane(p[0], yuv.y, w, h)
    copyPlane(p[1], yuv.u, w / 2, h / 2)
    copyPlane(p[2], yuv.v, w / 2, h / 2)
}

/**
 * Writes [frame] as an H.264 MP4 that lasts [seconds], using the phone's hardware encoder.
 * Frames are spread evenly across the duration; after the first one, nothing changes,
 * so the encoder emits tiny "no change" frames and the file stays small.
 */
fun encodeStill(
    frame: Bitmap,
    seconds: Long,
    out: FileDescriptor,
    onProgress: (Float) -> Unit,
    checkCancelled: () -> Unit,
) {
    val w = frame.width
    val h = frame.height
    val yuv = toYuv(frame)
    val total = min(seconds, MAX_FRAMES.toLong()).toInt().coerceAtLeast(1)
    val stepUs = seconds * 1_000_000L / total

    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        // Declared at 1 fps, so this is roughly the per-frame budget: ~0.5 bit per pixel.
        setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h / 2).toInt().coerceIn(200_000, 12_000_000))
        setInteger(MediaFormat.KEY_FRAME_RATE, 1)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        setInteger(MediaFormat.KEY_PRIORITY, 1) // not real-time: encode as fast as possible
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }

    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxing = false
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var track = -1
        var queued = 0
        var written = 0
        var inputDone = false
        var done = false

        while (!done) {
            checkCancelled()

            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    if (queued < total) {
                        val img = codec.getInputImage(i) ?: error("This encoder doesn't accept YUV images")
                        fill(img, yuv, w, h)
                        codec.queueInputBuffer(i, 0, w * h * 3 / 2, queued * stepUs, 0)
                        queued++
                    } else {
                        codec.queueInputBuffer(i, 0, 0, total * stepUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }

            val o = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        written++
                        onProgress(min(1f, written / total.toFloat()))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        muxer.stop()
        muxing = false
    } finally {
        runCatching { codec.stop() }
        codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }
}
