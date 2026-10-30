package com.jab.supereq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

/**
 * EXPERIMENTAL live Spatial / 8D for other apps (YouTube Music etc.):
 * capture their playback (AudioPlaybackCapture) -> SpatialDsp -> play the result.
 * Works only if the other app allows capture. The original keeps playing, so by default we turn the
 * original's media volume down and play ours on the alarm stream.
 */
class CaptureService : Service() {
    companion object {
        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        val active = MutableStateFlow(false)
        val error = MutableStateFlow("")
    }

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var savedMusic = -1
    private var savedAlarm = -1
    private var cleaned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) { running = false; if (!active.value) stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else intent?.getParcelableExtra("data")
        if (data == null || Build.VERSION.SDK_INT < 29) {
            error.value = if (Build.VERSION.SDK_INT < 29) "ต้องใช้ Android 10 ขึ้นไป" else "ไม่ได้รับสิทธิ์อัดเสียง"
            stopSelf(); return START_NOT_STICKY
        }
        startFg()
        error.value = ""
        Thread({ work(code, data) }, "spatial-capture").start()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("eq", "Super EQ", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "eq")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("กำลังทำ Spatial / 8D ให้แอปอื่น")
            .setContentText("เปิดแอป Super EQ เพื่อหยุด").setOngoing(true).build()
        try {
            ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (_: Throwable) {}
    }

    private fun work(code: Int, data: Intent) {
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        var session = 0
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running = false }
            }, Handler(Looper.getMainLooper()))

            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .excludeUid(Process.myUid())
                .build()
            val sr = 48000
            val inFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord.Builder().setAudioFormat(inFmt)
                .setBufferSizeInBytes(max(minIn * 2, 8192))
                .setAudioPlaybackCaptureConfig(cfg).build()

            val quiet = SParams.quiet.value
            val usage = if (quiet) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            val minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(max(minOut * 2, 8192))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            session = track.audioSessionId
            EqEngine.attach(session)

            if (quiet) {
                val am = getSystemService(AudioManager::class.java)
                try {
                    savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
                    am.setStreamVolume(AudioManager.STREAM_ALARM,
                        (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7f).toInt().coerceAtLeast(1), 0)
                } catch (_: Throwable) {}
            }

            running = true
            active.value = true
            rec.startRecording()
            track.play()

            val buf = ShortArray(sr / 50 * 2) // 20 ms of stereo
            val dsp = SpatialDsp(sr.toFloat())
            var silentReads = 0
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(5); continue }
                val mode = if (EqEngine.ui.value.enabled) SParams.mode.value else SParams.OFF
                val wd = SParams.width.value; val rm = SParams.room.value; val pd = SParams.period.value
                val g = SParams.gain.value
                var i = 0
                var peak = 0
                while (i + 1 < n) {
                    val l = buf[i] / 32768f
                    val r = buf[i + 1] / 32768f
                    if (mode == SParams.OFF) { dsp.outL = l; dsp.outR = r } else dsp.process(l, r, mode, wd, rm, pd)
                    buf[i] = ((dsp.outL * g).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    buf[i + 1] = ((dsp.outR * g).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    peak = max(peak, kotlin.math.abs(buf[i].toInt()))
                    i += 2
                }
                silentReads = if (peak < 8) silentReads + 1 else 0
                if (silentReads == 250) // ~5 s of pure silence
                    error.value = "ไม่ได้ยินเสียงจากแอปที่เล่น (แอปอาจไม่อนุญาตให้อัดเสียง หรือเสียงต้นฉบับเบาเกิน)"
                else if (silentReads == 0 && error.value.startsWith("ไม่ได้ยินเสียง")) error.value = ""
                track.write(buf, 0, n)
            }
        } catch (t: SecurityException) {
            error.value = "ไม่ได้รับสิทธิ์ (ต้องอนุญาตไมโครโฟนและการอัดเสียงหน้าจอ)"
        } catch (t: Throwable) {
            error.value = "เริ่มไม่ได้: ${t.javaClass.simpleName}"
        } finally {
            cleanup(rec, track, session)
        }
    }

    private fun cleanup(rec: AudioRecord?, track: AudioTrack?, session: Int) {
        if (cleaned) return
        cleaned = true
        running = false
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        if (session != 0) EqEngine.detach(session)
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        try {
            val am = getSystemService(AudioManager::class.java)
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
            if (savedAlarm >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
        } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        active.value = false
        Handler(Looper.getMainLooper()).post {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            stopSelf()
        }
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
