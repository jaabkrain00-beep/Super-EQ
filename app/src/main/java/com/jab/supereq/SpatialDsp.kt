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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/** Spatial / 8D settings shared by UI and audio threads. */
object SParams {
    const val OFF = 0
    const val SPATIAL = 1
    const val EIGHT_D = 2
    const val CINEMA = 3   // AirPods-style virtual speakers + room
    val mode = MutableStateFlow(OFF)
    val width = MutableStateFlow(0.7f)   // 0..1 stage width
    val room = MutableStateFlow(0.4f)    // 0..1 ambience / surround amount
    val period = MutableStateFlow(8f)    // seconds per full 8D rotation
    val gain = MutableStateFlow(1f)      // makeup gain for capture-from-other-apps
    val quiet = MutableStateFlow(true)   // capture: lower original app, play ours on alarm stream
}

/**
 * Stereo spatial processor. The center (mid) signal - vocals, kick, bass - is never reduced:
 *  - SPATIAL: widens only the side signal above 200 Hz and adds lateral early reflections.
 *  - CINEMA: binaural virtual speakers at +-30 deg (inter-aural delay + head-shadow cross-feed),
 *        rear-shaded ambience and a small stereo room reverb: the "AirPods Spatial Audio" feel.
 *  - 8D: the mid signal orbits the head (level pan + inter-aural delay + rear low-pass),
 *        side signal and ambience stay, so music and voice keep full body.
 */
internal class SpatialDsp(private val fs: Float) {
    private val n = (fs * 0.1f).toInt()
    private val dm = FloatArray(n)
    private val ds = FloatArray(n)
    private val dl = FloatArray(n)
    private val dr = FloatArray(n)
    private var w = 0
    private var sLow = 0f
    private var mLow = 0f
    private var bl = 0f
    private val aLow = (1.0 - exp(-2.0 * PI * 200.0 / fs)).toFloat()
    private val aBack = (1.0 - exp(-2.0 * PI * 2500.0 / fs)).toFloat()
    private var phase = 0.0
    var outL = 0f
    var outR = 0f

    // CINEMA state
    private val xl = FloatArray(n)
    private val xr = FloatArray(n)
    private var hsL = 0f
    private var hsR = 0f
    private var ambLp = 0f
    private val aHead = (1.0 - exp(-2.0 * PI * 1800.0 / fs)).toFloat()
    private val aRear = (1.0 - exp(-2.0 * PI * 4000.0 / fs)).toFloat()
    private val combLen = intArrayOf(1557, 1617, 1491, 1422)
    private val combL = Array(4) { FloatArray((combLen[it] * fs / 44100f).toInt() + 1) }
    private val combR = Array(4) { FloatArray(((combLen[it] + 23) * fs / 44100f).toInt() + 1) }
    private val ciL = IntArray(4)
    private val ciR = IntArray(4)
    private val dampL = FloatArray(4)
    private val dampR = FloatArray(4)

    private fun tap(b: FloatArray, ms: Float): Float = b[((w - (ms * fs / 1000f).toInt()) % n + n) % n]

    private fun frac(b: FloatArray, d: Float): Float {
        val pos = w - d
        val i0 = floor(pos).toInt()
        val f = pos - i0
        val a = b[((i0 % n) + n) % n]
        val c = b[(((i0 - 1) % n) + n) % n]
        return a + (c - a) * f
    }

    private fun lim(x: Float): Float {
        val a = abs(x)
        return if (a < 0.8f) x else (if (x < 0f) -1f else 1f) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }

    fun process(l: Float, r: Float, mode: Int, width: Float, room: Float, periodSec: Float) {
        val m = (l + r) * 0.5f
        val s = (l - r) * 0.5f
        sLow += aLow * (s - sLow)
        mLow += aLow * (m - mLow)
        val sHi = s - sLow
        val mHi = m - mLow
        dm[w] = mHi; ds[w] = sHi
        val amb = room * (0.22f * (tap(ds, 19f) + 0.7f * tap(ds, 31f)) + 0.12f * (tap(dm, 23f) - 0.6f * tap(dm, 37f)))

        if (mode == SParams.EIGHT_D) {
            phase += 2.0 * PI / (periodSec.coerceAtLeast(1f) * fs)
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            val p = sin(phase).toFloat()          // -1 left .. +1 right
            val back = (1f - cos(phase).toFloat()) * 0.5f // 0 front .. 1 behind
            bl += aBack * (m - bl)
            val x = (m + back * 0.75f * (bl - m)) * (1f - 0.12f * back)
            val a = (p + 1f) * (PI.toFloat() / 4f)
            dl[w] = x * cos(a) * 1.35f
            dr[w] = x * sin(a) * 1.35f
            val d = abs(p) * 0.0006f * fs
            val rl = if (p > 0f) frac(dl, d) else dl[w]
            val rr = if (p < 0f) frac(dr, d) else dr[w]
            val side = s * 0.6f * (0.6f + width) + amb * 1.4f
            outL = lim(rl + side)
            outR = lim(rr - side)
        } else if (mode == SParams.CINEMA) {
            xl[w] = l; xr[w] = r
            // far-ear path: ~0.26 ms later, darker (head shadow)
            hsL += aHead * (tap(xr, 0.26f) - hsL)
            hsR += aHead * (tap(xl, 0.26f) - hsR)
            val c = 0.45f * (0.4f + width)
            val comp = 1f / (1f + 0.7f * c)
            // rear-shaded lateral ambience (surround channels)
            ambLp += aRear * (amb - ambLp)
            // small room: 4 damped combs per ear, slightly different lengths for decorrelation
            val inp = (m * 0.6f + s * 0.4f) * 0.15f
            var wl = 0f; var wr = 0f
            for (k in 0..3) {
                val bl2 = combL[k]; val br2 = combR[k]
                val yl = bl2[ciL[k]]; val yr = br2[ciR[k]]
                dampL[k] = yl * 0.6f + dampL[k] * 0.4f
                dampR[k] = yr * 0.6f + dampR[k] * 0.4f
                bl2[ciL[k]] = inp + dampL[k] * 0.78f
                br2[ciR[k]] = inp + dampR[k] * 0.78f
                ciL[k] = (ciL[k] + 1) % bl2.size
                ciR[k] = (ciR[k] + 1) % br2.size
                wl += yl; wr += yr
            }
            val wet = 0.45f * room
            outL = lim((l + c * hsL) * comp + ambLp * 1.2f + wl * wet)
            outR = lim((r + c * hsR) * comp - ambLp * 1.2f + wr * wet)
        } else {
            val s2 = sLow + sHi * (1f + 1.4f * width)
            outL = lim(m + s2 + amb)
            outR = lim(m - s2 - amb)
        }
        w = (w + 1) % n
    }
}

/** Decodes a local file, applies SpatialDsp, plays through AudioTrack so the EQ still applies. */
class SpatialSession(
    private val ctx: Context, private val uri: Uri, private val startUs: Long,
    private val onEnd: () -> Unit, private val onFail: () -> Unit,
) {
    @Volatile var paused = false
    @Volatile var positionUs = startUs
    @Volatile private var running = false

    fun start() { running = true; Thread({ run() }, "spatial").start() }
    fun stop() { running = false }

    private fun run() {
        var ex: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var session = 0
        var natural = false
        var failed = false
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
            var dsp: SpatialDsp? = null
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
                        val c = ex.readSampleData(buf, 0)
                        if (c < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, c, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi < 0) continue

                if (track == null) {
                    val f = codec.outputFormat
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (ch > 2) { codec.releaseOutputBuffer(oi, false); failed = true; break }
                    val mask = AudioFormat.CHANNEL_OUT_STEREO
                    val minBuf = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(mask).build())
                        .setBufferSizeInBytes(max(minBuf * 4, sr * 4 / 2))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    dsp = SpatialDsp(sr.toFloat())
                    session = track.audioSessionId
                    EqEngine.attach(session)
                    track.play()
                }

                val out = codec.getOutputBuffer(oi)!!
                out.position(info.offset); out.limit(info.offset + info.size)
                val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                val src = ShortArray(sb.remaining())
                sb.get(src)
                val frames = if (ch == 1) src.size else src.size / 2
                val arr = ShortArray(frames * 2)
                val mode = if (EqEngine.ui.value.enabled) SParams.mode.value else SParams.OFF
                val wd = SParams.width.value; val rm = SParams.room.value; val pd = SParams.period.value
                val d = dsp!!
                for (k in 0 until frames) {
                    val l: Float; val r: Float
                    if (ch == 1) { l = src[k] / 32768f; r = l } else { l = src[2 * k] / 32768f; r = src[2 * k + 1] / 32768f }
                    if (mode == SParams.OFF) { d.outL = l; d.outR = r } else d.process(l, r, mode, wd, rm, pd)
                    arr[2 * k] = (d.outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    arr[2 * k + 1] = (d.outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }

                track!!.write(arr, 0, arr.size) // blocks: paces playback
                positionUs = info.presentationTimeUs
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(oi, false)
                if (eos) { natural = true; break }
            }
            if (natural && running) { track?.stop(); Thread.sleep(900) }
        } catch (_: Throwable) {
            if (track == null) failed = true
        } finally {
            if (session != 0) EqEngine.detach(session)
            try { track?.release() } catch (_: Throwable) {}
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            val h = Handler(Looper.getMainLooper())
            if (failed && running) h.post { onFail() }
            else if (natural && running) h.post { onEnd() }
        }
    }
}
