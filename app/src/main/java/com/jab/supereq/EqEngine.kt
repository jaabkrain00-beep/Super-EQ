package com.jab.supereq

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Build
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ln
import kotlin.math.roundToInt

data class EqUi(
    val enabled: Boolean = true,
    val preset: String = "Flat",
    val device: String = "ทั่วไป",
    val gains: List<Float> = List(10) { 0f },
    val preamp: Float = 0f,   // dB, 0..12
    val bass: Int = 0,        // 0..1000
    val virt: Int = 0,        // 0..1000
    val binaural: Boolean = false, // headphone surround (binaural virtualizer)
)

object EqEngine {
    val ui = MutableStateFlow(EqUi())

    private class Fx(val eq: Equalizer, val bb: BassBoost?, val vz: Virtualizer?, val le: LoudnessEnhancer?)

    private val fx = HashMap<Int, Fx>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        load()
        attach(0) // global mix: works on many devices, not all (deprecated by Android)
    }

    /** Attach effects to an audio session (0 = global output mix). */
    @Synchronized
    fun attach(session: Int) {
        if (fx.containsKey(session)) return
        val eq = try { Equalizer(0, session) } catch (t: Throwable) { return }
        val bb = try { BassBoost(0, session) } catch (t: Throwable) { null }
        val vz = try { Virtualizer(0, session) } catch (t: Throwable) { null }
        val le = try { LoudnessEnhancer(session) } catch (t: Throwable) { null }
        val f = Fx(eq, bb, vz, le)
        fx[session] = f
        apply(f)
    }

    @Synchronized
    fun detach(session: Int) {
        val f = fx.remove(session) ?: return
        try { f.eq.release() } catch (_: Throwable) {}
        try { f.bb?.release() } catch (_: Throwable) {}
        try { f.vz?.release() } catch (_: Throwable) {}
        try { f.le?.release() } catch (_: Throwable) {}
    }

    fun isActive(): Boolean = synchronized(this) { fx.isNotEmpty() }

    private fun interp(g: List<Float>, hz: Float): Float {
        val fs = FREQS.map { ln(it.toFloat()) }
        val x = ln(hz.coerceAtLeast(1f))
        if (x <= fs.first()) return g.first()
        if (x >= fs.last()) return g.last()
        val i = fs.indexOfLast { it <= x }
        val t = (x - fs[i]) / (fs[i + 1] - fs[i])
        return g[i] + (g[i + 1] - g[i]) * t
    }

    fun finalCurve(u: EqUi): List<Float> {
        val off = Devices.offsetOf(u.device)
        return u.gains.indices.map { u.gains[it] + off[it] }
    }

    private fun apply(f: Fx) {
        val u = ui.value
        try {
            val curve = finalCurve(u)
            val range = f.eq.bandLevelRange
            for (b in 0 until f.eq.numberOfBands) {
                val hz = f.eq.getCenterFreq(b.toShort()) / 1000f
                val mb = (interp(curve, hz) * 100).roundToInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                f.eq.setBandLevel(b.toShort(), mb.toShort())
            }
            f.eq.enabled = u.enabled
            f.bb?.let { if (it.strengthSupported) it.setStrength(u.bass.toShort()); it.enabled = u.enabled && u.bass > 0 }
            f.vz?.let {
                if (Build.VERSION.SDK_INT >= 28) try {
                    val mode = if (u.binaural && it.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, Virtualizer.VIRTUALIZATION_MODE_BINAURAL))
                        Virtualizer.VIRTUALIZATION_MODE_BINAURAL else Virtualizer.VIRTUALIZATION_MODE_AUTO
                    it.forceVirtualizationMode(mode)
                } catch (_: Throwable) {}
                if (it.strengthSupported) it.setStrength(u.virt.toShort())
                it.enabled = u.enabled && u.virt > 0
            }
            f.le?.let { it.setTargetGain((u.preamp * 100).roundToInt()); it.enabled = u.enabled && u.preamp > 0 }
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun push() {
        fx.values.forEach { apply(it) }
        save()
    }

    fun setEnabled(b: Boolean) { ui.update { it.copy(enabled = b) }; push() }
    fun setPreset(p: Preset) { ui.update { it.copy(preset = p.name, gains = p.g) }; push() }
    /** Tap the active preset again -> cancel it (flat curve, no preset). */
    fun togglePreset(p: Preset) {
        if (ui.value.preset == p.name) { ui.update { it.copy(preset = "", gains = List(10) { 0f }) }; push() }
        else setPreset(p)
    }
    fun setDevice(name: String) { ui.update { it.copy(device = name) }; push() }
    fun setBand(i: Int, finalDb: Float) {
        ui.update {
            val off = Devices.offsetOf(it.device)[i]
            val g = it.gains.toMutableList()
            g[i] = (finalDb - off).coerceIn(-12f, 12f)
            it.copy(gains = g, preset = "Custom")
        }
        push()
    }
    fun setPreamp(v: Float) { ui.update { it.copy(preamp = v) }; push() }
    fun setBass(v: Int) { ui.update { it.copy(bass = v) }; push() }
    fun setVirt(v: Int) { ui.update { it.copy(virt = v) }; push() }
    /** mode 0=off, 1=wide, 2=surround, 3=full 360 */
    fun setSpatial(mode: Int) {
        val v = intArrayOf(0, 450, 750, 1000)[mode.coerceIn(0, 3)]
        ui.update { it.copy(virt = v, binaural = mode > 0) }; push()
    }
    fun spatialMode(u: EqUi): Int = when { u.virt == 0 -> 0; u.virt < 600 -> 1; u.virt < 900 -> 2; else -> 3 }

    @Synchronized
    fun binauralSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return fx.values.any {
            try { it.vz?.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, Virtualizer.VIRTUALIZATION_MODE_BINAURAL) == true }
            catch (_: Throwable) { false }
        }
    }

    // ---- per-output memory: every headset/speaker keeps its own settings ----
    private var outKey: String? = null

    private fun snap(u: EqUi) = listOf(u.enabled, u.preset, u.device, u.gains.joinToString(","),
        u.preamp, u.bass, u.virt, u.binaural).joinToString("|")

    private fun unsnap(t: String): EqUi? = try {
        val p = t.split("|")
        EqUi(p[0].toBoolean(), p[1], p[2], p[3].split(",").map { it.toFloat() }, p[4].toFloat(),
            p[5].toInt(), p[6].toInt(), p[7].toBoolean())
    } catch (_: Throwable) { null }

    @Synchronized
    fun onOutputChanged(key: String, guessedProfile: String) {
        if (key == outKey) return
        outKey = key
        val saved = prefs?.getString("out_$key", null)?.let { unsnap(it) }
        ui.update { saved ?: it.copy(device = guessedProfile) }
        fx.values.forEach { apply(it) }
    }

    private fun save() {
        val u = ui.value
        outKey?.let { prefs?.edit()?.putString("out_$it", snap(u))?.apply() }
        prefs?.edit()
            ?.putBoolean("en", u.enabled)?.putString("preset", u.preset)?.putString("dev", u.device)
            ?.putString("gains", u.gains.joinToString(","))
            ?.putFloat("pre", u.preamp)?.putInt("bass", u.bass)?.putInt("virt", u.virt)?.putBoolean("bin", u.binaural)?.apply()
    }

    private fun load() {
        val p = prefs ?: return
        val g = p.getString("gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 } ?: List(10) { 0f }
        ui.value = EqUi(
            enabled = p.getBoolean("en", true), preset = p.getString("preset", "Flat") ?: "Flat",
            device = p.getString("dev", "ทั่วไป") ?: "ทั่วไป", gains = g,
            preamp = p.getFloat("pre", 0f), bass = p.getInt("bass", 0), virt = p.getInt("virt", 0),
            binaural = p.getBoolean("bin", false),
        )
    }
}
