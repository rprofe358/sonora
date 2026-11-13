@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.net.Uri
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
    var player by rememberSaveable { mutableStateOf(false) }
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
                composable("genre/{slug}/{id}/{title}") { GenreScreen(arg(it, "slug"), arg(it, "id"), arg(it, "title").trim()) }
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
      Box(Modifier.fillMaxSize()) {
        if (wide) {
            // Al abrir con el control remoto, el foco empieza en el menú.
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (tv) runCatching { first.requestFocus() } }
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    NavigationRail(header = {
                        Icon(painterResource(R.drawable.ic_notification), "TK-Music", Modifier.padding(vertical = 12.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
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

        AnimatedVisibility(
            visible = player,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            PlayerScreen { player = false }
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
fun PlayToggle(status: Status, big: Boolean, modifier: Modifier = Modifier) {
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

