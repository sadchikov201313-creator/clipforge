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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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

data class Seg(val a: Long, val b: Long)

fun fmt(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheme = darkColorScheme(
            primary = Color(0xFF9D8CFF), onPrimary = Color(0xFF1B1240),
            secondary = Color(0xFF5EEAD4),
            background = Color(0xFF0D0D12), surface = Color(0xFF17171F),
            surfaceVariant = Color(0xFF2A2A38),
            onBackground = Color(0xFFECECF4), onSurface = Color(0xFFECECF4),
            onSurfaceVariant = Color(0xFFB4B4C6)
        )
        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Editor() }
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun Labeled(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dur by remember { mutableLongStateOf(0L) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val segs = remember { mutableStateListOf<Seg>() }

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
            delay(100)
            pos = exo.currentPosition
            if (exo.isPlaying && segs.isNotEmpty() && segs.none { pos >= it.a && pos < it.b }) {
                val nx = segs.firstOrNull { it.a > pos }
                if (nx != null) exo.seekTo(nx.a) else { exo.pause(); exo.seekTo(segs.first().a) }
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
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val total = segs.sumOf { it.b - it.a }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ClipForge", style = MaterialTheme.typography.headlineSmall)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            val r = w.toFloat() / h
            val bw = if (maxWidth / r > maxHeight) maxHeight * r else maxWidth
            Box(modifier = Modifier.width(bw).height(bw / r).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                AndroidView(
                    factory = { c ->
                        (LayoutInflater.from(c).inflate(R.layout.player_view, null) as PlayerView).also { it.player = exo }
                    },
                    update = { it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = sx, scaleY = sy, rotationZ = rot)
                )
            }
        }

        Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Выбрать видео") }

        Section("Монтаж") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { if (exo.isPlaying) exo.pause() else exo.play() }, enabled = dur > 0) { Text(if (playing) "⏸" else "▶") }
                Spacer(Modifier.width(10.dp))
                Text("${fmt(pos)} / ${fmt(dur)}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                Text("Итог: ${fmt(total)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))) {
                drawRect(track)
                if (dur > 0) {
                    segs.forEach { sg ->
                        drawRect(accent, Offset(size.width * sg.a / dur, 0f), Size(size.width * (sg.b - sg.a) / dur, size.height))
                    }
                    drawRect(Color.White, Offset(size.width * pos / dur - 2f, 0f), Size(4f, size.height))
                }
            }
            if (dur > 0) {
                Slider(value = pos.toFloat().coerceIn(0f, dur.toFloat()), onValueChange = { pos = it.toLong(); exo.seekTo(it.toLong()) }, valueRange = 0f..dur.toFloat())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val i = segs.indexOfFirst { pos - it.a > 200 && it.b - pos > 200 }
                    if (i >= 0) { val s = segs[i]; segs[i] = Seg(s.a, pos); segs.add(i + 1, Seg(pos, s.b)) }
                }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("✂ Разрезать", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val n = segs.mapNotNull { if (it.b <= pos) null else Seg(maxOf(it.a, pos), it.b) }.filter { it.b - it.a > 100 }
                    segs.clear(); segs.addAll(n)
                }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Обрезать до", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val n = segs.mapNotNull { if (it.a >= pos) null else Seg(it.a, minOf(it.b, pos)) }.filter { it.b - it.a > 100 }
                    segs.clear(); segs.addAll(n)
                }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Обрезать после", fontSize = 12.sp) }
            }
            segs.forEachIndexed { i, sg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.  ${fmt(sg.a)} – ${fmt(sg.b)}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = { exo.seekTo(sg.a) }) { Text("▶") }
                    TextButton(onClick = { segs.removeAt(i) }) { Text("Удалить") }
                }
            }
        }

        Section("Холст") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("9:16" to (1080 to 1920), "16:9" to (1920 to 1080), "1:1" to (1080 to 1080), "4:5" to (1080 to 1350)).forEach { (n, s) ->
                    FilledTonalButton(onClick = { cw = s.first.toString(); ch = s.second.toString() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(n) }
                }
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fill, onCheckedChange = { fill = it })
                Spacer(Modifier.width(10.dp))
                Text("Убрать чёрные полосы (заполнить кадр)", fontSize = 14.sp)
            }
        }

        Section("Размер клипа") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = lock, onCheckedChange = { lock = it })
                Text("Сохранять пропорции", fontSize = 14.sp)
            }
            Labeled("Ширина: ${(sx * 100).toInt()}%", sx, 0.2f..3f) { sx = it; if (lock) sy = it }
            Labeled("Высота: ${(sy * 100).toInt()}%", sy, 0.2f..3f) { sy = it; if (lock) sx = it }
            Labeled("Поворот: ${rot.toInt()}°", rot, -180f..180f) { rot = it }
        }

        Button(
            onClick = { uri?.let { exportVideo(ctx, it, segs.toList(), w, h, sx, sy, rot, fill, { s -> status = s }, { b -> busy = b }) } },
            enabled = uri != null && segs.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
        ) { Text("Экспорт ${w}×${h}") }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp))
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
