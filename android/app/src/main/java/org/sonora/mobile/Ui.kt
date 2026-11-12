@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uniffi.sonora_mobile.LyricsResult
import uniffi.sonora_mobile.PlaybackState
import uniffi.sonora_mobile.Status
import uniffi.sonora_mobile.TrackItem

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Destination("home", "Inicio", Icons.Filled.Home),
    Destination("search", "Buscar", Icons.Filled.Search),
    Destination("library", "Biblioteca", Icons.Filled.LibraryMusic),
    Destination("session", "Sesión", Icons.Filled.Groups),
    Destination("settings", "Ajustes", Icons.Filled.Settings),
)

/** La pantalla principal: pestañas (abajo, o a un lado en TV y tablet), mini reproductor y avisos. */
@Composable
fun SonoraRoot() {
    val nav = rememberNavController()
    var player by remember { mutableStateOf(false) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tv = remember { isTv(context) }
    val wide = tv || (configuration.screenWidthDp >= 840 && configuration.screenHeightDp >= 480)

    fun open(tab: Destination) = nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun selected(tab: Destination) = route == tab.route || (tab.route == "settings" && route?.startsWith("settings") == true)

    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen() }
                composable("search") { SearchScreen() }
                composable("library") { LibraryScreen() }
                composable("session") { SessionScreen() }
                composable("settings") { SettingsScreen() }
                composable("settings/audio") { AudioScreen() }
                composable("settings/scrobble") { ScrobbleScreen() }
                composable("settings/scrobble/{id}") { ScrobbleServiceScreen(it.arguments?.getString("id") ?: "") }
                composable("album/{slug}/{id}") { AlbumScreen(arg(it, "slug"), arg(it, "id")) }
                composable("playlist/{slug}/{id}") { PlaylistScreen(arg(it, "slug"), arg(it, "id")) }
                composable("artist/{slug}/{id}") { ArtistScreen(arg(it, "slug"), arg(it, "id")) }
            }
            // La vista web que acuña tokens de YouTube: montada, pero invisible.
            Store.minter.view?.let { web ->
                AndroidView(
                    factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web },
                    modifier = Modifier.size(1.dp).alpha(0f),
                )
            }
        }
    }
    val mini: @Composable () -> Unit = {
        Store.state?.let { state ->
            state.track?.let { track -> MiniPlayer(track, state) { player = true } }
        }
    }

    CompositionLocalProvider(LocalNav provides nav, LocalTv provides tv, LocalWide provides wide) {
        if (wide) {
            // Al abrir con el control remoto, el foco empieza en el menú.
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (tv) runCatching { first.requestFocus() } }
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    NavigationRail(header = {
                        Icon(Icons.Filled.GraphicEq, null, Modifier.padding(vertical = 12.dp), tint = MaterialTheme.colorScheme.primary)
                    }) {
                        Spacer(Modifier.weight(1f))
                        tabs.forEachIndexed { index, tab ->
                            NavigationRailItem(
                                selected = selected(tab),
                                onClick = { open(tab) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f)) { content() }
                        mini()
                    }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    Column {
                        mini()
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = selected(tab),
                                    onClick = { open(tab) },
                                    icon = { Icon(tab.icon, null) },
                                    label = { Text(tab.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }

        if (player) {
            if (wide) PlayerScreen { player = false } else PlayerSheet { player = false }
        }
        Store.prompt?.let { PromptDialog(it) }
        Store.adding?.let { PlaylistPicker(it) }
        Store.problem?.let { message ->
            AlertDialog(
                onDismissRequest = { Store.problem = null },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { Store.problem = null }) { Text("Aceptar") } },
            )
        }
    }
}

/** Android TV, Google TV o un proyector con su interfaz de televisor. */
private fun isTv(context: Context): Boolean {
    val modes = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return modes.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

private fun arg(entry: androidx.navigation.NavBackStackEntry, name: String): String =
    Uri.decode(entry.arguments?.getString(name) ?: "")

// ── Mini reproductor ───────────────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayer(track: TrackItem, state: PlaybackState, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().focusRing(RoundedCornerShape(0.dp)).clickable(onClick = onOpen)) {
            LinearProgressIndicator(progress = { progress(state) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Cover(track.cover, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (state.remote) "En la sesión · ${track.artists}" else track.artists,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { Store.engine.previous() }) { Icon(Icons.Filled.SkipPrevious, "Anterior") }
                PlayToggle(state.status, big = false)
                IconButton(onClick = { Store.engine.next() }, enabled = state.index + 1u < state.queueLength) {
                    Icon(Icons.Filled.SkipNext, "Siguiente")
                }
            }
        }
    }
}

private fun progress(state: PlaybackState): Float =
    if (state.durationMs == 0uL) 0f else (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)

@Composable
private fun PlayToggle(status: Status, big: Boolean, modifier: Modifier = Modifier) {
    val size = if (big) 72.dp else 40.dp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (status == Status.LOADING) {
            CircularProgressIndicator(Modifier.size(size * 0.6f))
        } else {
            val icon = if (status == Status.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val label = if (status == Status.PLAYING) "Pausar" else "Reproducir"
            if (big) {
                FilledIconButton(onClick = { Store.engine.toggle() }, modifier = Modifier.size(size)) {
                    Icon(icon, label, Modifier.size(40.dp))
                }
            } else {
                IconButton(onClick = { Store.engine.toggle() }) { Icon(icon, label) }
            }
        }
    }
}

// ── Reproductor completo ───────────────────────────────────────────────────────────────────

@Composable
private fun PlayerSheet(onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var lyrics by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        val state = Store.state
        if (state?.track == null) {
            LaunchedEffect(Unit) { onClose() }
            return@ModalBottomSheet
        }
        PlayerBody(state, onLyrics = { lyrics = true }, onQueue = { queue = true })
    }
    if (lyrics) LyricsSheet { lyrics = false }
    if (queue) QueueSheet { queue = false }
}

@Composable
private fun PlayerBody(
    state: PlaybackState,
    onLyrics: (() -> Unit)?,
    onQueue: (() -> Unit)?,
    cover: androidx.compose.ui.unit.Dp = 300.dp,
    play: FocusRequester? = null,
) {
    val track = state.track ?: return
    val tv = LocalTv.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf(state.volume) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(track.cover, cover)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(track.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Column {
            val duration = state.durationMs.toFloat().coerceAtLeast(1f)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconButton(onClick = { Store.engine.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(40.dp))
            }
            if (tv) {
                IconButton(onClick = { skip(state, -10_000) }) { Icon(Icons.Filled.Replay10, "Atrás 10 segundos") }
            }
            PlayToggle(state.status, big = true, modifier = play?.let { Modifier.focusRequester(it) } ?: Modifier)
            if (tv) {
                IconButton(onClick = { skip(state, 10_000) }) { Icon(Icons.Filled.Forward10, "Adelante 10 segundos") }
            }
            IconButton(
                onClick = { Store.engine.next() },
                enabled = state.index + 1u < state.queueLength,
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(40.dp)) }
        }
        if (tv && !state.remote) {
            // el volumen va con los botones del control
        } else if (state.remote) {
            Text(
                "Controlas la sesión: esto suena en los otros dispositivos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            onLyrics?.let {
                TextButton(onClick = it) {
                    Icon(Icons.Filled.Lyrics, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Letra")
                }
            }
            onQueue?.let {
                TextButton(onClick = it) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Cola · ${state.index + 1u} de ${state.queueLength}")
                }
            }
        }
        Text(
            Store.providers.firstOrNull { it.slug == state.provider }?.name ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun skip(state: PlaybackState, by: Long) {
    val target = (state.positionMs.toLong() + by).coerceIn(0, state.durationMs.toLong())
    Store.engine.seek(target.toULong())
}

/** En TV y tablet: el reproductor a pantalla completa, con la letra o la cola al lado. */
@Composable
private fun PlayerScreen(onClose: () -> Unit) {
    var side by remember { mutableStateOf(0) }
    val play = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val state = Store.state
            if (state?.track == null) {
                LaunchedEffect(Unit) { onClose() }
                return@Surface
            }
            LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlayerBody(state, onLyrics = null, onQueue = null, cover = 180.dp, play = play)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TabRow(selectedTabIndex = side, modifier = Modifier.weight(1f)) {
                            Tab(selected = side == 0, onClick = { side = 0 }, text = { Text("Letra") })
                            Tab(selected = side == 1, onClick = { side = 1 }, text = { Text("Cola · ${state.index + 1u} de ${state.queueLength}") })
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (side == 0) Lyrics() else QueueList(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

// ── Letra ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsSheet(onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().height(560.dp)) { Lyrics() }
    }
}

@Composable
private fun Lyrics() {
    val state = Store.state
    val track = state?.track
    val slug = state?.provider
    if (track == null || slug == null) {
        Centered("No suena nada.")
    } else {
        Loader(key = "$slug:${track.id}", load = { it.lyricsFor(slug, track) }) { result -> LyricsBody(result) }
    }
}

@Composable
private fun LyricsBody(result: LyricsResult?) {
    when {
        result != null && result.lines.isNotEmpty() && result.synced -> SyncedLyrics(result)
        result != null && result.lines.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(24.dp)) {
            item {
                Text(result.lines.joinToString("\n") { it.text }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(16.dp))
                Text("Letra: ${result.source}", style = MaterialTheme.typography.labelSmall)
            }
        }
        result?.instrumental == true -> Centered("Instrumental")
        else -> Centered("No encontré la letra de esta canción.")
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SyncedLyrics(result: LyricsResult) {
    val position = Store.state?.positionMs ?: 0uL
    val active = result.lines.indexOfLast { it.startMs <= position }
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem((active - 3).coerceAtLeast(0))
    }
    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)) {
        itemsIndexed(result.lines) { index, line ->
            val color by animateColorAsState(
                if (index == active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                label = "lyric",
            )
            Text(
                line.text.ifEmpty { "♪" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.fillMaxWidth().focusRing().clickable { Store.engine.seek(line.startMs) }.padding(vertical = 8.dp),
            )
        }
        item {
            Text("Letra: ${result.source}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ── Cola ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun QueueSheet(onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Cola", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        QueueList(Modifier.fillMaxWidth().height(560.dp))
    }
}

@Composable
private fun QueueList(modifier: Modifier) {
    val state = Store.state
    // se vuelve a leer cada vez que cambia el estado (salto, quitar, siguiente)
    val queue = remember(state?.index, state?.queueLength, state?.track?.id) { Store.engine.queue() }
    val current = state?.index?.toInt() ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    LazyColumn(state = list, modifier = modifier) {
        itemsIndexed(queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .focusRing()
                    .clickable { Store.engine.jump(index.toUInt()) }
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(track.cover, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal)
                    Text(track.artists, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (index == current) {
                    Icon(Icons.Filled.MusicNote, null, Modifier.padding(end = 16.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    IconButton(onClick = { Store.engine.removeFromQueue(index.toUInt()) }) {
                        Icon(Icons.Filled.Close, "Quitar de la cola")
                    }
                }
            }
        }
    }
}
