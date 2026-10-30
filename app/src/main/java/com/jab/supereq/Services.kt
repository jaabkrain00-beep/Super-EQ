package com.jab.supereq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Keeps the process alive in the background while other apps (YouTube, Spotify...) play. */
class EqService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        EqEngine.init(this)
        Player.init(this)
        OutputState.watch(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("eq", "Super EQ", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, "eq")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Super EQ กำลังทำงาน")
            .setContentText("ปรับเสียงเบื้องหลังอยู่")
            .setContentIntent(open).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29)
                ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, n)
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
}

/** Players that broadcast their audio session (Poweramp, VLC, etc.) get EQ'd directly. */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (s <= 0) return
        EqEngine.init(c)
        if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) EqEngine.attach(s)
        else EqEngine.detach(s)
    }
}

data class NowPlaying(
    val app: String = "", val pkg: String = "",
    val title: String = "", val artist: String = "", val playing: Boolean = false,
)

object NowPlayingState { val now = MutableStateFlow(NowPlaying()) }

/** Reads which app is playing and what track, via media sessions. Needs Notification Access. */
class MediaWatcher : NotificationListenerService() {
    private var ctrls: List<MediaController> = emptyList()
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { update(it ?: emptyList()) }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(this, MediaWatcher::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(listener, cn)
            update(msm.getActiveSessions(cn))
        } catch (_: SecurityException) {}
    }

    override fun onListenerDisconnected() {
        ctrls.forEach { it.unregisterCallback(cb) }
        ctrls = emptyList()
        NowPlayingState.now.value = NowPlaying()
    }

    private fun update(list: List<MediaController>) {
        ctrls.forEach { it.unregisterCallback(cb) }
        ctrls = list
        ctrls.forEach { it.registerCallback(cb) }
        publish()
    }

    private fun label(pkg: String): String = KNOWN[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    private fun publish() {
        val c = ctrls.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: ctrls.firstOrNull()
        if (c == null) { NowPlayingState.now.value = NowPlaying(); return }
        val m = c.metadata
        NowPlayingState.now.value = NowPlaying(
            app = label(c.packageName), pkg = c.packageName,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    companion object {
        val KNOWN = mapOf(
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.spotify.music" to "Spotify",
            "com.apple.android.music" to "Apple Music",
            "com.joox.joox" to "JOOX",
            "deezer.android.app" to "Deezer",
            "com.soundcloud.android" to "SoundCloud",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.facebook.katana" to "Facebook",
            "org.videolan.vlc" to "VLC",
        )
    }
}

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

/** Simple local-music player, so users can pick songs stored on the phone. */
object Player {
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val current = MutableStateFlow<Song?>(null)
    val playing = MutableStateFlow(false)
    private var mp: MediaPlayer? = null
    private var idx = -1
    private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    fun scan() {
        val c = app ?: return
        val out = ArrayList<Song>()
        try {
            c.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out += Song(id, cur.getString(1) ?: "?", cur.getString(2) ?: "",
                        Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()))
                }
            }
        } catch (_: Throwable) {}
        songs.value = out
    }

    private fun release() {
        mp?.let { EqEngine.detach(it.audioSessionId); try { it.release() } catch (_: Throwable) {} }
        mp = null
    }

    fun play(i: Int) {
        val c = app ?: return
        val list = songs.value
        if (i !in list.indices) return
        release()
        idx = i
        val s = list[i]
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(c, s.uri)
                setOnCompletionListener { next() }
                prepare()
                EqEngine.attach(audioSessionId)
                start()
            }
            current.value = s
            playing.value = true
        } catch (_: Throwable) { playing.value = false }
    }

    fun toggle() {
        val m = mp ?: run { if (songs.value.isNotEmpty()) play(0); return }
        if (m.isPlaying) { m.pause(); playing.value = false } else { m.start(); playing.value = true }
    }
    fun next() { if (songs.value.isNotEmpty()) play((idx + 1) % songs.value.size) }
    fun prev() { if (songs.value.isNotEmpty()) play((idx - 1 + songs.value.size) % songs.value.size) }
}

data class OutputInfo(
    val label: String = "ลำโพงเครื่อง", val name: String = "",
    val key: String = "speaker", val bluetooth: Boolean = false, val profile: String = "ลำโพงมือถือ",
)

/** Detects the active output (any Bluetooth headset/speaker, wired, USB, built-in) and picks a profile. */
object OutputState {
    val now = MutableStateFlow(OutputInfo())

    private fun guess(name: String): String {
        val n = name.lowercase()
        return when {
            listOf("speaker", "boom", "flip", "charge", "megaboom", "soundlink", "homepod", "echo", "stanmore", "marshall")
                .any { n.contains(it) } -> "ลำโพงบลูทูธพกพา"
            listOf("car", "carplay", "android auto", "mazda", "toyota", "honda", "bmw", "benz").any { n.contains(it) } -> "ลำโพงในรถ"
            listOf("wh-1000", "xm4", "xm5", "xm3", "momentum", "px7", "quietcomfort", "qc35", "qc45", "ath-", "studio")
                .any { n.contains(it) } -> "หูฟัง Over-ear ปิด (เบสเยอะ)"
            listOf("gaming", "headset", "arctis", "cloud", "kraken", "g pro").any { n.contains(it) } -> "หูฟังเกมมิ่ง"
            else -> "TWS / หูฟังไร้สาย" // AirPods, Buds, WF-, Pods... and any unknown headset
        }
    }

    fun refresh(c: Context) {
        val am = c.getSystemService(AudioManager::class.java)
        val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun nm(x: AudioDeviceInfo) = x.productName?.toString().orEmpty()
        fun addr(x: AudioDeviceInfo) = if (Build.VERSION.SDK_INT >= 28) x.address.orEmpty() else ""
        val info: OutputInfo = run {
            d.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == 26 /*BLE headset*/ }?.let {
                val n = nm(it).ifBlank { "Bluetooth" }
                return@run OutputInfo("Bluetooth", n, "bt:${addr(it).ifBlank { n }}", true, guess(n))
            }
            d.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let {
                return@run OutputInfo("หูฟังสาย", nm(it), "wired", false, "ทั่วไป")
            }
            d.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }?.let {
                return@run OutputInfo("USB", nm(it), "usb:${nm(it)}", false, "หูฟัง Studio")
            }
            OutputInfo()
        }
        now.value = info
        EqEngine.onOutputChanged(info.key, info.profile)
    }

    fun watch(c: Context) {
        val app = c.applicationContext
        app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh(app)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh(app)
        }, null)
        refresh(app)
    }
}

data class SpatialCaps(val system: Boolean, val systemOn: Boolean, val headTracking: Boolean)

/** System spatializer (Android 12L+): what the phone + connected headset support. Apps cannot force it on. */
fun spatialCaps(c: Context): SpatialCaps {
    if (Build.VERSION.SDK_INT < 32) return SpatialCaps(false, false, false)
    return try {
        val s = c.getSystemService(AudioManager::class.java).spatializer
        SpatialCaps(s.isAvailable, s.isEnabled, s.isHeadTrackerAvailable)
    } catch (_: Throwable) { SpatialCaps(false, false, false) }
}
