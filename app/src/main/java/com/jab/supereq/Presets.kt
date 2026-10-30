package com.jab.supereq

/** 10-band master curve, frequencies in Hz. Values in dB. */
val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

data class Preset(val name: String, val group: String, val g: List<Float>)

private fun p(n: String, grp: String, vararg v: Float) = Preset(n, grp, v.toList())

/**
 * Hand-tuned starting points based on common reference ideas (Harman-style bass/ear-gain target,
 * equal-loudness compensation, typical genre mixing balance). They are NOT measured data.
 */
val PRESETS: List<Preset> = listOf(
    // Reference
    p("Flat", "อ้างอิง", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Harman-style", "อ้างอิง", 3f, 2.5f, 1.5f, 0f, 0f, 0.5f, 1f, -0.5f, 0f, -1f),
    p("Quiet Loudness", "อ้างอิง", 6f, 4.5f, 2f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 4f),
    p("Late Night", "อ้างอิง", 2f, 2f, 1f, 0f, -0.5f, 0f, -1f, -2f, -3f, -4f),
    p("Warm", "อ้างอิง", 2f, 2.5f, 2f, 1.5f, 0.5f, 0f, -1f, -2f, -2.5f, -3f),
    p("Bright", "อ้างอิง", -2f, -1.5f, -1f, 0f, 0f, 0.5f, 2f, 3f, 4f, 4.5f),
    p("V-Shape", "อ้างอิง", 5f, 4f, 2f, -1f, -2.5f, -1.5f, 1f, 3f, 4.5f, 5f),
    p("Live / Concert", "อ้างอิง", -1f, 0f, 1.5f, 2f, 2.5f, 2f, 1.5f, 1.5f, 1f, 0.5f),
    // Genres
    p("Rock", "แนวเพลง", 4f, 3.5f, 2.5f, 1f, -1f, -1.5f, 0.5f, 2.5f, 3.5f, 4f),
    p("Pop", "แนวเพลง", -1f, 0f, 1.5f, 3f, 3.5f, 3f, 1f, 0f, -1f, -1.5f),
    p("Jazz", "แนวเพลง", 3f, 2.5f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1.5f, 2.5f, 3f),
    p("Classical", "แนวเพลง", 3.5f, 3f, 2.5f, 2f, -1f, -1f, 0f, 1.5f, 2.5f, 3f),
    p("EDM", "แนวเพลง", 5f, 4.5f, 2f, 0f, -2f, 1.5f, 0.5f, 2f, 4f, 5f),
    p("Techno", "แนวเพลง", 5.5f, 5f, 2.5f, 0f, -1f, 0f, 1f, 2.5f, 3f, 2f),
    p("Dance", "แนวเพลง", 4f, 5f, 3f, 0f, 1f, 2f, 3f, 1.5f, 0f, 0f),
    p("Hip-Hop", "แนวเพลง", 5.5f, 5f, 3f, 3f, -1f, -1f, 1f, -0.5f, 1.5f, 2.5f),
    p("R&B / Soul", "แนวเพลง", 3f, 6f, 5f, 2f, -2f, -1.5f, 2f, 2.5f, 3f, 3.5f),
    p("Metal", "แนวเพลง", 4f, 3f, 1f, -1f, -1.5f, 0f, 3f, 3.5f, 3f, 2f),
    p("Acoustic", "แนวเพลง", 4f, 3.5f, 3f, 1f, 1f, 1f, 2.5f, 3f, 3f, 2f),
    p("Country", "แนวเพลง", 2f, 2f, 1.5f, 0.5f, 1f, 1.5f, 3f, 3.5f, 3f, 2.5f),
    p("Reggae", "แนวเพลง", 4f, 4.5f, 3f, 0.5f, -1f, 0f, 1f, 1.5f, 1.5f, 1f),
    p("Blues", "แนวเพลง", 2.5f, 2.5f, 1.5f, 1f, 0.5f, 1f, 2f, 1.5f, 0.5f, 0f),
    p("Indie / Folk", "แนวเพลง", 2f, 2.5f, 2f, 1f, 0.5f, 1f, 1.5f, 2f, 1.5f, 1f),
    p("Piano", "แนวเพลง", 1f, 1f, 1f, 1.5f, 1f, 0.5f, 1.5f, 3f, 3f, 2f),
    p("Lo-fi", "แนวเพลง", 3f, 3.5f, 2.5f, 1.5f, 0.5f, 0f, -1.5f, -3f, -4f, -5f),
    // World
    p("K-Pop", "ทั่วโลก", 2f, 2.5f, 1.5f, 0f, -1f, 0.5f, 2f, 2.5f, 2.5f, 2f),
    p("J-Pop / Anime", "ทั่วโลก", 1f, 1.5f, 0.5f, 0f, 0f, 1.5f, 3f, 3f, 2.5f, 1.5f),
    p("Bollywood", "ทั่วโลก", 2f, 2.5f, 2f, 1f, 0f, 1f, 3f, 3f, 2f, 1f),
    p("Afrobeats", "ทั่วโลก", 4f, 5f, 3f, 1f, 0f, 0f, 1f, 1.5f, 1.5f, 1f),
    p("Amapiano", "ทั่วโลก", 5f, 5.5f, 3.5f, 1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 1f),
    p("Reggaeton / Latin", "ทั่วโลก", 5.5f, 5.5f, 3f, 0.5f, -1f, 0f, 1f, 1.5f, 2f, 2f),
    p("Arabic / Middle East", "ทั่วโลก", 2f, 2f, 1.5f, 1f, 0f, 1f, 2f, 2f, 1.5f, 1f),
    p("ลูกทุ่ง", "ทั่วโลก", 1f, 1.5f, 1f, 0.5f, 0f, 2f, 3f, 2.5f, 1.5f, 0.5f),
    p("ลูกกรุง / เพลงไทยเก่า", "ทั่วโลก", 2f, 2f, 1f, 0f, -0.5f, 1.5f, 2f, 1.5f, 1f, 0f),
    p("หมอลำ", "ทั่วโลก", 3f, 3f, 1.5f, 0.5f, 0f, 1.5f, 2.5f, 2.5f, 2f, 1f),
    // Use cases
    p("Bass Boost", "การใช้งาน", 6f, 5.5f, 4.5f, 2.5f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Deep Bass", "การใช้งาน", 8f, 7f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Bass Reducer", "การใช้งาน", -6f, -5f, -4f, -2f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Treble Boost", "การใช้งาน", 0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 5.5f),
    p("Treble Reducer", "การใช้งาน", 0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -4f, -5f, -5.5f),
    p("Vocal Boost", "การใช้งาน", -2f, -2f, -1f, 1f, 2f, 3.5f, 3.5f, 2f, 0f, -1f),
    p("Podcast / Speech", "การใช้งาน", -5f, -4f, -2f, 0f, 2f, 3f, 3.5f, 2f, 0f, -2f),
    p("Cinema / Movie", "การใช้งาน", 4f, 3f, 1f, 0f, -0.5f, 1f, 1.5f, 2f, 2.5f, 2f),
    p("Gaming (footsteps)", "การใช้งาน", 1f, 0f, -1f, -1.5f, 0f, 1.5f, 3.5f, 4f, 2f, 0f),
    p("Workout", "การใช้งาน", 4f, 4f, 2f, 0f, 0f, 1f, 2f, 3f, 3f, 2f),
    p("Car", "การใช้งาน", 3f, 3f, 1f, 0f, 0f, 0f, 1f, 2f, 3f, 3f),
)
val PRESET_GROUPS: List<String> = PRESETS.map { it.group }.distinct()

/** Output device compensation, added on top of the preset curve. Category level, not per-model. */
data class Dev(val name: String, val icon: String, val offset: List<Float>)

object Devices {
    val list = listOf(
        Dev("ทั่วไป", "🎧", List(10) { 0f }),
        Dev("ลำโพงมือถือ", "📱", listOf(-8f, -6f, -2f, 2f, 2f, 1f, 0f, 1f, 2f, 1f)),
        Dev("ลำโพงโน้ตบุ๊ก", "💻", listOf(-7f, -5f, -1f, 2f, 1.5f, 0.5f, 0f, 1f, 2f, 1f)),
        Dev("ลำโพงบลูทูธพกพา", "🔊", listOf(-2f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 1f)),
        Dev("ลำโพงในรถ", "🚗", listOf(-2f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f)),
        Dev("หูฟัง Studio", "🎼", List(10) { 0f }),
        Dev("หูฟัง Over-ear ปิด (เบสเยอะ)", "🎧", listOf(-2f, -2f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Dev("หูฟัง Open-back", "🎧", listOf(2f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Dev("In-ear เบสหนัก", "🎵", listOf(-4f, -3f, -2f, -1f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Dev("In-ear แหลมจัด", "🎵", listOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -2.5f, -1f)),
        Dev("TWS / หูฟังไร้สาย", "🎶", listOf(2f, 1.5f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1f)),
        Dev("หูฟังเกมมิ่ง", "🎮", listOf(1f, 1f, 0f, 0f, 0f, 1f, 2f, 2f, 0f, 0f)),
    )
    fun offsetOf(name: String): List<Float> = list.firstOrNull { it.name == name }?.offset ?: List(10) { 0f }
}
