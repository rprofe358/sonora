package org.sonora.mobile

import android.os.Build
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

/** Los colores del reproductor: texto claro sobre la portada desenfocada. */
private val playerColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    onSurface = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.7f),
    secondaryContainer = Color.White.copy(alpha = 0.16f),
    onSecondaryContainer = Color.White,
    surfaceVariant = Color.White.copy(alpha = 0.1f),
)

/**
 * El reproductor a pantalla completa, sobre la portada desenfocada. En el teléfono la portada,
 * la letra, la cola y la sesión se turnan en el centro; en TV y tablet la portada y los controles
 * van a la izquierda y la letra (o la cola, o la sesión) a la derecha.
 */
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val wide = LocalWide.current
    var panel by remember { mutableStateOf(if (wide) Panel.LYRICS else null) }
    var adding by remember { mutableStateOf(false) }
    val play = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MaterialTheme(colorScheme = playerColors) {
            val state = Store.state
            val track = state?.track
            if (state == null || track == null) {
                LaunchedEffect(Unit) { onClose() }
                return@MaterialTheme
            }
            val tv = LocalTv.current
            LaunchedEffect(Unit) { if (tv) runCatching { play.requestFocus() } }
            val slug = state.provider ?: Store.selected
            Box(Modifier.fillMaxSize()) {
                Backdrop(track.cover)
                if (wide) {
                    Row(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                    ) {
                        BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val cover = minOf(maxWidth * 0.8f, maxHeight * 0.48f)
                            Column(
                                Modifier.verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Cover(track.cover, cover)
                                Info(track, slug, centered = true)
                                Seek(state)
                                Controls(state, play)
                                Volume(state)
                            }
                        }
                        Column(Modifier.weight(0.58f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                TrackActions(track, slug, onClose, onAdd = { adding = true })
                                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") }
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
                                null -> Cover(track.cover, minOf(maxWidth, maxHeight) - 8.dp)
                                else -> PanelContent(shown, slug, big = false, onAdd = { adding = true })
                            }
                        }
                        Info(track, slug, centered = false)
                        Seek(state)
                        Controls(state, play)
                        Volume(state)
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

/** La portada, borrosa y oscurecida, de fondo. */
@Composable
private fun Backdrop(cover: String?) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
        if (cover != null) {
            // una versión diminuta estirada ya se ve desenfocada, también donde no hay blur (Android 11)
            val request = remember(cover) { ImageRequest.Builder(context).data(fixUrl(cover)).size(40).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier,
                ),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f))),
            ),
        )
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
        IconButton(onClick = { Store.save(SavedKind.TRACK, track.id, slug) }) {
            Icon(Icons.Filled.FavoriteBorder, "Guardar en tu biblioteca")
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

@Composable
private fun Volume(state: PlaybackState) {
    when {
        state.remote -> Text(
            "Controlas la sesión: esto suena en los otros dispositivos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // en TV el volumen va con los botones del control
        LocalTv.current -> Unit
        else -> {
            var volume by remember { mutableStateOf(state.volume) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { Store.engine.setVolume(volume) },
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Letra, cola y sesión. En el teléfono, volver a tocar el activo muestra la portada otra vez. */
@Composable
private fun Toggles(panel: Panel?, onPanel: (Panel?) -> Unit) {
    Row(
        Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)),
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
                Modifier.background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(6.dp)
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
    val position = Store.state?.positionMs ?: 0uL
    val active = result.lines.indexOfLast { it.startMs <= position }
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem(active)
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

@Composable
private fun LyricLine(text: String, current: Boolean, distance: Int, big: Boolean, onClick: () -> Unit) {
    val size by animateFloatAsState(
        when {
            current && big -> 40f
            current -> 30f
            big -> 28f
            else -> 22f
        },
        label = "lyric-size",
    )
    val alpha by animateFloatAsState(if (current) 1f else if (distance <= 1) 0.5f else 0.35f, label = "lyric-alpha")
    val blur by animateDpAsState(if (current) 0.dp else if (distance <= 1) 2.dp else 4.dp, label = "lyric-blur")
    val blurred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.2.dp
    Text(
        text.ifEmpty { "♪" },
        fontSize = size.sp,
        lineHeight = (size * 1.28f).sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = alpha),
        modifier = Modifier
            .fillMaxWidth()
            .focusRing()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
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
