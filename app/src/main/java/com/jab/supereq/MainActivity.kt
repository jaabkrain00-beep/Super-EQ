package com.jab.supereq

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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

private val Bg = Color(0xFF0A0A12)
private val CardC = Color(0xFF171425)
private val Gold = Color(0xFFFFC857)
private val Violet = Color(0xFF8B6CFF)
private val Dim = Color(0xFF8E8AA3)

// Latin glyphs use Poppins; Thai glyphs fall back to the phone's modern system Thai font.
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
        colorScheme = darkColorScheme(primary = Gold, background = Bg, surface = CardC),
        typography = appTypography()
    ) {
      CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = Poppins))) {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F0D18)) {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("🎚") }, label = { Text("EQ") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("🎵") }, label = { Text("เพลงในเครื่อง") })
            }
        }) { pad ->
            Box(
                Modifier.padding(pad).fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1C1336), Bg)))
            ) { if (tab == 0) EqScreen() else LibraryScreen() }
        }
      }
    }
}

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SUPER EQ", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Gold, letterSpacing = 5.sp)
                Text(if (EqEngine.isActive()) "เอฟเฟกต์ทำงานอยู่" else "ยังจับ audio ไม่ได้ (ลองเล่นเพลง)", color = Dim, fontSize = 12.sp)
            }
            Switch(ui.enabled, { EqEngine.setEnabled(it) })
        }

        NowCard(now)
        PermCard(tick) { tick++ }

        Text(
            "เอาต์พุต: ${out.label}${if (out.name.isNotBlank()) " · " + out.name else ""}" +
                if (out.bluetooth) "  (จำค่า EQ แยกต่อรุ่นให้อัตโนมัติ)" else "",
            color = if (out.bluetooth) Gold else Dim, fontSize = 12.sp
        )

        Label("อุปกรณ์ฟัง (ลำโพง / หูฟัง)")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Devices.list) { d -> Pill("${d.icon} ${d.name}", ui.device == d.name) { EqEngine.setDevice(d.name) } }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardC)) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text("Preset: ${ui.preset}", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("รีเซ็ต", color = Gold, modifier = Modifier.clickable { EqEngine.setPreset(PRESETS[0]) })
                }
                EqCurve(ui.gains, off) { i, db -> EqEngine.setBand(i, db) }
                Text("ลากนิ้วบนกราฟเพื่อปรับแต่ละย่านความถี่ (±12 dB)", color = Dim, fontSize = 11.sp)
            }
        }

        Knob("Loudness / Preamp", "${"%.1f".format(ui.preamp)} dB", ui.preamp, 0f..12f) { EqEngine.setPreamp(it) }
        Knob("Bass Boost", "${ui.bass / 10}%", ui.bass.toFloat(), 0f..1000f) { EqEngine.setBass(it.roundToInt()) }
        Knob("Virtualizer (เวทีเสียง)", "${ui.virt / 10}%", ui.virt.toFloat(), 0f..1000f) { EqEngine.setVirt(it.roundToInt()) }

        Label("เสียงรอบทิศทาง / Spatial (ใช้กับหูฟังทุกรุ่น)")
        val mode = EqEngine.spatialMode(ui)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(listOf("ปิด", "Wide", "Surround", "360°")) { i, t -> Pill(t, mode == i) { EqEngine.setSpatial(i) } }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardC)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ความสามารถของเครื่อง + หูฟังนี้", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text((if (true) "✅" else "") + " EQ / Bass / Loudness (ใช้ได้กับทุกอุปกรณ์)", color = Dim, fontSize = 12.sp)
                Text((if (binaural) "✅" else "⚠️") + " Binaural surround ของ Android" + if (binaural) "" else " (เครื่องนี้ไม่รองรับ จะใช้แบบ Virtualizer ธรรมดา)", color = Dim, fontSize = 12.sp)
                Text((if (caps.system) "✅" else "❌") + " Spatial Audio ของระบบ" + if (caps.system) (if (caps.systemOn) " (เปิดอยู่)" else " (ปิดอยู่ ต้องเปิดในตั้งค่า)") else "", color = Dim, fontSize = 12.sp)
                Text((if (caps.headTracking) "✅" else "❌") + " Head tracking (หันหัวแล้วเสียงอยู่กับที่)", color = Dim, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) { Text("ตั้งค่าเสียง", color = Gold) }
                    TextButton({ ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("ตั้งค่า Bluetooth / Codec", color = Gold) }
                }
            }
        }

        CaptureCard()

        PRESET_GROUPS.forEach { g ->
            Label(g)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PRESETS.filter { it.group == g }) { p -> Pill(p.name, ui.preset == p.name) { EqEngine.setPreset(p) } }
            }
        }
        Text(
            "หมายเหตุ: Preset เป็นค่าตั้งต้นที่จูนจากแนวคิดอ้างอิงทั่วไป ไม่ใช่ค่าวัดจริงของแต่ละรุ่น",
            color = Dim, fontSize = 11.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable fun Label(t: String) = Text(t, color = Dim, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Gold else CardC)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun Knob(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(title, color = Color.White, modifier = Modifier.weight(1f)); Text(value, color = Gold) }
        Slider(v, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Violet))
    }
}

@Composable
fun NowCard(n: NowPlaying) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardC)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (n.pkg.isEmpty()) {
                Text("ยังไม่มีเพลงเล่นอยู่", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("เปิดเพลงในแอปไหนก็ได้ แล้วจะแสดงที่นี่ (ต้องอนุญาต Notification Access)", color = Dim, fontSize = 12.sp)
            } else {
                Text(if (n.playing) "▶ กำลังเล่นใน ${n.app}" else "⏸ หยุดใน ${n.app}", color = Gold, fontSize = 12.sp)
                Text(n.title.ifBlank { "(ไม่ทราบชื่อเพลง)" }, color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.artist.isNotBlank()) Text(n.artist, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PermCard(tick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    val hasAudio = remember(tick) { ContextCompat.checkSelfPermission(ctx, audioPerm) == 0 }
    val hasListener = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    if (hasAudio && hasListener) return
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F3D))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ต้องอนุญาตก่อนใช้งานเต็มรูปแบบ", color = Gold, fontWeight = FontWeight.Bold)
            if (!hasAudio) Button({
                val p = mutableListOf(audioPerm)
                if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
                launcher.launch(p.toTypedArray())
            }) { Text("อนุญาตเข้าถึงเพลงในเครื่อง") }
            if (!hasListener) Button({
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("เปิด Notification Access (ดูว่าเล่นเพลงอะไร/แอปไหน)") }
        }
    }
}

@Composable
fun EqCurve(gains: List<Float>, off: List<Float>, onChange: (Int, Float) -> Unit) {
    val finalDb = gains.indices.map { gains[it] + off[it] }
    Canvas(
        Modifier.fillMaxWidth().height(230.dp).pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val w = size.width.toFloat(); val h = size.height.toFloat() - 24.dp.toPx()
                val i = ((change.position.x / w) * 10 - 0.5f).roundToInt().coerceIn(0, 9)
                val db = (0.5f - change.position.y / h) * 24f
                onChange(i, db.coerceIn(-12f, 12f))
                change.consume()
            }
        }
    ) {
        val w = size.width; val h = size.height - 24.dp.toPx()
        fun x(i: Int) = w * (i + 0.5f) / 10
        fun y(db: Float) = h / 2 - (db.coerceIn(-12f, 12f) / 12f) * (h / 2)
        for (d in listOf(-12f, -6f, 0f, 6f, 12f))
            drawLine(Color(0x22FFFFFF), Offset(0f, y(d)), Offset(w, y(d)), if (d == 0f) 2f else 1f)
        val path = Path().apply {
            moveTo(x(0), y(finalDb[0]))
            for (i in 1 until 10) {
                val mx = (x(i - 1) + x(i)) / 2
                cubicTo(mx, y(finalDb[i - 1]), mx, y(finalDb[i]), x(i), y(finalDb[i]))
            }
        }
        val fill = Path().apply { addPath(path); lineTo(x(9), h / 2); lineTo(x(0), h / 2); close() }
        drawPath(fill, Brush.verticalGradient(listOf(Violet.copy(alpha = 0.45f), Color.Transparent)))
        drawPath(path, Brush.horizontalGradient(listOf(Violet, Gold)), style = Stroke(width = 6f))
        val paint = android.graphics.Paint().apply { color = 0xFF8E8AA3.toInt(); textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in 0 until 10) {
            drawCircle(Gold, 11f, Offset(x(i), y(finalDb[i])))
            val f = FREQS[i]
            drawContext.canvas.nativeCanvas.drawText(if (f >= 1000) "${f / 1000}k" else "$f", x(i), size.height - 4f, paint)
        }
    }
}

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val songs by Player.songs.collectAsState()
    val cur by Player.current.collectAsState()
    val playing by Player.playing.collectAsState()
    val kOn by Player.karaoke.collectAsState()
    val kStrength by KParams.strength.collectAsState()
    val kRange by KParams.range.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Player.scan() } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cur?.title ?: "เลือกเพลงจากเครื่อง", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cur?.artist ?: "${songs.size} เพลง", color = Dim, fontSize = 12.sp, maxLines = 1)
            }
            TextButton({ Player.prev() }) { Text("⏮", fontSize = 22.sp) }
            TextButton({ Player.toggle() }) { Text(if (playing) "⏸" else "▶", fontSize = 26.sp, color = Gold) }
            TextButton({ Player.next() }) { Text("⏭", fontSize = 22.sp) }
        }
        Card(
            Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardC)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ตัดเสียงร้อง (Karaoke)", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("เหลือดนตรี ใช้กับเพลงในแท็บนี้เท่านั้น", color = Dim, fontSize = 11.sp)
                    }
                    Switch(kOn, { Player.setKaraoke(it) })
                }
                if (kOn) {
                    Row { Text("ความแรง", color = Color.White, modifier = Modifier.weight(1f)); Text("${(kStrength * 100).roundToInt()}%", color = Gold) }
                    Slider(kStrength, { KParams.strength.value = it }, valueRange = 0.3f..1f,
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Violet))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(listOf("กว้าง (ทั่วไป)", "เสียงผู้ชาย", "เสียงผู้หญิง")) { i, t ->
                            Pill(t, kRange == i) { KParams.range.value = i }
                        }
                    }
                    Text("ถ้าเสียงร้องยังเหลือ ลองเพิ่มความแรง หรือสลับช่วงเสียง", color = Dim, fontSize = 11.sp)
                }
            }
        }
        if (songs.isEmpty()) {
            Text("ไม่พบเพลง หรือยังไม่ได้อนุญาตเข้าถึงไฟล์ (ไปแท็บ EQ แล้วกดอนุญาต)", color = Dim, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(songs) { i, s ->
                Column(Modifier.fillMaxWidth().clickable { Player.play(i) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(s.title, color = if (cur?.id == s.id) Gold else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = Dim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}


@Composable
fun CaptureCard() {
    val ctx = LocalContext.current
    val active by CaptureService.active.collectAsState()
    val err by CaptureService.error.collectAsState()
    val s by KParams.strength.collectAsState()
    val r by KParams.range.collectAsState()
    val g by KParams.gain.collectAsState()
    val q by KParams.quiet.collectAsState()

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val d = res.data
        if (res.resultCode == Activity.RESULT_OK && d != null) {
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

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardC)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ตัดเสียงร้องจากแอปอื่น (ทดลอง)", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "ใช้กับ YouTube Music ฯลฯ กดเริ่ม แล้วอนุญาตการอัดเสียง จากนั้นเปิดเพลงในแอปอื่นได้เลย",
                color = Dim, fontSize = 11.sp
            )
            Button({
                if (active) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACT_STOP))
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == 0) askProjection()
                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (active) "หยุดตัดเสียงร้อง" else "เริ่มตัดเสียงร้อง") }
            if (err.isNotBlank()) Text(err, color = Color(0xFFFF8A80), fontSize = 12.sp)

            Row { Text("ความแรง", color = Color.White, modifier = Modifier.weight(1f)); Text("${(s * 100).roundToInt()}%", color = Gold) }
            Slider(s, { KParams.strength.value = it }, valueRange = 0.3f..1f,
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Violet))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("กว้าง (ทั่วไป)", "เสียงผู้ชาย", "เสียงผู้หญิง")) { i, t ->
                    Pill(t, r == i) { KParams.range.value = i }
                }
            }
            Row { Text("ขยายเสียงชดเชย", color = Color.White, modifier = Modifier.weight(1f)); Text("x${"%.1f".format(g)}", color = Gold) }
            Slider(g, { KParams.gain.value = it }, valueRange = 1f..8f,
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Violet))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ลดเสียงต้นฉบับ", color = Color.White)
                    Text("ลดเสียงของแอปต้นทางให้เบา แล้วเล่นเสียงที่ตัดแล้วทางช่อง Alarm (ปรับก่อนกดเริ่ม)", color = Dim, fontSize = 11.sp)
                }
                Switch(q, { KParams.quiet.value = it })
            }
        }
    }
}
