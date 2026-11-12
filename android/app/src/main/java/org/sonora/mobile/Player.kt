package org.sonora.mobile

import android.app.Activity
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import uniffi.sonora_mobile.LyricWord
import uniffi.sonora_mobile.Status
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import uniffi.sonora_mobile.LyricsResult
import uniffi.sonora_mobile.PlaybackState
import uniffi.sonora_mobile.SavedKind
import uniffi.sonora_mobile.TrackItem
import kotlin.math.abs

/** Lo que se ve junto a los controles: la letra, la cola o la sesión. */
private enum class Panel { LYRICS, QUEUE, SESSION }

/** Los colores sacados de la portada: los del fondo y si encima va texto claro u oscuro. */
private data class Tint(val top: Color, val bottom: Color, val light: Boolean)

private val neutral = Tint(Color(0xFF2A2A2A), Color(0xFF121212), light = false)

/** Casi negro para portadas claras, blanco para las oscuras. */
private val ink = Color(0xFF151515)

/** Los colores del reproductor según si el fondo es claro u oscuro. */
private fun playerColors(content: Color, light: Boolean) = darkColorScheme(
    primary = content,
    onPrimary = if (light) Color.White else Color.Black,
    surface = if (light) Color(0xFFF2F2F2) else Color(0xFF1E1E1E),
    onSurface = content,
    onSurfaceVariant = content.copy(alpha = 0.7f),
    secondaryContainer = content.copy(alpha = 0.14f),
    onSecondaryContainer = content,
    surfaceVariant = content.copy(alpha = 0.1f),
    surfaceContainer = if (light) Color(0xFFF2F2F2) else Color(0xFF242424),
    surfaceContainerHigh = if (light) Color(0xFFEDEDED) else Color(0xFF2A2A2A),
    outline = content.copy(alpha = 0.5f),
)

/**
 * En TV y tablet el reproductor va en una ventana aparte, que se queda con el foco del control.
 * En el teléfono va encima de la app, en la misma ventana, para respetar las barras del sistema
 * (en una ventana aparte, los botones de abajo quedaban tapados).
 */
@Composable
private fun Shell(wide: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    if (wide) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { content() }
    } else {
        BackHandler(onBack = onClose)
        // se queda con los toques, para que no lleguen a la pantalla de abajo
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) { content() }
    }
}

/** Iconos de la barra de estado oscuros sobre portadas claras, y claros sobre las oscuras. */
@Composable
private fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(dark) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = dark
        controller?.isAppearanceLightNavigationBars = dark
        onDispose {
            status?.let { controller?.isAppearanceLightStatusBars = it }
            navigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

/** Saca de la portada el color dominante (con Palette) y decide si el texto va claro u oscuro. */
@Composable
private fun rememberTint(cover: String?): Tint {
    val context = LocalContext.current
    var tint by remember { mutableStateOf(neutral) }
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(fixUrl(cover)).size(96).allowHardware(false).build()
        val image = SingletonImageLoader.get(context).execute(request).image ?: return@LaunchedEffect
        tint = withContext(Dispatchers.Default) {
            val palette = Palette.from(image.toBitmap()).generate()
            val dominant = Color(palette.getDominantColor(0xFF2A2A2A.toInt()))
            val light = dominant.luminance() > 0.55f
            Tint(
                top = dominant,
                bottom = lerp(dominant, if (light) Color.White else Color.Black, 0.45f),
                light = light,
            )
        }
    }
    return tint
}

/**
 * El reproductor a pantalla completa, sobre la portada desenfocada. En el teléfono la portada,
 * la letra, la cola y la sesión se turnan en el centro; en TV y tablet la portada y los controles
 * van a la izquierda y la letra (o la cola, o la sesión) a la derecha.
 */
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val wide = LocalWide.current
    var panel by rememberSaveable { mutableStateOf(if (wide) Panel.LYRICS else null) }
    var adding by remember { mutableStateOf(false) }
    val play = remember { FocusRequester() }

    Shell(wide, onClose) {
        val tint = rememberTint(Store.state?.track?.cover)
        if (!wide) StatusBarIcons(dark = tint.light)
        val content by animateColorAsState(if (tint.light) ink else Color.White, tween(600), label = "content")
        MaterialTheme(colorScheme = playerColors(content, tint.light)) {
          CompositionLocalProvider(LocalContentColor provides content) {
            val state = Store.state
            val track = state?.track
            if (state == null || track == null) {
                LaunchedEffect(Unit) { onClose() }
                return@CompositionLocalProvider
            }
            val tv = LocalTv.current
            LaunchedEffect(Unit) { if (tv) runCatching { play.requestFocus() } }
            val slug = state.provider ?: Store.selected
            // el teléfono de lado usa las dos columnas de la TV y la tablet
            val configuration = LocalConfiguration.current
            val twoColumns = wide || configuration.screenWidthDp > configuration.screenHeightDp
            Box(Modifier.fillMaxSize()) {
                Backdrop(track.cover, tint)
                if (twoColumns) {
                    Row(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(
                            horizontal = if (wide) 32.dp else 20.dp,
                            vertical = if (wide) 16.dp else 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 40.dp else 24.dp),
                    ) {
                        BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val cover = minOf(maxWidth * 0.8f, maxHeight * 0.48f)
                            Column(
                                Modifier.verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Cover(state.coverLarge ?: track.cover, cover, under = track.cover)
                                Info(track, slug, centered = true)
                                Visualizer(Modifier.fillMaxWidth().height(32.dp))
                                Seek(state)
                                Controls(state, play)
                                RemoteNote(state)
                            }
                        }
                        Column(Modifier.weight(0.58f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                TrackActions(track, slug, onClose, onAdd = { adding = true })
                                IconButton(onClick = onClose) {
                                    Icon(if (wide) Icons.Filled.Close else Icons.Filled.KeyboardArrowDown, "Cerrar")
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                PanelContent(panel ?: Panel.LYRICS, slug, big = true, onAdd = { adding = true })
                            }
                            Row(Modifier.align(Alignment.End)) {
                                Toggles(panel ?: Panel.LYRICS) { panel = it ?: Panel.LYRICS }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp)) }
                            Text(
                                Store.providers.firstOrNull { it.slug == state.provider }?.name?.let { "Reproduciendo desde $it" } ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            TrackActions(track, slug, onClose, onAdd = { adding = true })
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (val shown = panel) {
                                null -> Cover(state.coverLarge ?: track.cover, minOf(maxWidth, maxHeight) - 8.dp, under = track.cover)
                                else -> PanelContent(shown, slug, big = false, onAdd = { adding = true })
                            }
                        }
                        Info(track, slug, centered = false)
                        Visualizer(Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp))
                        Seek(state)
                        Controls(state, play)
                        RemoteNote(state)
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                            Toggles(panel) { panel = it }
                        }
                    }
                }
            }
            if (adding) AddSongs(slug) { adding = false }
          }
        }
    }
}

/**
 * El fondo: desde Android 12, la portada muy desenfocada con un velo; en Android 11 y antes (sin
 * desenfoque en el sistema), un degradado con el color dominante de la portada.
 */
@Composable
private fun Backdrop(cover: String?, tint: Tint) {
    val context = LocalContext.current
    val top by animateColorAsState(tint.top, tween(700), label = "top")
    val bottom by animateColorAsState(tint.bottom, tween(700), label = "bottom")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = remember(cover) { ImageRequest.Builder(context).data(fixUrl(cover)).size(256).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // agrandada, para que el borde del desenfoque quede fuera de la pantalla
                        scaleX = 1.35f
                        scaleY = 1.35f
                    }
                    .blur(90.dp, BlurredEdgeTreatment.Rectangle),
            )
            val veil = if (tint.light) Color.White else Color.Black
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(veil.copy(alpha = if (tint.light) 0.2f else 0.3f), veil.copy(alpha = if (tint.light) 0.4f else 0.6f)),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun Info(track: TrackItem, slug: String, centered: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
            Text(
                track.artists,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // el motor avisa con un estado nuevo cuando termina de cargar las guardadas
        val tick = Store.state
        var mine by remember(track.id) { mutableStateOf<Boolean?>(null) }
        val known = remember(track.id, slug, tick) { Store.engine.trackSaved(slug, track.id) }
        val saved = mine ?: known
        IconButton(onClick = {
            mine = !saved
            Store.save(SavedKind.TRACK, track.id, slug, saved = !saved, quiet = true)
        }) {
            Icon(
                if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (saved) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
            )
        }
    }
}

@Composable
private fun Seek(state: PlaybackState) {
    val tv = LocalTv.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.toFloat().coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, duration),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { Store.engine.seek(it.toLong().coerceAtLeast(0).toULong()) }
                scrub = null
            },
            valueRange = 0f..duration,
            // con el control, la barra atraparía las flechas; se adelanta con ±10 s
            modifier = if (tv) Modifier.focusProperties { canFocus = false } else Modifier,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(scrub?.toLong()?.toULong() ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(clock(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Controls(state: PlaybackState, play: FocusRequester) {
    val tv = LocalTv.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (tv) IconButton(onClick = { skip(state, -10_000) }) { Icon(Icons.Filled.Replay10, "Atrás 10 segundos") }
        IconButton(onClick = { Store.engine.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(40.dp))
        }
        PlayToggle(state.status, big = true, modifier = Modifier.focusRequester(play))
        IconButton(
            onClick = { Store.engine.next() },
            enabled = state.index + 1u < state.queueLength,
            modifier = Modifier.size(56.dp),
        ) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(40.dp)) }
        if (tv) IconButton(onClick = { skip(state, 10_000) }) { Icon(Icons.Filled.Forward10, "Adelante 10 segundos") }
    }
}

/** En «solo controlar» se avisa de que la música suena en otro lado. El volumen lo lleva el teléfono. */
@Composable
private fun RemoteNote(state: PlaybackState) {
    if (!state.remote) return
    Text(
        "Controlas la sesión: esto suena en los otros dispositivos.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Barras que siguen la música (32 bandas, de graves a agudos), leídas del motor unas 30 veces
 * por segundo mientras se ven. Al salir, el motor deja de analizar.
 */
@Composable
private fun Visualizer(modifier: Modifier) {
    var bands by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(Unit) {
        try {
            while (true) {
                bands = withContext(Dispatchers.Default) { Store.engine.spectrum() }.toFloatArray()
                delay(33)
            }
        } finally {
            Store.engine.spectrumStop()
        }
    }
    val color = LocalContentColor.current
    Canvas(modifier) {
        val count = bands.size
        if (count == 0) return@Canvas
        val gap = 3.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val least = 2.dp.toPx()
        bands.forEachIndexed { index, level ->
            val height = (level.coerceIn(0f, 1f) * size.height).coerceAtLeast(least)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.5f * level.coerceIn(0f, 1f)),
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2, width / 2),
            )
        }
    }
}

/** Letra, cola y sesión. En el teléfono, volver a tocar el activo muestra la portada otra vez. */
@Composable
private fun Toggles(panel: Panel?, onPanel: (Panel?) -> Unit) {
    Row(
        Modifier.background(LocalContentColor.current.copy(alpha = 0.08f), RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Toggle(Icons.Filled.Lyrics, "Letra", panel == Panel.LYRICS) { onPanel(if (it) Panel.LYRICS else null) }
        Toggle(Icons.AutoMirrored.Filled.QueueMusic, "Cola", panel == Panel.QUEUE) { onPanel(if (it) Panel.QUEUE else null) }
        Toggle(Icons.Filled.Groups, "Sesión", panel == Panel.SESSION) { onPanel(if (it) Panel.SESSION else null) }
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = onChange) {
        Icon(
            icon,
            label,
            tint = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = if (checked) {
                Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(6.dp)
            } else {
                Modifier.padding(6.dp)
            },
        )
    }
}

/** El menú «⋮» de lo que suena. */
@Composable
private fun TrackActions(track: TrackItem, slug: String, onClose: () -> Unit, onAdd: () -> Unit) {
    val nav = LocalNav.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            DropdownMenuItem(
                text = { Text("Agregar canciones a la cola") },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { pick(onAdd) },
            )
            DropdownMenuItem(
                text = { Text("Limpiar la cola") },
                leadingIcon = { Icon(Icons.Filled.ClearAll, null) },
                onClick = { pick { Store.engine.clearQueue() } },
            )
            DropdownMenuItem(
                text = { Text("Agregar a una playlist…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { pick { Store.adding = PendingAdd(slug, listOf(track)) } },
            )
            if (!(Store.state?.remote ?: false)) {
                track.albumId?.let { album ->
                    DropdownMenuItem(
                        text = { Text("Ir al álbum") },
                        leadingIcon = { Icon(Icons.Filled.Album, null) },
                        onClick = { pick { onClose(); nav.navigate(Routes.album(slug, album)) } },
                    )
                }
                track.artistId?.let { artist ->
                    DropdownMenuItem(
                        text = { Text("Ir al artista") },
                        leadingIcon = { Icon(Icons.Filled.Person, null) },
                        onClick = { pick { onClose(); nav.navigate(Routes.artist(slug, artist)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelContent(panel: Panel, slug: String, big: Boolean, onAdd: () -> Unit) {
    when (panel) {
        Panel.LYRICS -> LyricsPanel(big)
        Panel.QUEUE -> Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cola", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, null)
                    Text("Agregar")
                }
                TextButton(onClick = { Store.engine.clearQueue() }) {
                    Icon(Icons.Filled.ClearAll, null)
                    Text("Limpiar")
                }
            }
            QueueList(Modifier.fillMaxSize())
        }
        Panel.SESSION -> SessionContent(Modifier.fillMaxSize(), slug = slug)
    }
}

// ── Letra ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsPanel(big: Boolean) {
    val state = Store.state
    val track = state?.track
    val slug = state?.provider
    if (track == null || slug == null) {
        Centered("No suena nada.")
        return
    }
    Loader(key = "$slug:${track.id}", load = { it.lyricsFor(slug, track) }) { result -> LyricsBody(result, big) }
}

@Composable
private fun LyricsBody(result: LyricsResult?, big: Boolean) {
    when {
        result != null && result.lines.isNotEmpty() && result.synced -> SyncedLyrics(result, big)
        result != null && result.lines.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
            item {
                Text(
                    result.lines.joinToString("\n") { it.text },
                    fontSize = if (big) 26.sp else 20.sp,
                    lineHeight = if (big) 36.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(16.dp))
                Text("Letra: ${result.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.instrumental == true -> Centered("♪ Instrumental")
        else -> Centered("No encontré la letra de esta canción.")
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * Como en el escritorio: la línea que suena, nítida y grande, a un tercio de la altura; las
 * demás, más pequeñas, tenues y borrosas (el desenfoque necesita Android 12; antes solo se
 * atenúan).
 */
@Composable
private fun SyncedLyrics(result: LyricsResult, big: Boolean) {
    // El motor da la posición cada medio segundo; entre medio se adelanta con el reloj, para que
    // las palabras del karaoke se enciendan a tiempo.
    val state = Store.state
    val reported = state?.positionMs?.toLong() ?: 0L
    val playing = state?.status == Status.PLAYING
    var anchor by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(reported, playing) { anchor = reported to SystemClock.uptimeMillis() }
    var tick by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val worded = remember(result) { result.lines.any { it.words.isNotEmpty() } }
    LaunchedEffect(playing, worded) {
        while (playing) {
            tick = SystemClock.uptimeMillis()
            delay(if (worded) 40 else 200)
        }
    }
    val position = if (playing) anchor.first + (tick - anchor.second).coerceIn(0, 1500) else reported
    val active = result.lines.indexOfLast { it.startMs.toLong() <= position }
    val list = rememberLazyListState()
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val target = info.viewportSize.height * 0.3f
        val item = info.visibleItemsInfo.firstOrNull { it.index == active }
        if (item == null || first) {
            // lejos (o al abrir): salta sin animar
            list.scrollToItem(active)
            first = false
            return@LaunchedEffect
        }
        // cerca: se desliza despacio hasta dejar la línea a un tercio de la altura
        val now = item.offset - info.viewportStartOffset
        list.animateScrollBy(now - target, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.65f),
        ) {
            itemsIndexed(result.lines) { index, line ->
                LyricLine(
                    text = line.text,
                    words = line.words,
                    position = if (index == active) position else 0L,
                    current = index == active,
                    distance = if (active < 0) 99 else abs(index - active),
                    big = big,
                ) { Store.engine.seek(line.startMs) }
            }
            item {
                Text(
                    "Letra: ${result.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/**
 * Una línea de la letra. Todas ocupan el espacio de la línea grande y las demás se encogen con
 * una escala, así el texto no cambia de tamaño ni empuja a las otras al pasar de una a otra.
 */
@Composable
private fun LyricLine(
    text: String,
    words: List<LyricWord>,
    position: Long,
    current: Boolean,
    distance: Int,
    big: Boolean,
    onClick: () -> Unit,
) {
    val spec = tween<Float>(durationMillis = 700, easing = FastOutSlowInEasing)
    val scale by animateFloatAsState(if (current) 1f else 0.8f, spec, label = "lyric-scale")
    val fade by animateFloatAsState(if (current) 1f else if (distance <= 1) 0.5f else 0.3f, spec, label = "lyric-alpha")
    val blur by animateDpAsState(
        if (current) 0.dp else if (distance <= 1) 2.dp else 4.dp,
        tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "lyric-blur",
    )
    val size = if (big) 38f else 28f
    val blurred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.2.dp
    val glow = LocalContentColor.current
    // Karaoke: en la línea que suena, las palabras ya cantadas se encienden y las demás esperan.
    val shown = if (current && words.isNotEmpty()) {
        buildAnnotatedString {
            for (word in words) {
                withStyle(SpanStyle(color = glow.copy(alpha = if (word.startMs.toLong() <= position) 1f else 0.4f))) {
                    append(word.text)
                }
            }
        }
    } else {
        AnnotatedString(text.ifEmpty { "♪" })
    }
    Text(
        shown,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
        fontWeight = FontWeight.Bold,
        color = LocalContentColor.current,
        modifier = Modifier
            .fillMaxWidth()
            .focusRing()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = fade
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (blurred) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
    )
}

// ── Cola ───────────────────────────────────────────────────────────────────────────────────

private fun skip(state: PlaybackState, by: Long) {
    val target = (state.positionMs.toLong() + by).coerceIn(0, state.durationMs.toLong())
    Store.engine.seek(target.toULong())
}

@Composable
private fun QueueList(modifier: Modifier) {
    val state = Store.state
    // se vuelve a leer cada vez que cambia el estado (salto, quitar, agregar, siguiente)
    val queue = remember(state?.index, state?.queueLength, state?.track?.id) { Store.engine.queue() }
    val current = state?.index?.toInt() ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    if (queue.size <= current + 1) {
        Text(
            "No hay nada después de esta canción. Toca «Agregar» para sumar más.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    LazyColumn(state = list, modifier = modifier) {
        itemsIndexed(queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .focusRing()
                    .clickable { Store.engine.jump(index.toUInt()) }
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(track.cover, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index < current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        track.artists, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index == current) {
                    Icon(Icons.Filled.MusicNote, null, Modifier.padding(end = 12.dp))
                } else {
                    IconButton(onClick = { Store.engine.removeFromQueue(index.toUInt()) }) {
                        Icon(Icons.Filled.Close, "Quitar de la cola")
                    }
                }
            }
        }
    }
}

/** Buscar canciones y sumarlas a la cola sin salir del reproductor. */
@Composable
private fun AddSongs(slug: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<TrackItem>()) }
    var searching by remember { mutableStateOf(false) }
    val added = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun run() {
        val text = query.trim()
        if (text.isEmpty()) return
        searching = true
        scope.launch {
            Store.run { it.search(slug, text) }
                .onSuccess { results = it }
                .onFailure { Store.problem = describe(it) }
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Agregar a la cola") },
        text = {
            Column(Modifier.height(440.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar canciones") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { track ->
                            val done = track.id in added
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Cover(track.cover, 40.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        track.artists, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (done) {
                                    Icon(Icons.Filled.Check, "Agregada", Modifier.padding(12.dp))
                                } else {
                                    IconButton(onClick = {
                                        Store.enqueue(listOf(track), true, slug)
                                        added += track.id
                                    }) { Icon(Icons.Filled.SkipNext, "Reproducir a continuación") }
                                    IconButton(onClick = {
                                        Store.enqueue(listOf(track), false, slug)
                                        added += track.id
                                    }) { Icon(Icons.Filled.Add, "Agregar al final") }
                                }
                            }
                        }
                    }
                    if (searching) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Listo") } },
    )
}
