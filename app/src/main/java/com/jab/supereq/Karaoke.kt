package com.jab.supereq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Vocal-removal settings shared by the UI and the audio thread. */
object KParams {
    val strength = MutableStateFlow(0.9f) // 0..1
    val range = MutableStateFlow(0)       // 0 wide, 1 male, 2 female
    val gain = MutableStateFlow(1f)       // live-capture makeup gain
    val quiet = MutableStateFlow(true)    // lower original app volume, play ours on the alarm stream
    fun band(): Pair<Float, Float> = when (range.value) {
        1 -> 100f to 5000f
        2 -> 200f to 9000f
        else -> 150f to 9000f
    }
}

internal class Biquad(highPass: Boolean, fc: Float, fs: Float) {
    private val b0: Float; private val b1: Float; private val b2: Float
    private val a1: Float; private val a2: Float
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    init {
        val w0 = (2.0 * PI * fc / fs).toDouble()
        val c = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * 0.7071)).toFloat()
        val a0 = 1f + alpha
        if (highPass) {
            b0 = (1f + c) / 2f / a0; b1 = -(1f + c) / a0; b2 = (1f + c) / 2f / a0
        } else {
            b0 = (1f - c) / 2f / a0; b1 = (1f - c) / a0; b2 = (1f - c) / 2f / a0
        }
        a1 = -2f * c / a0; a2 = (1f - alpha) / a0
    }

    fun p(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** 4th-order band-pass: keeps only the vocal range of the center (mid) signal. */
internal class VocalBand(fs: Float, lo: Float, hi: Float) {
    private val f = listOf(Biquad(true, lo, fs), Biquad(true, lo, fs), Biquad(false, hi, fs), Biquad(false, hi, fs))
    fun p(x: Float): Float { var y = x; for (b in f) y = b.p(y); return y }
}

/**
 * Decodes a local audio file, removes center-panned vocals (phase cancellation limited to the vocal
 * band so bass and cymbals stay), and plays it through AudioTrack so the EQ still applies.
 */
class KaraokeSession(
    private val ctx: Context, private val uri: Uri, private val startUs: Long, private val onEnd: () -> Unit,
) {
    @Volatile var paused = false
    @Volatile var positionUs = startUs
    @Volatile private var running = false

    fun start() { running = true; Thread({ run() }, "karaoke").start() }
    fun stop() { running = false }

    private fun run() {
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var session = 0
        var natural = false
        try {
            ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
            val ti = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val info = MediaCodec.BufferInfo()
            var inDone = false
            var ch = 0
            var sr = 0
            var band: VocalBand? = null
            var bandKey = -1
            var trackPaused = false

            while (running) {
                if (paused) {
                    if (!trackPaused) { track?.pause(); trackPaused = true }
                    Thread.sleep(40); continue
                }
                if (trackPaused) { track?.play(); trackPaused = false }

                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi < 0) continue

                if (track == null) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (ch > 2) { codec.releaseOutputBuffer(oi, false); break } // only mono/stereo supported
                    val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minBuf = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(mask).build())
                        .setBufferSizeInBytes(max(minBuf * 4, sr * 2 * ch / 2))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    session = track.audioSessionId
                    EqEngine.attach(session)
                    track.play()
                }

                val out = codec.getOutputBuffer(oi)!!
                out.position(info.offset); out.limit(info.offset + info.size)
                val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                val arr = ShortArray(sb.remaining())
                sb.get(arr)

                if (ch == 2 && arr.isNotEmpty()) {
                    val rk = KParams.range.value
                    if (band == null || rk != bandKey) {
                        bandKey = rk
                        val (lo, hi) = KParams.band()
                        band = VocalBand(sr.toFloat(), lo, min(hi, sr * 0.45f))
                    }
                    val s = KParams.strength.value
                    val vb = band!!
                    var i = 0
                    while (i + 1 < arr.size) {
                        val l = arr[i] / 32768f
                        val r = arr[i + 1] / 32768f
                        val mid = vb.p((l + r) * 0.5f)
                        arr[i] = ((l - s * mid).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        arr[i + 1] = ((r - s * mid).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        i += 2
                    }
                }

                track!!.write(arr, 0, arr.size) // blocks until there is room: paces playback
                positionUs = info.presentationTimeUs
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(oi, false)
                if (eos) { natural = true; break }
            }
            if (natural && running) { track?.stop(); Thread.sleep(900) }
        } catch (_: Throwable) {
        } finally {
            if (session != 0) EqEngine.detach(session)
            try { track?.release() } catch (_: Throwable) {}
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            if (natural && running) Handler(Looper.getMainLooper()).post { onEnd() }
        }
    }
}
