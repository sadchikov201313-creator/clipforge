package com.clipforge.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

data class Seg(val a: Long, val b: Long)

val Cyan = Color(0xFF25F4EE)
val Grey = Color(0xFFB4B4C6)

fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheme = darkColorScheme(
            primary = Cyan, onPrimary = Color.Black,
            background = Color.Black, surface = Color.Black,
            onBackground = Color.White, onSurface = Color.White,
            surfaceVariant = Color(0xFF2A2A38), onSurfaceVariant = Grey
        )
        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) { Editor() }
            }
        }
    }
}

@Composable
fun Tool(icon: String, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (!enabled) Color(0xFF555566) else if (on) Cyan else Color.White
    Column(
        modifier = Modifier.width(70.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Text(label, fontSize = 10.sp, color = c, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 12.sp)
    }
}

@Composable
fun Labeled(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, fontSize = 13.sp, color = Grey)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
fun Editor() {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var cw by remember { mutableStateOf("1080") }
    var ch by remember { mutableStateOf("1920") }
    var sx by remember { mutableFloatStateOf(1f) }
    var sy by remember { mutableFloatStateOf(1f) }
    var rot by remember { mutableFloatStateOf(0f) }
    var lock by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dur by remember { mutableLongStateOf(0L) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val segs = remember { mutableStateListOf<Seg>() }
    val scroll = rememberScrollState()
    val pxPerMs = LocalDensity.current.density * 0.08f

    val exo = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && dur == 0L && exo.duration > 0) {
                    dur = exo.duration
                    segs.clear()
                    segs.add(Seg(0L, dur))
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            if (scroll.isScrollInProgress) {
                val p = (scroll.value / pxPerMs).toLong()
                if (abs(p - pos) > 10) { pos = p; exo.seekTo(p) }
            } else {
                pos = exo.currentPosition
                if (exo.isPlaying && segs.isNotEmpty() && segs.none { pos >= it.a && pos < it.b }) {
                    val nx = segs.firstOrNull { it.a > pos }
                    if (nx != null) exo.seekTo(nx.a) else { exo.pause(); exo.seekTo(segs.first().a) }
                }
                if (dur > 0) scroll.scrollTo((pos * pxPerMs).toInt().coerceIn(0, scroll.maxValue))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) {
            uri = u; dur = 0L; pos = 0L; segs.clear()
            exo.setMediaItem(MediaItem.fromUri(u))
            exo.prepare()
        }
    }

    val w = ((cw.toIntOrNull() ?: 1080).coerceIn(64, 4096) / 2) * 2
    val h = ((ch.toIntOrNull() ?: 1920).coerceIn(64, 4096) / 2) * 2
    val total = segs.sumOf { it.b - it.a }
    val selIdx = segs.indexOfFirst { pos >= it.a && pos < it.b }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Text("+ Видео", color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text("${w}×${h}", color = Grey, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { uri?.let { exportVideo(ctx, it, segs.toList(), w, h, sx, sy, rot, fill, { s -> status = s }, { b -> busy = b }) } },
                enabled = uri != null && segs.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) { Text("Экспорт") }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan)
        if (status.isNotEmpty()) Text(status, color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))

        // Предпросмотр
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val r = w.toFloat() / h
            val bw = if (maxWidth / r > maxHeight) maxHeight * r else maxWidth
            Box(
                modifier = Modifier.width(bw).height(bw / r).clip(RoundedCornerShape(4.dp)).background(Color(0xFF101014)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { c ->
                        (LayoutInflater.from(c).inflate(R.layout.player_view, null) as PlayerView).also { it.player = exo }
                    },
                    update = { it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = sx, scaleY = sy, rotationZ = rot)
                )
                if (uri == null) Text("Нажми «+ Видео»", color = Grey, fontSize = 14.sp)
            }
        }

        // Время и Play
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("${fmt(pos)} / ${fmt(dur)}", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterStart))
            Box(
                modifier = Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.White)
                    .clickable(enabled = dur > 0) { if (exo.isPlaying) exo.pause() else exo.play() },
                contentAlignment = Alignment.Center
            ) { Text(if (playing) "⏸" else "▶", color = Color.Black, fontSize = 18.sp) }
            Text("Итог ${fmt(total)}", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterEnd))
        }

        // Таймлайн
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(84.dp).background(Color(0xFF0B0B0F))) {
            Row(modifier = Modifier.height(84.dp).horizontalScroll(scroll)) {
                Spacer(Modifier.width(maxWidth / 2))
                Canvas(modifier = Modifier.width((dur * 0.08f).dp).height(84.dp)) {
                    val top = 24.dp.toPx()
                    val hh = 52.dp.toPx()
                    var s = 0L
                    while (s <= dur) {
                        val x = s * pxPerMs
                        val len = if ((s / 1000) % 5 == 0L) 16.dp.toPx() else 10.dp.toPx()
                        drawLine(Color(0x66FFFFFF), Offset(x, 4.dp.toPx()), Offset(x, len), 1.5f)
                        s += 1000
                    }
                    segs.forEach { sg ->
                        val x = sg.a * pxPerMs
                        val wd = (sg.b - sg.a) * pxPerMs
                        drawRoundRect(Color(0xFF2B2B3A), Offset(x, top), Size(wd, hh), CornerRadius(10f))
                        drawRoundRect(Color(0xFF3F3F55), Offset(x, top), Size(wd, hh * 0.35f), CornerRadius(10f))
                        if (pos >= sg.a && pos < sg.b) {
                            drawRoundRect(Color.White, Offset(x, top), Size(wd, hh), CornerRadius(10f), style = Stroke(4f))
                        }
                    }
                }
                Spacer(Modifier.width(maxWidth / 2))
            }
            Box(modifier = Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
        }

        // Панель настроек
        if (panel.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF17171F), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (panel == "canvas") "Формат" else "Размер клипа", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { panel = "" }) { Text("✓", color = Cyan, fontSize = 20.sp) }
                }
                if (panel == "canvas") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("9:16" to (1080 to 1920), "16:9" to (1920 to 1080), "1:1" to (1080 to 1080), "4:5" to (1080 to 1350)).forEach { (n, s) ->
                            FilledTonalButton(onClick = { cw = s.first.toString(); ch = s.second.toString() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(n) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cw, onValueChange = { v -> cw = v.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f), label = { Text("Ширина, px") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = ch, onValueChange = { v -> ch = v.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f), label = { Text("Высота, px") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = lock, onCheckedChange = { lock = it })
                        Text("Сохранять пропорции", fontSize = 13.sp, color = Color.White)
                    }
                    Labeled("Ширина: ${(sx * 100).toInt()}%", sx, 0.2f..3f) { sx = it; if (lock) sy = it }
                    Labeled("Высота: ${(sy * 100).toInt()}%", sy, 0.2f..3f) { sy = it; if (lock) sx = it }
                    Labeled("Поворот: ${rot.toInt()}°", rot, -180f..180f) { rot = it }
                }
            }
        }

        // Нижняя панель инструментов
        Row(modifier = Modifier.fillMaxWidth().background(Color.Black).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            Tool("✂️", "Разрезать", enabled = dur > 0) {
                val i = segs.indexOfFirst { pos - it.a > 200 && it.b - pos > 200 }
                if (i >= 0) { val s = segs[i]; segs[i] = Seg(s.a, pos); segs.add(i + 1, Seg(pos, s.b)) }
            }
            Tool("⏮️", "Обрезать слева", enabled = dur > 0) {
                val n = segs.mapNotNull { if (it.b <= pos) null else Seg(maxOf(it.a, pos), it.b) }.filter { it.b - it.a > 100 }
                segs.clear(); segs.addAll(n)
            }
            Tool("⏭️", "Обрезать справа", enabled = dur > 0) {
                val n = segs.mapNotNull { if (it.a >= pos) null else Seg(it.a, minOf(it.b, pos)) }.filter { it.b - it.a > 100 }
                segs.clear(); segs.addAll(n)
            }
            Tool("🗑️", "Удалить", enabled = selIdx >= 0) { if (selIdx >= 0) segs.removeAt(selIdx) }
            Tool("📐", "Формат", on = panel == "canvas") { panel = if (panel == "canvas") "" else "canvas" }
            Tool("↔️", "Размер", on = panel == "size") { panel = if (panel == "size") "" else "size" }
            Tool("🖼️", "Без чёрных полос", on = fill) { fill = !fill }
        }
    }
}

fun exportVideo(
    ctx: Context, src: Uri, parts: List<Seg>, w: Int, h: Int, sx: Float, sy: Float, rot: Float, fill: Boolean,
    onStatus: (String) -> Unit, onBusy: (Boolean) -> Unit
) {
    val f = File(ctx.cacheDir, "out_${System.currentTimeMillis()}.mp4")
    val layout = if (fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
    val items = parts.map { p ->
        val mi = MediaItem.Builder().setUri(src)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(p.a).setEndPositionMs(p.b).build()
            ).build()
        val fx = Effects(
            emptyList(),
            listOf<Effect>(
                ScaleAndRotateTransformation.Builder().setScale(sx, sy).setRotationDegrees(rot).build(),
                Presentation.createForWidthAndHeight(w, h, layout)
            )
        )
        EditedMediaItem.Builder(mi).setEffects(fx).build()
    }
    val t = Transformer.Builder(ctx)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipForge")
                    }
                    val dst = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    if (dst != null) {
                        ctx.contentResolver.openOutputStream(dst)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                    }
                    onStatus("Готово: Movies/ClipForge/${f.name}")
                } catch (e: Exception) {
                    onStatus("Ошибка сохранения: ${e.message}")
                }
                onBusy(false)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                onStatus("Ошибка экспорта: ${exportException.message}")
                onBusy(false)
            }
        })
        .build()
    onBusy(true)
    onStatus("Экспорт ${w}×${h}…")
    t.start(Composition.Builder(EditedMediaItemSequence(items)).build(), f.absolutePath)
}
