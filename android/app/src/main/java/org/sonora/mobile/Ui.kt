@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.net.Uri
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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Inicio", Icons.Filled.Home),
    Tab("search", "Buscar", Icons.Filled.Search),
    Tab("library", "Biblioteca", Icons.Filled.LibraryMusic),
    Tab("session", "Sesión", Icons.Filled.Groups),
    Tab("settings", "Ajustes", Icons.Filled.Settings),
)

/** La pantalla principal: pestañas abajo, mini reproductor encima y los avisos del motor. */
@Composable
fun SonoraRoot() {
    val nav = rememberNavController()
    var player by remember { mutableStateOf(false) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(
            bottomBar = {
                Column {
                    Store.state?.let { state ->
                        state.track?.let { track -> MiniPlayer(track, state) { player = true } }
                    }
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route || (tab.route == "settings" && route?.startsWith("settings") == true),
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
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
                    AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web }, modifier = Modifier.size(1.dp).alpha(0f))
                }
            }
        }

        if (player) PlayerSheet { player = false }
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

private fun arg(entry: androidx.navigation.NavBackStackEntry, name: String): String =
    Uri.decode(entry.arguments?.getString(name) ?: "")

// ── Mini reproductor ───────────────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayer(track: TrackItem, state: PlaybackState, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
private fun PlayToggle(status: Status, big: Boolean) {
    val size = if (big) 72.dp else 40.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
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
private fun PlayerBody(state: PlaybackState, onLyrics: () -> Unit, onQueue: () -> Unit) {
    val track = state.track ?: return
    var scrub by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf(state.volume) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(track.cover, 300.dp)
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
            PlayToggle(state.status, big = true)
            IconButton(
                onClick = { Store.engine.next() },
                enabled = state.index + 1u < state.queueLength,
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(40.dp)) }
        }
        if (state.remote) {
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
            TextButton(onClick = onLyrics) {
                Icon(Icons.Filled.Lyrics, null)
                Spacer(Modifier.size(6.dp))
                Text("Letra")
            }
            TextButton(onClick = onQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                Spacer(Modifier.size(6.dp))
                Text("Cola · ${state.index + 1u} de ${state.queueLength}")
            }
        }
        Text(
            Store.providers.firstOrNull { it.slug == state.provider }?.name ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Letra ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsSheet(onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val state = Store.state
        val track = state?.track
        val slug = state?.provider
        Box(Modifier.fillMaxWidth().height(560.dp)) {
            if (track == null || slug == null) {
                Text("No suena nada.", Modifier.align(Alignment.Center))
            } else {
                Loader(key = "$slug:${track.id}", load = { it.lyricsFor(slug, track) }) { result -> LyricsBody(result) }
            }
        }
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
                modifier = Modifier.fillMaxWidth().clickable { Store.engine.seek(line.startMs) }.padding(vertical = 8.dp),
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
        val state = Store.state
        // se vuelve a leer cada vez que cambia el estado (salto, quitar, siguiente)
        val queue = remember(state?.index, state?.queueLength, state?.track?.id) { Store.engine.queue() }
        val current = state?.index?.toInt() ?: 0
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
        Text("Cola", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(560.dp)) {
            itemsIndexed(queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (index == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
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
}
