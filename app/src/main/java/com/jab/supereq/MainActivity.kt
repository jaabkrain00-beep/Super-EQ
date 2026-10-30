package com.jab.supereq

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------- design tokens (palette taken from the app icon) ----------
private val Bg = Color(0xFF07080F)
private val Panel2 = Color(0xFF1A1D2D)
private val Cyan = Color(0xFF22D3FF)
private val Blue = Color(0xFF4D7CFF)
private val Violet = Color(0xFF9B5CFF)
private val Pink = Color(0xFFFF3DDB)
private val Amber = Color(0xFFFFB347)
private val Good = Color(0xFF34D399)
private val Bad = Color(0xFFFF6B7A)
private val TextHi = Color(0xFFF4F5FA)
private val Dim = Color(0xFF8A8FA8)
private val Grey = Color(0xFF4A4E60)
private val GreyBg = Color(0xFF14151C)
private val Line = Color(0x1FFFFFFF)
private val Brand = listOf(Cyan, Violet, Pink)
private val Accent = listOf(Blue, Violet, Pink)

/** false when the master switch is off: every control turns grey and ignores touch. */
private val LocalEqOn = compositionLocalOf { true }

private val Poppins = FontFamily(
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.Black),
)

private fun appTypography(): Typography {
    val d = Typography()
    fun s(t: TextStyle) = t.copy(fontFamily = Poppins)
    return d.copy(
        displayLarge = s(d.displayLarge), displayMedium = s(d.displayMedium), displaySmall = s(d.displaySmall),
        headlineLarge = s(d.headlineLarge), headlineMedium = s(d.headlineMedium), headlineSmall = s(d.headlineSmall),
        titleLarge = s(d.titleLarge), titleMedium = s(d.titleMedium), titleSmall = s(d.titleSmall),
        bodyLarge = s(d.bodyLarge), bodyMedium = s(d.bodyMedium), bodySmall = s(d.bodySmall),
        labelLarge = s(d.labelLarge), labelMedium = s(d.labelMedium), labelSmall = s(d.labelSmall),
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        EqEngine.init(this)
        Player.init(this)
        try { ContextCompat.startForegroundService(this, Intent(this, EqService::class.java)) } catch (_: Throwable) {}
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Violet, background = Bg, surface = Panel2),
        typography = appTypography()
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = Poppins, color = TextHi))) {
            var tab by remember { mutableIntStateOf(0) }
            Box(
                Modifier.fillMaxSize().background(Bg).drawBehind {
                    drawRect(Brush.radialGradient(listOf(Violet.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.02f), radius = size.width * 1.0f))
                    drawRect(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width * 1.0f, size.height * 0.30f), radius = size.width * 0.85f))
                    drawRect(Brush.radialGradient(listOf(Pink.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 1.0f), radius = size.width * 0.9f))
                }
            ) {
                if (tab == 0) EqScreen() else LibraryScreen()
                BottomBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// ---------- building blocks ----------
@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp)
            .clip(shape).background(Color(0xF2101220)).border(1.dp, Line, shape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NavItem(tab == 0, "Equalizer", 0) { onTab(0) }
        NavItem(tab == 1, "Library", 1) { onTab(1) }
    }
}

@Composable
private fun NavItem(selected: Boolean, label: String, kind: Int, onClick: () -> Unit) {
    val c by animateColorAsState(if (selected) Color.White else Dim, tween(200), label = "nav")
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.clip(shape)
            .background(if (selected) Brush.horizontalGradient(listOf(Blue.copy(alpha = 0.45f), Violet.copy(alpha = 0.45f), Pink.copy(alpha = 0.45f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height; val sw = 2.dp.toPx()
            if (kind == 0) {
                val ys = floatArrayOf(0.35f, 0.65f, 0.45f)
                for (i in 0..2) {
                    val x = w * (0.2f + 0.3f * i)
                    drawLine(c, Offset(x, h * 0.12f), Offset(x, h * 0.88f), sw, StrokeCap.Round)
                    drawCircle(c, 3.6.dp.toPx(), Offset(x, h * ys[i]))
                }
            } else {
                drawCircle(c, w * 0.16f, Offset(w * 0.32f, h * 0.76f))
                drawLine(c, Offset(w * 0.47f, h * 0.76f), Offset(w * 0.47f, h * 0.16f), sw, StrokeCap.Round)
                drawLine(c, Offset(w * 0.47f, h * 0.16f), Offset(w * 0.82f, h * 0.30f), sw, StrokeCap.Round)
            }
        }
        if (selected) Text(label, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC1A1D2E), Color(0xCC10121E))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x08FFFFFF))), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(title: String, sub: String? = null) {
    val on = LocalEqOn.current
    Column {
        Text(title, color = if (on) TextHi else Grey, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PremiumSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(240), label = "sw")
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(54.dp, 31.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(MutableInteractionSource(), null, enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress); onChange(!checked)
            }
    ) {
        Box(Modifier.matchParentSize().background(GreyBg))
        Box(Modifier.matchParentSize().graphicsLayer { alpha = t }.background(Brush.horizontalGradient(Brand)))
        Box(Modifier.align(Alignment.CenterStart).offset(x = 3.dp + 23.dp * t).size(25.dp).clip(CircleShape)
            .background(Color.White))
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val on = LocalEqOn.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    val bgMod = when {
        !on -> Modifier.background(GreyBg)
        selected -> Modifier.background(Brush.horizontalGradient(Accent))
        else -> Modifier.background(Panel2.copy(alpha = 0.85f)).border(1.dp, Line, shape)
    }
    Box(
        Modifier.clip(shape).then(bgMod).clickable(enabled = on) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }.padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (!on) Grey else if (selected) Color.White else Color(0xFFC9CCDD), fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun GroupTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(MutableInteractionSource(), null, onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = if (selected) TextHi else Dim, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.height(3.dp).width(if (selected) 22.dp else 0.dp).clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(Accent)))
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).border(1.dp, Line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = Color(0xFFC9CCDD), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (primary && enabled) Modifier.background(Brush.horizontalGradient(Accent))
    else Modifier.background(GreyBg).border(1.dp, Line, shape)
    Box(
        Modifier.fillMaxWidth().clip(shape).then(bg).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Color.White else Grey, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

/** Custom gradient slider (tap or drag). */
@Composable
private fun PSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val on = LocalEqOn.current
    val cur by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    fun at(x: Float, w: Float) { cur(range.start + (x / w).coerceIn(0f, 1f) * span) }
    Canvas(
        Modifier.fillMaxWidth().height(34.dp)
            .pointerInput(on, range) { if (on) detectTapGestures { at(it.x, size.width.toFloat()) } }
            .pointerInput(on, range) {
                if (on) detectHorizontalDragGestures(
                    onDragStart = { at(it.x, size.width.toFloat()) },
                    onHorizontalDrag = { c, _ -> c.consume(); at(c.position.x, size.width.toFloat()) }
                )
            }
    ) {
        val pad = 12.dp.toPx()
        val w = size.width - pad * 2
        val th = 6.dp.toPx()
        val cy = size.height / 2
        drawRoundRect(Color(0x1FFFFFFF), Offset(pad, cy - th / 2), Size(w, th), CornerRadius(th / 2))
        val ax = (w * frac).coerceAtLeast(th)
        drawRoundRect(
            if (on) Brush.horizontalGradient(Brand, startX = pad, endX = pad + w) else Brush.horizontalGradient(listOf(Grey, Grey)),
            Offset(pad, cy - th / 2), Size(ax, th), CornerRadius(th / 2)
        )
        val tx = pad + w * frac
        if (on) drawCircle(Violet.copy(alpha = 0.30f), 16.dp.toPx(), Offset(tx, cy))
        drawCircle(if (on) Color.White else Color(0xFF8A8FA8), 10.dp.toPx(), Offset(tx, cy))
        drawCircle(if (on) Violet else Grey, 4.dp.toPx(), Offset(tx, cy))
    }
}

@Composable
private fun ParamRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val on = LocalEqOn.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (on) TextHi else Grey, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, color = if (on) Cyan else Grey, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        PSlider(v, range, onChange)
    }
}

@Composable
private fun Bars(active: Boolean, size: Int = 22) {
    val inf = rememberInfiniteTransition(label = "bars")
    val a = inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = inf.animateFloat(0.2f, 1f, infiniteRepeatable(tween(690, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c = inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    val d = inf.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    Canvas(Modifier.size(size.dp)) {
        val hs = floatArrayOf(a.value, b.value, c.value, d.value)
        val bw = this.size.width / 7f
        for (i in 0..3) {
            val h = this.size.height * (if (active) hs[i] else 0.28f)
            drawRoundRect(
                Brush.verticalGradient(listOf(Cyan, Violet), startY = this.size.height - h, endY = this.size.height),
                Offset(i * bw * 2f, this.size.height - h), Size(bw, h), CornerRadius(bw / 2)
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

// ---------- EQ screen ----------
@Composable
fun EqScreen() {
    val ctx = LocalContext.current
    val ui by EqEngine.ui.collectAsState()
    val now by NowPlayingState.now.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(tick) { OutputState.refresh(ctx) }
    val out by OutputState.now.collectAsState()
    val caps = remember(tick, out) { spatialCaps(ctx) }
    val binaural = remember(tick, out, ui.virt) { EqEngine.binauralSupported() }
    val off = Devices.offsetOf(ui.device)

    CompositionLocalProvider(LocalEqOn provides ui.enabled) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            // header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)))
                Column(Modifier.weight(1f)) {
                    Text("Super EQ", style = TextStyle(brush = Brush.horizontalGradient(Brand), fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = Poppins))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(if (!ui.enabled) Grey else if (EqEngine.isActive()) Good else Amber)
                        Text(
                            if (!ui.enabled) "ปิดการใช้งาน" else if (EqEngine.isActive()) "เอฟเฟกต์ทำงานอยู่" else "รอเสียง · ลองเล่นเพลง",
                            color = Dim, fontSize = 12.sp
                        )
                    }
                }
                PremiumSwitch(ui.enabled) { EqEngine.setEnabled(it) }
            }

            NowCard(now)
            PermCard(tick) { tick++ }

            // output + device
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (out.bluetooth && ui.enabled) Cyan else Grey)
                    Text(
                        "${out.label}${if (out.name.isNotBlank()) " · " + out.name else ""}" +
                            if (out.bluetooth) " · จำค่า EQ แยกตามอุปกรณ์" else "",
                        color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Devices.list) { d -> Pill(d.name, ui.device == d.name) { EqEngine.setDevice(d.name) } }
                }
            }

            // equalizer
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Equalizer", color = if (ui.enabled) TextHi else Grey, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!ui.enabled) "ปิดอยู่" else ui.preset.ifBlank { "ไม่ใช้ Preset" },
                            style = TextStyle(brush = Brush.horizontalGradient(if (ui.enabled) Brand else listOf(Grey, Grey)),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Poppins)
                        )
                    }
                    if (ui.enabled) GhostButton("รีเซ็ต") { EqEngine.setPreset(PRESETS[0]) }
                }
                EqCurve(ui.gains, off) { i, db -> EqEngine.setBand(i, db) }
                Text("ลากนิ้วบนกราฟเพื่อปรับแต่ละย่านความถี่ (±12 dB)", color = Dim, fontSize = 11.sp)
            }

            // tone
            GlassCard {
                SectionTitle("Tone")
                ParamRow("Loudness / Preamp", "${"%.1f".format(ui.preamp)} dB", ui.preamp, 0f..12f) { EqEngine.setPreamp(it) }
                ParamRow("Bass Boost", "${ui.bass / 10}%", ui.bass.toFloat(), 0f..1000f) { EqEngine.setBass(it.roundToInt()) }
                ParamRow("Virtualizer", "${ui.virt / 10}%", ui.virt.toFloat(), 0f..1000f) { EqEngine.setVirt(it.roundToInt()) }
            }

            SpatialCard()
            CaptureCard()

            // capabilities
            GlassCard {
                SectionTitle("ความสามารถของเครื่องนี้")
                CapRow(Good, "EQ / Bass / Loudness ใช้ได้กับทุกอุปกรณ์")
                CapRow(if (binaural) Good else Amber, "Binaural ของ Android" + if (binaural) "" else " · ไม่รองรับ ใช้ Virtualizer ปกติ")
                CapRow(if (caps.system) Good else Bad, "Spatial Audio ของระบบ" + if (caps.system) (if (caps.systemOn) " · เปิดอยู่" else " · ปิดอยู่ (เปิดในตั้งค่า)") else "")
                CapRow(if (caps.headTracking) Good else Bad, "Head tracking")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("ตั้งค่าเสียง") { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                    GhostButton("Bluetooth / Codec") { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            }

            PresetBrowser(ui.preset)
            Text("Preset เป็นค่าตั้งต้นที่จูนจากแนวคิดอ้างอิงทั่วไป ไม่ใช่ค่าวัดจริงของแต่ละรุ่น", color = Dim, fontSize = 11.sp)
            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun CapRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color)
        Text(text, color = Color(0xFFC9CCDD), fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetBrowser(current: String) {
    val on = LocalEqOn.current
    var group by remember { mutableStateOf(PRESET_GROUPS.first()) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val list = remember(group, q) {
        if (q.isEmpty()) PRESETS.filter { it.group == group }
        else PRESETS.filter { it.name.contains(q, true) || it.group.contains(q, true) }
    }
    GlassCard {
        SectionTitle("Presets", "${PRESETS.size} ชุด · แตะซ้ำเพื่อยกเลิก")
        SearchField(query, on) { query = it }
        if (q.isEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(PRESET_GROUPS) { g -> GroupTab(g, g == group) { group = g } }
            }
        } else {
            Text("พบ ${list.size} รายการ", color = Dim, fontSize = 12.sp)
        }
        if (list.isEmpty()) Text("ไม่พบ Preset", color = Dim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p -> Pill(p.name, current == p.name) { EqEngine.togglePreset(p) } }
        }
    }
}

@Composable
private fun SearchField(value: String, on: Boolean, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0x14FFFFFF)).border(1.dp, Line, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.size(16.dp)) {
            val sw = 1.8.dp.toPx()
            drawCircle(Dim, size.width * 0.34f, Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(sw))
            drawLine(Dim, Offset(size.width * 0.66f, size.height * 0.66f), Offset(size.width * 0.92f, size.height * 0.92f), sw, StrokeCap.Round)
        }
        BasicTextField(
            value, onChange, enabled = on, singleLine = true,
            textStyle = TextStyle(color = TextHi, fontSize = 14.sp, fontFamily = Poppins),
            cursorBrush = SolidColor(Violet), modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("ค้นหา Preset เช่น Rock, 8D, ลูกทุ่ง", color = Dim, fontSize = 14.sp)
                    inner()
                }
            }
        )
    }
}

@Composable
private fun NowCard(n: NowPlaying) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0x334D7CFF), Color(0x339B5CFF)))),
                contentAlignment = Alignment.Center
            ) { Bars(n.pkg.isNotEmpty() && n.playing, 24) }
            Column(Modifier.weight(1f)) {
                if (n.pkg.isEmpty()) {
                    Text("ยังไม่มีเพลงเล่นอยู่", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("เปิดเพลงในแอปไหนก็ได้ จะแสดงที่นี่", color = Dim, fontSize = 12.sp)
                } else {
                    Text(if (n.playing) "กำลังเล่นใน ${n.app}" else "หยุดใน ${n.app}", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(n.title.ifBlank { "(ไม่ทราบชื่อเพลง)" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.artist.isNotBlank()) Text(n.artist, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PermCard(tick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    val hasAudio = remember(tick) { ContextCompat.checkSelfPermission(ctx, audioPerm) == 0 }
    val hasListener = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    if (hasAudio && hasListener) return
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brush.horizontalGradient(listOf(Color(0x339B5CFF), Color(0x33FF3DDB))))
            .border(1.dp, Color(0x40FFFFFF), shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ต้องอนุญาตก่อนใช้งานเต็มรูปแบบ", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (!hasAudio) ActionButton("อนุญาตเข้าถึงเพลงในเครื่อง") {
            val p = mutableListOf(audioPerm)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
            launcher.launch(p.toTypedArray())
        }
        if (!hasListener) ActionButton("เปิด Notification Access", primary = hasAudio) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}

// ---------- EQ curve ----------
@Composable
private fun EqCurve(gains: List<Float>, off: List<Float>, onChange: (Int, Float) -> Unit) {
    val on = LocalEqOn.current
    var active by remember { mutableIntStateOf(-1) }
    val finalDb = gains.indices.map { gains[it] + off[it] }
    Canvas(
        Modifier.fillMaxWidth().height(250.dp).pointerInput(on) {
            if (!on) return@pointerInput
            detectDragGestures(onDragEnd = { active = -1 }, onDragCancel = { active = -1 }) { change, _ ->
                val w = size.width.toFloat(); val h = size.height.toFloat() - 26.dp.toPx()
                val i = ((change.position.x / w) * 10 - 0.5f).roundToInt().coerceIn(0, 9)
                val db = (0.5f - change.position.y / h) * 24f
                active = i
                onChange(i, db.coerceIn(-12f, 12f))
                change.consume()
            }
        }
    ) {
        val w = size.width; val h = size.height - 26.dp.toPx()
        fun x(i: Int) = w * (i + 0.5f) / 10
        fun y(db: Float) = h / 2 - (db.coerceIn(-12f, 12f) / 12f) * (h / 2)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))
        val lp = android.graphics.Paint().apply { color = 0xFF6C718A.toInt(); textSize = 24f; isAntiAlias = true }
        for (d in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(if (d == 0f) Color(0x40FFFFFF) else Color(0x14FFFFFF), Offset(0f, y(d)), Offset(w, y(d)),
                if (d == 0f) 2f else 1f, pathEffect = if (d == 0f) null else dash)
            drawContext.canvas.nativeCanvas.drawText(if (d > 0) "+${d.toInt()}" else "${d.toInt()}", 2f, y(d) - 6f, lp)
        }
        for (i in 0 until 10) drawLine(Color(if (i == active) 0x33FFFFFF else 0x0DFFFFFF), Offset(x(i), 0f), Offset(x(i), h), 1f)

        val colors = if (on) Brand else listOf(Grey, Grey, Grey)
        val path = Path().apply {
            moveTo(x(0), y(finalDb[0]))
            for (i in 1 until 10) {
                val mx = (x(i - 1) + x(i)) / 2
                cubicTo(mx, y(finalDb[i - 1]), mx, y(finalDb[i]), x(i), y(finalDb[i]))
            }
        }
        val fill = Path().apply { addPath(path); lineTo(x(9), h / 2); lineTo(x(0), h / 2); close() }
        drawPath(fill, Brush.verticalGradient(listOf(colors[1].copy(alpha = 0.40f), Color.Transparent)))
        drawPath(path, Brush.horizontalGradient(colors.map { it.copy(alpha = 0.22f) }), style = Stroke(width = 16f, cap = StrokeCap.Round))
        drawPath(path, Brush.horizontalGradient(colors), style = Stroke(width = 5f, cap = StrokeCap.Round))

        val vp = android.graphics.Paint().apply {
            color = 0xFFFFFFFF.toInt(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        val fp = android.graphics.Paint().apply {
            color = 0xFF8A8FA8.toInt(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        for (i in 0 until 10) {
            val c = colors[(i * 2 / 9).coerceIn(0, 2)]
            val r = if (i == active) 13f else 8f
            drawCircle(c.copy(alpha = 0.28f), r + 9f, Offset(x(i), y(finalDb[i])))
            drawCircle(Color.White, r, Offset(x(i), y(finalDb[i])))
            drawCircle(c, r - 4f, Offset(x(i), y(finalDb[i])))
            val f = FREQS[i]
            drawContext.canvas.nativeCanvas.drawText(if (f >= 1000) "${f / 1000}k" else "$f", x(i), size.height - 4f, fp)
            if (i == active) {
                val v = finalDb[i]
                drawContext.canvas.nativeCanvas.drawText((if (v > 0) "+" else "") + "%.1f dB".format(v), x(i).coerceIn(60f, w - 60f), y(v) - 34f, vp)
            }
        }
    }
}

// ---------- Spatial / capture ----------
@Composable
fun SpatialCard() {
    val mode by SParams.mode.collectAsState()
    val width by SParams.width.collectAsState()
    val room by SParams.room.collectAsState()
    val period by SParams.period.collectAsState()
    GlassCard {
        SectionTitle(
            "Spatial Audio / 8D",
            "เวทีกว้าง + เสียงรอบทิศทาง โดยไม่ลดเสียงดนตรีหรือเสียงร้อง (ย่านกลางไม่ถูกแตะ) " +
                "ใช้กับเพลงในแท็บ Library ได้ทันที ส่วนแอปอื่นกดเริ่มด้านล่าง"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(listOf("ปิด", "Spatial", "8D")) { i, t -> Pill(t, mode == i) { Player.setSpatialMode(i) } }
        }
        if (mode != SParams.OFF) {
            ParamRow("ความกว้างเวที", "${(width * 100).roundToInt()}%", width, 0f..1f) { SParams.width.value = it }
            ParamRow("ห้อง / รอบทิศ", "${(room * 100).roundToInt()}%", room, 0f..1f) { SParams.room.value = it }
        }
        if (mode == SParams.EIGHT_D) {
            ParamRow("ความเร็ววน", "รอบละ ${"%.0f".format(period)} วินาที", period, 2f..20f) { SParams.period.value = it }
            Text("ใส่หูฟังจะได้ผล 8D ชัดที่สุด", color = Dim, fontSize = 11.sp)
        }
    }
}

@Composable
fun CaptureCard() {
    val ctx = LocalContext.current
    val active by CaptureService.active.collectAsState()
    val err by CaptureService.error.collectAsState()
    val g by SParams.gain.collectAsState()
    val q by SParams.quiet.collectAsState()
    val on = LocalEqOn.current

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val d = res.data
        if (res.resultCode == Activity.RESULT_OK && d != null) {
            if (SParams.mode.value == SParams.OFF) SParams.mode.value = SParams.SPATIAL
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACT_START)
                    .putExtra("code", res.resultCode).putExtra("data", d)
            )
        }
    }
    fun askProjection() {
        projLauncher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askProjection()
    }

    GlassCard {
        SectionTitle("ใช้กับแอปอื่น (ทดลอง)", "YouTube Music ฯลฯ กดเริ่ม อนุญาตการอัดเสียง แล้วเปิดเพลงในแอปอื่นได้เลย")
        ActionButton(if (active) "หยุด" else "เริ่ม Spatial / 8D", primary = !active, enabled = on) {
            if (active) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACT_STOP))
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == 0) askProjection()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (err.isNotBlank()) Text(err, color = Bad, fontSize = 12.sp)
        ParamRow("ขยายเสียงชดเชย", "x${"%.1f".format(g)}", g, 1f..4f) { SParams.gain.value = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ลดเสียงต้นฉบับ", color = if (on) TextHi else Grey, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("ลดเสียงแอปต้นทาง แล้วเล่นเสียงที่ประมวลผลทางช่อง Alarm (ปรับก่อนกดเริ่ม)", color = Dim, fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            PremiumSwitch(q, on) { SParams.quiet.value = it }
        }
    }
}

// ---------- Library ----------
@Composable
fun LibraryScreen() {
    val songs by Player.songs.collectAsState()
    val cur by Player.current.collectAsState()
    val playing by Player.playing.collectAsState()
    val mode by SParams.mode.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Player.scan() } }
    val grads = listOf(listOf(Cyan, Blue), listOf(Blue, Violet), listOf(Violet, Pink), listOf(Pink, Amber))

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Library", style = TextStyle(brush = Brush.horizontalGradient(Brand), fontSize = 28.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Poppins))
                    Text("${songs.size} เพลงในเครื่อง", color = Dim, fontSize = 12.sp)
                }
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(Color(0x334D7CFF), Color(0x33FF3DDB)))),
                            contentAlignment = Alignment.Center) { Bars(playing, 26) }
                        Column(Modifier.weight(1f)) {
                            Text(cur?.title ?: "เลือกเพลงจากเครื่อง", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cur?.artist?.ifBlank { null } ?: "แตะเพลงด้านล่างเพื่อเล่น", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        CtrlButton(0, 52) { Player.prev() }
                        CtrlButton(if (playing) 2 else 1, 68, primary = true) { Player.toggle() }
                        CtrlButton(3, 52) { Player.next() }
                    }
                    Text("เสียงรอบทิศทาง", color = Dim, fontSize = 11.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(listOf("ปิด", "Spatial", "8D")) { i, t -> Pill(t, mode == i) { Player.setSpatialMode(i) } }
                    }
                }
                if (songs.isEmpty()) Text("ไม่พบเพลง หรือยังไม่ได้อนุญาตเข้าถึงไฟล์ (ไปแท็บ Equalizer แล้วกดอนุญาต)", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
            }
        }
        itemsIndexed(songs) { i, s ->
            val isCur = cur?.id == s.id
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (isCur) Color(0x229B5CFF) else Color.Transparent)
                    .clickable { Player.play(i) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(grads[i % grads.size])),
                    contentAlignment = Alignment.Center) {
                    if (isCur) Bars(playing, 18)
                    else Text(s.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = if (isCur) Color.White else TextHi, fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist.ifBlank { "ไม่ทราบศิลปิน" }, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** kind: 0 prev, 1 play, 2 pause, 3 next */
@Composable
private fun CtrlButton(kind: Int, dia: Int, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) Modifier.background(Brush.linearGradient(Accent)) else Modifier.background(Panel2).border(1.dp, Line, CircleShape)
    Box(Modifier.size(dia.dp).clip(CircleShape).then(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((dia * 0.36f).dp)) {
            val w = this.size.width; val h = this.size.height
            val c = Color.White
            fun tri(x0: Float, x1: Float) = Path().apply {
                moveTo(x0, h * 0.5f); lineTo(x1, h * 0.05f); lineTo(x1, h * 0.95f); close()
            }
            when (kind) {
                1 -> drawPath(Path().apply { moveTo(w * 0.15f, 0f); lineTo(w, h * 0.5f); lineTo(w * 0.15f, h); close() }, c, style = Fill)
                2 -> {
                    drawRoundRect(c, Offset(w * 0.1f, 0f), Size(w * 0.28f, h), CornerRadius(w * 0.08f))
                    drawRoundRect(c, Offset(w * 0.62f, 0f), Size(w * 0.28f, h), CornerRadius(w * 0.08f))
                }
                0 -> {
                    drawPath(tri(w * 0.9f, w * 0.25f), c)
                    drawRoundRect(c, Offset(0f, h * 0.05f), Size(w * 0.14f, h * 0.9f), CornerRadius(w * 0.05f))
                }
                else -> {
                    drawPath(Path().apply { moveTo(w * 0.1f, h * 0.05f); lineTo(w * 0.75f, h * 0.5f); lineTo(w * 0.1f, h * 0.95f); close() }, c)
                    drawRoundRect(c, Offset(w * 0.86f, h * 0.05f), Size(w * 0.14f, h * 0.9f), CornerRadius(w * 0.05f))
                }
            }
        }
    }
}
